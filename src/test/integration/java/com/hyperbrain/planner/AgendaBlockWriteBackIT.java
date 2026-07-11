package com.hyperbrain.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.shared.outbox.OutboxWorker;
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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the morning agenda write-back (HU-01b): an {@code AgendaBlockPlannedEvent} drained from the
 * outbox materializes each {@code PLANNED} block as a reminder {@code WriteCommand} on
 * {@code apple-commands.fifo}, deliberately routing <b>around</b> the {@code system_generated}
 * suppression that keeps ordinary planner accounting rows off Apple.
 */
@IntegrationTest
@DisplayName("AgendaBlockPropagator — morning agenda write-back (HU-01b)")
class AgendaBlockWriteBackIT {

    private static final String COMMANDS_QUEUE = "apple-commands.fifo";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final OffsetDateTime BLOCK_START = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, UTC);
    private static final OffsetDateTime BLOCK_END = OffsetDateTime.of(2026, 7, 10, 10, 0, 0, 0, UTC);
    private static final OffsetDateTime NOON = OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, UTC);

    @Autowired private OutboxWorker outboxWorker;
    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
        drainQueue(COMMANDS_QUEUE);
    }

    @Test
    @DisplayName("a PLANNED block reaches apple-commands.fifo as a calendar event, past the system-generated guard")
    void planned_block_is_delivered_as_calendar_event() throws Exception {
        // Given a system-generated executable with a PLANNED/PLANNER block carrying a reason
        UUID executable = insertSystemGeneratedExecutable("Deep work");
        UUID blockId = insertPlannedBlock(executable, "Reserved as the WIG lead measure");
        insertAgendaBlockEvent("Sleep Score 80 -> margin +0.6 -> quota 3");

        // When the outbox drains
        outboxWorker.drainBatch();

        // Then a CALENDAR_EVENT CREATED command lands as a time-boxed event (start + end, not due_date)
        Message<String> message = receiveOne(COMMANDS_QUEUE).orElseThrow();
        JsonNode command = objectMapper.readTree(message.getPayload());
        assertThat(command.path("command_type").asText()).isEqualTo("CALENDAR_EVENT");
        assertThat(command.path("operation").asText()).isEqualTo("CREATED");

        JsonNode payload = command.path("payload");
        assertThat(payload.path("title").asText()).isEqualTo("Deep work");
        // The block's temporal span is preserved: start + end, no due_date.
        assertThat(payload.path("start_time").asText()).startsWith("2026-07-10T09:00:00");
        assertThat(payload.path("end_time").asText()).startsWith("2026-07-10T10:00:00");
        assertThat(payload.has("due_date")).isFalse();
        // Destination is HyperBrain's own writable calendar, never a read-only AGENDA one (ADR-009).
        assertThat(payload.path("calendar_name").asText()).isEqualTo("HyperBrain");
        assertThat(payload.path("calendar_id").asText()).isEmpty();
        String notes = payload.path("notes").asText();
        assertThat(notes).contains("Reserved as the WIG lead measure");
        assertThat(notes).contains("Sleep Score 80");

        // And the command is logged under the BLOCK id (not the executable id) so mappings never collide
        UUID localId = jdbcTemplate.queryForObject(
            "SELECT local_id FROM sync_write_commands WHERE command_id = ?::uuid",
            UUID.class, command.path("command_id").asText());
        assertThat(localId).isEqualTo(blockId);
    }

    @Test
    @DisplayName("no PLANNED blocks for the day: nothing is emitted")
    void no_blocks_emits_nothing() {
        insertAgendaBlockEvent("Sleep Score 80 -> margin +0.6 -> quota 3");

        outboxWorker.drainBatch();

        assertThat(receiveOne(COMMANDS_QUEUE)).isEmpty();
        Integer commands = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_write_commands", Integer.class);
        assertThat(commands).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertSystemGeneratedExecutable(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, system_generated)
            VALUES (?, ?, ?, 'TASK', 'TODO', true)
            """, id, USER, name);
        return id;
    }

    private UUID insertPlannedBlock(UUID executableId, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin, reason)
            VALUES (?, ?, ?, ?, 'PLANNED', 'PLANNER', ?)
            """, id, executableId, BLOCK_START, BLOCK_END, reason);
        return id;
    }

    private void insertAgendaBlockEvent(String energyCriterion) {
        String payload = """
            {"user_id":"%s","target_day":"2026-07-10","zone_id":"UTC","energy_criterion":"%s"}
            """.formatted(USER, energyCriterion);
        jdbcTemplate.update("""
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at)
            VALUES (?, 'AGENDA_BLOCK', ?, 'AgendaBlockPlannedEvent', ?::jsonb, 'SYSTEM', ?)
            """, UUID.randomUUID(), USER.toString(), payload, NOON);
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
