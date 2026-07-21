package com.hyperbrain.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.application.AgendaDeliveryService;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the morning dispatch orchestration (HU-01b): the once-per-day trigger guard and the
 * negative case where an empty window must never produce silent, empty delivery — instead a readable
 * "no blocks today, planned for tomorrow" signal reaches the user (Triángulo de Control).
 */
@IntegrationTest
@DisplayName("AgendaDeliveryService — morning dispatch orchestration (HU-01b)")
class MorningAgendaDispatchIT {

    private static final String COMMANDS_QUEUE = "apple-commands.fifo";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneId ZONE = ZoneOffset.UTC;
    // The test seeds its own cold-start wake edge (06:30) via settings.planner_constraints.sleep_window
    // so the trigger is derived deterministically from a fixture the test owns, not from the
    // production DEFAULT_WAKE constant. Wake 06:30 + 10-min lead offset = 06:40 trigger; a run at
    // 06:41 is due.
    private static final String SLEEP_WINDOW_SETTINGS =
        "{\"planner_constraints\":{\"sleep_window\":{\"wake\":\"06:30\",\"bedtime\":\"23:00\"}}}";
    private static final OffsetDateTime DUE_NOW = OffsetDateTime.of(2026, 7, 10, 6, 41, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEFORE_TRIGGER =
        OffsetDateTime.of(2026, 7, 10, 5, 0, 0, 0, ZoneOffset.UTC);

    @Autowired private AgendaDeliveryService deliveryService;
    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update(
            "UPDATE sys_user SET timezone = 'UTC', settings = ?::jsonb WHERE id = ?",
            SLEEP_WINDOW_SETTINGS, USER);
        drainQueue(COMMANDS_QUEUE);
    }

    @Test
    @DisplayName("not due: before the trigger minute nothing fires and no state is written")
    void does_not_fire_before_trigger() {
        boolean fired = deliveryService.dispatchIfDue(USER, ZONE, BEFORE_TRIGGER);

        assertThat(fired).isFalse();
        assertThat(lastFiredDay()).isNull();
    }

    @Test
    @DisplayName("empty window: no blocks yields a next-day proposal signal, never a silent empty delivery")
    void empty_window_proposes_next_day() throws Exception {
        // Given no schedulable executables at all → the generated day has no blocks.
        boolean fired = deliveryService.dispatchIfDue(USER, ZONE, DUE_NOW);

        assertThat(fired).isTrue();
        // No AgendaBlockPlannedEvent is staged (there are no blocks to deliver)...
        Integer blockEvents = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'AGENDA_BLOCK'", Integer.class);
        assertThat(blockEvents).isZero();

        // ...but a single readable next-day proposal reminder is emitted directly. Assert on the
        // durable command log (the FIFO message is subject to cross-test deduplication by the
        // deterministic command id, so the log row is the reliable evidence).
        Integer signals = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_write_commands WHERE command_type = 'REMINDER' "
                + "AND operation = 'CREATED'", Integer.class);
        assertThat(signals).isEqualTo(1);
        String payloadJson = jdbcTemplate.queryForObject(
            "SELECT payload::text FROM sync_write_commands LIMIT 1", String.class);
        JsonNode payload = objectMapper.readTree(payloadJson);
        assertThat(payload.path("title").asText()).contains("No agenda blocks");
    }

    @Test
    @DisplayName("once per day: a second dispatch after firing is a no-op")
    void fires_at_most_once_per_day() {
        boolean first = deliveryService.dispatchIfDue(USER, ZONE, DUE_NOW);
        boolean second = deliveryService.dispatchIfDue(USER, ZONE, DUE_NOW);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(lastFiredDay()).isEqualTo("2026-07-10");
    }

    private String lastFiredDay() {
        return jdbcTemplate.queryForObject(
            "SELECT settings #>> '{planner_state,morning_trigger,last_fired_day}' FROM sys_user WHERE id = ?",
            String.class, USER);
    }

    private Optional<Message<String>> receiveOne(String queue) {
        return sqsTemplate.receive(from -> from
            .queue(queue)
            .pollTimeout(Duration.ofSeconds(5)), String.class);
    }

    private void drainQueue(String queue) {
        while (receiveOne(queue).isPresent()) {
            // drain until empty
        }
    }
}
