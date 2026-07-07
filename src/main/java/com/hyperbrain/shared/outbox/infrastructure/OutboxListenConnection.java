package com.hyperbrain.shared.outbox.infrastructure;

import com.hyperbrain.shared.outbox.OutboxWorker;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Dedicated PostgreSQL LISTEN/NOTIFY connection for the Transactional Outbox relay.
 *
 * <p>Maintains one connection outside of HikariCP — kept separate to avoid leaving a pooled
 * connection in LISTEN mode. On each NOTIFY on channel {@code outbox_drain} (emitted by
 * {@link JdbcOutboxRepository#append} at commit), triggers an immediate drain without waiting
 * for the backup scheduler poll.
 *
 * <p>{@code start()} registers {@code LISTEN outbox_drain} synchronously before returning so
 * callers can immediately send NOTIFYs without a race window. The receive loop runs in a
 * virtual thread.
 *
 * <p>If the connection fails the receive loop exits and logs the error; recovery is provided
 * by the backup scheduler poll (default 30 s, {@code app.outbox.poll-interval-ms}).
 *
 * <p>Enabled via {@code app.outbox.notify-listen-enabled=true} (default). Disabled in the
 * integration-test profile so {@code OutboxWorkerIT} can drive {@code drainBatch()} manually
 * without async interference.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.outbox", name = "notify-listen-enabled",
    havingValue = "true", matchIfMissing = true
)
public class OutboxListenConnection implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxListenConnection.class);

    /*
     * How long getNotifications() blocks before returning null if no notification arrived.
     * Short enough to detect stop() promptly; long enough not to spam the server.
     */
    private static final int NOTIFICATION_POLL_TIMEOUT_MS = 500;

    private final HikariDataSource hikariDataSource;
    private final OutboxWorker worker;

    private volatile Connection listenConn;
    private volatile Thread listenerThread;
    private volatile boolean running;

    public OutboxListenConnection(HikariDataSource hikariDataSource, OutboxWorker worker) {
        this.hikariDataSource = hikariDataSource;
        this.worker = worker;
    }

    /**
     * Opens the dedicated connection, registers {@code LISTEN outbox_drain} synchronously, then
     * starts the virtual receive loop. When this method returns the channel is already subscribed.
     */
    @Override
    public void start() {
        running = true;
        try {
            listenConn = openDedicatedConnection();
            PGConnection pgConn = listenConn.unwrap(PGConnection.class);
            try (Statement st = listenConn.createStatement()) {
                st.execute("LISTEN outbox_drain");
            }
            log.info("OutboxListenConnection started — listening on channel 'outbox_drain'");
            listenerThread = Thread.ofVirtual()
                .name("outbox-listen")
                .start(() -> receiveLoop(pgConn));
        } catch (SQLException e) {
            log.error("Failed to start LISTEN/NOTIFY connection — backup scheduler poll will recover events", e);
            running = false;
        }
    }

    @Override
    public void stop() {
        running = false;
        Connection conn = listenConn;
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // closing the connection causes getNotifications() to throw, exiting the loop
            }
        }
        Thread t = listenerThread;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("OutboxListenConnection stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void receiveLoop(PGConnection pgConn) {
        try {
            while (running) {
                PGNotification[] notifications = pgConn.getNotifications(NOTIFICATION_POLL_TIMEOUT_MS);
                if (notifications != null && notifications.length > 0) {
                    log.debug("Received {} NOTIFY outbox_drain signal(s) — triggering drain", notifications.length);
                    worker.drainBatch();
                }
            }
        } catch (SQLException e) {
            if (running) {
                log.error("LISTEN/NOTIFY receive loop failed — backup scheduler poll will handle recovery", e);
            }
        }
    }

    /**
     * Opens a raw JDBC connection outside of HikariCP using the pool's configured credentials.
     * Auto-commit is left at the driver default (true), which is required for LISTEN to work.
     */
    private Connection openDedicatedConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", hikariDataSource.getUsername());
        props.setProperty("password", hikariDataSource.getPassword());
        return DriverManager.getConnection(hikariDataSource.getJdbcUrl(), props);
    }
}
