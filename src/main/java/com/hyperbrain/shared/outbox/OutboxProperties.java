package com.hyperbrain.shared.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the Transactional Outbox relay. Bound from the {@code app.outbox.*} namespace.
 */
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    /** Delay between backup drain polls, in milliseconds. Primary trigger is LISTEN/NOTIFY. */
    private long pollIntervalMs = 30000;

    /** Maximum events claimed per drain. */
    private int batchSize = 50;

    /** Retention window before processed events are purged, in days. */
    private int retentionDays = 7;

    /** Whether the scheduled drain/purge triggers are active. Disabled in tests. */
    private boolean schedulingEnabled = true;

    /**
     * Whether the dedicated PostgreSQL LISTEN/NOTIFY connection is active.
     * When true, inserts to {@code outbox_events} trigger an immediate drain via
     * {@code NOTIFY outbox_drain}; the backup poll ({@code poll-interval-ms}) runs at a long
     * interval for recovery only. Disabled in integration tests to avoid async interference.
     */
    private boolean notifyListenEnabled = true;

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public boolean isNotifyListenEnabled() {
        return notifyListenEnabled;
    }

    public void setNotifyListenEnabled(boolean notifyListenEnabled) {
        this.notifyListenEnabled = notifyListenEnabled;
    }
}
