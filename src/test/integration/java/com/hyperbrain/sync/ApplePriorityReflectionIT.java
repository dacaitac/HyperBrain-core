package com.hyperbrain.sync;

import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.application.SyncEventIngestionService;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the #66a on-event priority reflection on the <b>Apple</b> ingestion path (ADR-020, D2):
 * the Priority Score is recomputed on the row that was just merged and persisted, not on the stale
 * pre-merge one. Apple carries no second SYSTEM event — its own {@code APPLE}-origin outbox event
 * already reaches Notion — so this only pins the persisted score to the merged state.
 *
 * <p>Black-box: only the public {@link SyncEventIngestionService} and the persisted row are
 * exercised, against a real PostgreSQL. Urgency (derived by SQL over the persisted {@code end_time})
 * is the moving factor, so a CALENDAR_EVENT (ACTIVITY, which carries {@code end_time}) is used:
 * an end time beyond the urgency horizon scores 0; one inside it scores above 0. Editing the end
 * time from far to near must land the near urgency — which only holds if the rescore runs after the
 * merged {@code end_time} is upserted.
 */
@IntegrationTest
@DisplayName("Apple ingestion — on-event priority reflection scores the merged row (#66a, ADR-020)")
class ApplePriorityReflectionIT {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SyncEventIngestionService ingestionService;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM sync_mappings");
        jdbcTemplate.update("DELETE FROM processed_message");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("editing an Apple event's end time to within the urgency horizon lands the merged urgency, not the stale one")
    void apple_edit_scores_the_merged_end_time() {
        String entityId = "EKEvent-" + UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
        // An end time well beyond the 7-day horizon scores urgency 0 on CREATE.
        OffsetDateTime farEnd = OffsetDateTime.now().plusDays(60).truncatedTo(ChronoUnit.MINUTES);
        ingestionService.ingest(calendarEvent(entityId, Operation.CREATED, "Apple activity", start, farEnd));
        // The score is computed on the persisted CREATE row (post-upsert): far end -> urgency 0.
        // Against the pre-reorder code the rescore ran before the upsert, so the row's score was never
        // persisted on CREATE and this reads null.
        assertThat(persistedUrgency(entityId)).isNotNull().isZero();

        // Editing the end time to tomorrow (well inside the horizon) must move urgency above 0.
        OffsetDateTime nearEnd = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
        ingestionService.ingest(calendarEvent(entityId, Operation.UPDATED, "Apple activity", start, nearEnd));

        // The persisted urgency reflects the MERGED (near) end time, not the stale pre-merge far one.
        assertThat(persistedUrgency(entityId)).isNotNull().isGreaterThan(0.0);
        assertThat(persistedPriority(entityId)).isNotNull().isGreaterThan(0.0);
    }

    private Double persistedUrgency(String entityId) {
        return jdbcTemplate.queryForObject("""
            SELECT e.urgency_score FROM core_executable e JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_system = 'APPLE' AND m.external_id = ?
            """, Double.class, entityId);
    }

    private Double persistedPriority(String entityId) {
        return jdbcTemplate.queryForObject("""
            SELECT e.priority_score FROM core_executable e JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_system = 'APPLE' AND m.external_id = ?
            """, Double.class, entityId);
    }

    private static SentinelEvent calendarEvent(String entityId, Operation operation, String title,
                                               OffsetDateTime start, OffsetDateTime end) {
        String payload = """
            {
              "title": "%s",
              "start_time": "%s",
              "end_time": "%s",
              "all_day": false,
              "notes": null,
              "calendar_id": "EKCalendar-hb",
              "calendar_name": "HyperBrain",
              "alarms": []
            }
            """.formatted(title, start, end);
        return new SentinelEvent("1", UUID.randomUUID().toString(), "APPLE",
            EntityType.CALENDAR_EVENT, entityId, operation, OffsetDateTime.now(), payload);
    }
}
