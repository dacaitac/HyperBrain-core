package com.hyperbrain.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Builds test data for integration tests.
 *
 * <p>All fixture methods require an active JDBC connection. Inserts are idempotent
 * ({@code ON CONFLICT DO NOTHING}) so they can be called in {@code @BeforeEach} without
 * explicit cleanup.
 */
public final class DataFixture {

    /** Fixed UUID for the test user so all fixture rows share a stable foreign key. */
    public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private DataFixture() {}

    /**
     * Inserts the mandatory SYS_USER fixture required by domain table foreign keys.
     *
     * @param connection active JDBC connection (transaction managed by the caller)
     * @return {@link #SYSTEM_USER_ID}
     * @throws SQLException if the INSERT fails unexpectedly
     */
    public static UUID insertSystemUser(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO sys_user (id, email, password_hash, role, status, timezone, settings)
            VALUES (?, 'daniel@hyperbrain.test', 'x', 'ADMIN', 'ACTIVE', 'America/Bogota', '{}')
            ON CONFLICT (id) DO NOTHING
            """)) {
            ps.setObject(1, SYSTEM_USER_ID);
            ps.executeUpdate();
        }
        return SYSTEM_USER_ID;
    }
}
