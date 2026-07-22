package com.hyperbrain.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.application.AgendaDeliveryService;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pipeline-level test of the single-owner materialization cut-over (HU-01c H2): the morning trigger
 * emits a {@code DailyAgendaRequestedEvent} to {@code ia-jobs} through the Transactional Outbox, and
 * the {@code AgendaJobConsumer} is the sole materializer. Both cut-over flags are on here; the sync
 * path (flags off) stays covered by {@code MorningAgendaDispatchIT} and {@code UserCommandConsumerIT}.
 *
 * <p>The outbox drain is invoked explicitly ({@code scheduling-enabled=false} in the profile), so the
 * emit → consume → materialize cycle is deterministic.
 */
@IntegrationTest
@TestPropertySource(properties = {
    "app.planner.agenda-job-consumer.enabled=true",
    "app.planner.materialization.async-enabled=true"
})
@DisplayName("AgendaJobConsumer — ia-jobs single-owner materialization (HU-01c H2)")
class AgendaJobConsumerIT {

    private static final String IA_JOBS_QUEUE = "ia-jobs";
    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // Cold-start wake edge 06:30 (+10 min lead = 06:40 trigger); a run at 06:41 is due.
    private static final String SLEEP_WINDOW_SETTINGS =
        "{\"planner_constraints\":{\"sleep_window\":{\"wake\":\"06:30\",\"bedtime\":\"23:00\"}}}";
    private static final OffsetDateTime DUE_NOW =
        OffsetDateTime.of(2026, 7, 10, 6, 41, 0, 0, ZoneOffset.UTC);

    @Autowired private AgendaDeliveryService deliveryService;
    @Autowired private OutboxWorker outboxWorker;
    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM planner_agenda_materialization");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM sync_write_commands");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM tel_sleep_record");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update(
            "UPDATE sys_user SET timezone = 'UTC', settings = ?::jsonb WHERE id = ?",
            SLEEP_WINDOW_SETTINGS, USER);
        drainQueue(IA_JOBS_QUEUE);
    }

    @Test
    @DisplayName("emit → drain → consume → materialize: the trigger enqueues a job the consumer plans")
    void full_cycle_emit_consume_materialize() {
        insertTask("Deep work", 0.9, 60);

        // The trigger emits an ia-job (guard + IA_JOB outbox row) but does not generate in-process.
        boolean fired = deliveryService.dispatchIfDue(USER, ZONE, DUE_NOW);
        assertThat(fired).isTrue();
        assertThat(iaJobOutboxCount()).isEqualTo(1);
        assertThat(countPlannedBlocks()).isZero();

        // Draining the outbox publishes the job to ia-jobs; the single owner materializes it.
        outboxWorker.drainBatch();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countPlannedBlocks()).isEqualTo(1));
        assertThat(materializationRows()).isEqualTo(1);
        // Delivery to iOS rides the existing outbox path (AgendaBlockPlannedEvent staged).
        await().atMost(TIMEOUT).untilAsserted(() ->
            assertThat(agendaBlockOutboxCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("an at-least-once redelivery of the same job is deduplicated (no duplicate blocks)")
    void redelivery_is_deduplicated() {
        insertTask("Deep work", 0.9, 60);
        String envelope = morningEnvelope(DUE_NOW);

        // Two deliveries of the identical job (same frozen T ⇒ same input hash). The (user, day,
        // input_hash) claim is race-free: the second delivery can never add a second claim or a second
        // block, whether it processes before or after the assertion — so the invariant is stable.
        sendToIaJobs(envelope);
        sendToIaJobs(envelope);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countPlannedBlocks()).isEqualTo(1));
        assertThat(materializationRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty window: the next-day proposal is staged in-tx and delivered on drain (exactly-once)")
    void empty_window_proposal_rides_the_outbox() {
        // No schedulable task → the materialized day is empty.
        sendToIaJobs(morningEnvelope(DUE_NOW));

        // The claim and the proposal event are committed atomically by the consumer — no block, no
        // post-commit publish that a crash could drop.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(emptyProposalOutboxCount()).isEqualTo(1));
        assertThat(materializationRows()).isEqualTo(1);
        assertThat(countPlannedBlocks()).isZero();
        assertThat(reminderCommandCount()).isZero();

        // Draining the outbox turns the staged proposal into the "planned for tomorrow" reminder.
        outboxWorker.drainBatch();
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(reminderCommandCount()).isEqualTo(1));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void sendToIaJobs(String body) {
        sqsTemplate.send(to -> to.queue(IA_JOBS_QUEUE).payload(body));
    }

    private String morningEnvelope(OffsetDateTime referenceInstant) {
        return """
            {
              "event_type": "DailyAgendaRequestedEvent",
              "aggregate_type": "IA_JOB",
              "aggregate_id": "%s",
              "payload": {
                "user_id": "%s",
                "agenda_date": "2026-07-10",
                "zone_id": "UTC",
                "reference_instant": "%s",
                "from_now": false
              }
            }
            """.formatted(USER, USER, referenceInstant);
    }

    private void insertTask(String name, double priority, int estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, priority_score)
            VALUES (?, ?, ?, 'TASK', 'TODO', ?)
            """, id, USER, name, priority);
        jdbcTemplate.update("""
            INSERT INTO core_execution_profile (executable_id, estimated_minutes)
            VALUES (?, ?)
            """, id, estimatedMinutes);
    }

    private int countPlannedBlocks() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_time_block WHERE status = 'PLANNED' AND origin = 'PLANNER'",
            Integer.class);
        return count == null ? 0 : count;
    }

    private int materializationRows() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM planner_agenda_materialization WHERE user_id = ?", Integer.class, USER);
        return count == null ? 0 : count;
    }

    private int iaJobOutboxCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_type = 'IA_JOB'", Integer.class);
        return count == null ? 0 : count;
    }

    private int agendaBlockOutboxCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'AgendaBlockPlannedEvent'",
            Integer.class);
        return count == null ? 0 : count;
    }

    private int emptyProposalOutboxCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'EmptyAgendaProposedEvent'",
            Integer.class);
        return count == null ? 0 : count;
    }

    private int reminderCommandCount() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_write_commands WHERE command_type = 'REMINDER' "
                + "AND operation = 'CREATED'", Integer.class);
        return count == null ? 0 : count;
    }

    private Optional<Message<String>> receiveOne(String queue) {
        return sqsTemplate.receive(from -> from
            .queue(queue)
            .pollTimeout(Duration.ofSeconds(2)), String.class);
    }

    private void drainQueue(String queue) {
        while (receiveOne(queue).isPresent()) {
            // drain until empty
        }
    }
}
