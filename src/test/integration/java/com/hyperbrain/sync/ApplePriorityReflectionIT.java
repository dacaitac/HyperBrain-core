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

import java.time.LocalDate;
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
 * exercised, against a real PostgreSQL. Since ADR-026 urgency is derived from the executable's cycle
 * deadline ({@code MIN(core_cycle.end_date)} over its chain), not from {@code end_time}; the moving
 * factor here is therefore the row's persisted {@code cycle_id}. A CREATE lands with no cycle (Apple
 * never sets one) and scores urgency 0 — persisted, not null, which already fails the pre-reorder code
 * that scored before the upsert. Assigning the row to a cycle with a near deadline and re-ingesting an
 * UPDATE must land that near urgency, which only holds if the rescore reads the merged/persisted row
 * (the merge preserves {@code cycle_id}, {@link com.hyperbrain.sync.domain.service.SourceAwareMerge}).
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
        jdbcTemplate.update("UPDATE core_executable SET cycle_id = NULL");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("re-ingesting an Apple event after it joins a near-deadline cycle lands the merged urgency, not the stale one")
    void apple_edit_scores_the_merged_row() {
        String entityId = "EKEvent-" + UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
        OffsetDateTime end = OffsetDateTime.now().plusDays(1).plusHours(1).truncatedTo(ChronoUnit.MINUTES);
        // CREATE lands with no cycle (Apple never assigns one) -> urgency 0, persisted (not null).
        // Against the pre-reorder code the rescore ran before the upsert, so the row's score was never
        // persisted on CREATE and this reads null.
        ingestionService.ingest(calendarEvent(entityId, Operation.CREATED, "Apple activity", start, end));
        assertThat(persistedUrgency(entityId)).isNotNull().isZero();

        // The row later joins a cycle whose deadline is inside the urgency horizon (ADR-026 source).
        UUID cycle = insertCycle(LocalDate.now().plusDays(1));
        jdbcTemplate.update("""
            UPDATE core_executable e SET cycle_id = ?
            FROM sync_mappings m
            WHERE m.local_id = e.id AND m.external_system = 'APPLE' AND m.external_id = ?
            """, cycle, entityId);

        // Re-ingesting an UPDATE (merge preserves cycle_id) must land the near cycle-deadline urgency.
        ingestionService.ingest(calendarEvent(entityId, Operation.UPDATED, "Apple activity", start, end));

        // The persisted urgency reflects the MERGED/persisted row (its cycle deadline), not the stale
        // pre-merge cycle-less state.
        assertThat(persistedUrgency(entityId)).isNotNull().isGreaterThan(0.0);
        assertThat(persistedPriority(entityId)).isNotNull().isGreaterThan(0.0);
    }

    private UUID insertCycle(LocalDate endDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_cycle (id, user_id, name, type, status, end_date)
            VALUES (?, ?, 'Near cycle', 'PROJECT', 'ACTIVE', ?)
            """, id, DataFixture.SYSTEM_USER_ID, endDate);
        return id;
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
