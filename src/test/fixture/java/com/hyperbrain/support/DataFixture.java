package com.hyperbrain.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Builds test data for integration tests.
 *
 * <p>Fixtures will be populated once DDL v1 (issue #2) adds the domain tables.
 * Each fixture method inserts the minimum data needed for a specific test scenario.
 */
public final class DataFixture {

    private DataFixture() {}

    /**
     * Inserts the mandatory SYS_USER fixture required by domain table foreign keys.
     *
     * @param connection  active JDBC connection with an open transaction
     * @return the UUID of the inserted user
     * @throws SQLException if the INSERT fails
     */
    public static UUID insertSystemUser(Connection connection) throws SQLException {
        // TODO: implement after SYS_USER table is created in issue #2
        return UUID.randomUUID();
    }
}
