package com.hyperbrain;

import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@DisplayName("PostgreSQL connection and V1 DDL")
class PostgresConnectionIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Spring context loads and datasource is reachable")
    void contextLoads_and_datasource_is_reachable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.isValid(5)).isTrue();
        }
    }

    @Test
    @DisplayName("Flyway V1 migration creates outbox_events table")
    void v1_migration_creates_outbox_events_table() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ResultSet rs = conn.getMetaData()
                .getTables(null, "public", "outbox_events", new String[]{"TABLE"});
            assertThat(rs.next()).as("outbox_events table must exist after V1 migration").isTrue();
        }
    }

    @Test
    @DisplayName("Flyway V1 migration creates processed_message table")
    void v1_migration_creates_processed_message_table() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ResultSet rs = conn.getMetaData()
                .getTables(null, "public", "processed_message", new String[]{"TABLE"});
            assertThat(rs.next()).as("processed_message table must exist after V1 migration").isTrue();
        }
    }

    @Test
    @DisplayName("pgvector extension is active")
    void pgvector_extension_is_active() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).as("pgvector extension must be installed").isTrue();
        }
    }

    @Test
    @DisplayName("All 23 domain tables from V1 DDL exist in public schema")
    void allDomainTablesExist() throws Exception {
        List<String> expected = List.of(
                "sys_user", "sync_credential",
                "core_cycle", "core_executable",
                "core_execution_profile", "core_time_block",
                "fin_account", "fin_category", "fin_budget_template",
                "fin_budget", "fin_goal", "fin_transaction", "fin_networth_snapshot",
                "lrn_topic", "lrn_assessment",
                "tel_sleep_record", "tel_activity_stream",
                "brain_idea", "context_event", "rag_embedding",
                "sync_mappings", "outbox_events", "processed_message"
        );

        try (Connection conn = dataSource.getConnection()) {
            List<String> missing = new ArrayList<>();
            for (String table : expected) {
                ResultSet rs = conn.getMetaData()
                        .getTables(null, "public", table, new String[]{"TABLE"});
                if (!rs.next()) {
                    missing.add(table);
                }
            }
            assertThat(missing)
                    .as("Tables missing from V1 DDL (check V1__init.sql vs supabase migrations): %s", missing)
                    .isEmpty();
        }
    }
}
