package com.hyperbrain.sync;

import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
@TestPropertySource(properties = "app.sync.consumer.enabled=true")
@DisplayName("CALENDAR_EVENT handler — full CRUD pipeline with multiple calendars (SQS → DB)")
class CalendarEventHandlerIT {

    private static final String SYNC_QUEUE = "sync-events.fifo";

    @Autowired private SqsTemplate sqsTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM sync_mappings");
        jdbcTemplate.execute("DELETE FROM core_executable");
        jdbcTemplate.execute("DELETE FROM processed_message");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
    }

    @Test
    @DisplayName("CREATED: event is persisted as ACTIVITY with correct title and calendar name")
    void created_persists_as_activity() {
        String entityId = "EKEvent-" + UUID.randomUUID();

        send(calendarEventBody(UUID.randomUUID().toString(), entityId, "Team sync",
            "Work", "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00", "CREATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = queryExecutable(entityId);
            assertThat(row).isNotNull();
            assertThat(row.get("name")).isEqualTo("Team sync");
            assertThat(row.get("type")).isEqualTo("ACTIVITY");
            assertThat(row.get("status")).isEqualTo("TODO");
            assertThat(row.get("source_calendar")).isEqualTo("Work");
        });

        assertThat(countSyncMapping(entityId)).isEqualTo(1);
        assertThat(countOutbox("CalendarEventSyncedEvent")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CREATED: start_time and end_time are stored correctly")
    void created_stores_times() {
        String entityId = "EKEvent-" + UUID.randomUUID();

        send(calendarEventBody(UUID.randomUUID().toString(), entityId, "Doctor",
            "Health", "2026-07-10T08:00:00-05:00", "2026-07-10T09:00:00-05:00", "CREATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            int count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM core_executable e
                JOIN sync_mappings m ON m.local_id = e.id
                WHERE m.external_id = ?
                  AND e.start_time IS NOT NULL AND e.end_time IS NOT NULL
                """, Integer.class, entityId);
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("CREATED: events from different calendars (Work, Personal, Health) are each persisted")
    void events_from_different_calendars_are_isolated() {
        String workId    = "EKEvent-work-"    + UUID.randomUUID();
        String personalId = "EKEvent-personal-" + UUID.randomUUID();
        String healthId  = "EKEvent-health-"  + UUID.randomUUID();

        send(calendarEventBody(UUID.randomUUID().toString(), workId,
            "Work standup", "Work", "2026-07-07T09:00:00-05:00", null, "CREATED"),
            workId, UUID.randomUUID().toString());
        send(calendarEventBody(UUID.randomUUID().toString(), personalId,
            "Gym", "Personal", "2026-07-07T07:00:00-05:00", null, "CREATED"),
            personalId, UUID.randomUUID().toString());
        send(calendarEventBody(UUID.randomUUID().toString(), healthId,
            "Doctor checkup", "Health", "2026-07-08T10:00:00-05:00", null, "CREATED"),
            healthId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(queryExecutable(workId)).isNotNull();
            assertThat(queryExecutable(personalId)).isNotNull();
            assertThat(queryExecutable(healthId)).isNotNull();
        });

        // Verify calendars are stored correctly
        assertThat(queryExecutable(workId).get("source_calendar")).isEqualTo("Work");
        assertThat(queryExecutable(personalId).get("source_calendar")).isEqualTo("Personal");
        assertThat(queryExecutable(healthId).get("source_calendar")).isEqualTo("Health");
    }

    @Test
    @DisplayName("UPDATED: calendar name change is reflected in core_executable")
    void updated_moves_event_to_different_calendar() {
        String entityId = "EKEvent-" + UUID.randomUUID();

        // CREATED in Work calendar
        send(calendarEventBody(UUID.randomUUID().toString(), entityId, "Workshop",
            "Work", "2026-07-09T14:00:00-05:00", null, "CREATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(entityId)).isNotNull());

        // UPDATED — moved to Personal calendar (different payload → new checksum)
        send(calendarEventBody(UUID.randomUUID().toString(), entityId, "Workshop",
            "Personal", "2026-07-09T14:00:00-05:00", null, "UPDATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = queryExecutable(entityId);
            assertThat(row.get("source_calendar")).isEqualTo("Personal");
        });
    }

    @Test
    @DisplayName("second event with same checksum (same operation + same payload) is discarded silently")
    void same_checksum_discards_silently() {
        String entityId = "EKEvent-" + UUID.randomUUID();
        String title = "Same event";
        String calendar = "Work";

        // CREATED — stored checksum = SHA-256(entityId + "CREATED" + payload)
        send(calendarEventBody(UUID.randomUUID().toString(), entityId, title,
            calendar, "2026-07-07T10:00:00-05:00", "2026-07-07T11:00:00-05:00", "CREATED"),
            entityId, UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(entityId)).isNotNull());
        int outboxAfterCreated = countOutbox("CalendarEventSyncedEvent");

        // Second CREATED with same payload content — checksum matches → must be discarded
        send(calendarEventBody(UUID.randomUUID().toString(), entityId, title,
            calendar, "2026-07-07T10:00:00-05:00", "2026-07-07T11:00:00-05:00", "CREATED"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(countProcessed()).isGreaterThanOrEqualTo(2));
        assertThat(countOutbox("CalendarEventSyncedEvent")).isEqualTo(outboxAfterCreated);
    }

    @Test
    @DisplayName("DELETED: removes core_executable and sync_mapping")
    void deleted_removes_records() {
        String entityId = "EKEvent-" + UUID.randomUUID();

        send(calendarEventBody(UUID.randomUUID().toString(), entityId, "Meeting",
            "Work", "2026-07-07T09:00:00-05:00", null, "CREATED"),
            entityId, UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(entityId)).isNotNull());

        send(deletedBody(UUID.randomUUID().toString(), entityId, "CALENDAR_EVENT"),
            entityId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(queryExecutable(entityId)).isNull();
            assertThat(countSyncMapping(entityId)).isEqualTo(0);
            assertThat(countOutbox("CalendarEventDeletedEvent")).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    @DisplayName("core#65: an inbound AGENDA edit from DanielC updates the row and stages an APPLE-origin event (loop guard: origin==target is never re-written)")
    void inbound_agenda_edit_does_not_loop() {
        String entityId = "EKEvent-agenda-" + UUID.randomUUID();
        // Given an AGENDA already synced from the DanielC Google calendar (pre-existing mapping).
        UUID localId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, source_calendar)
            VALUES (?, ?, 'Standup', 'AGENDA', 'TODO', '2026-07-07T09:00:00-05:00', 'DanielC')
            """, localId, DataFixture.SYSTEM_USER_ID);
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id,
                                       last_known_checksum, sync_status)
            VALUES (?, ?, ?, 'APPLE', ?, 'stale-checksum', 'SYNCED')
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, entityId);

        // When the user edits the event in DanielC (Apple UPDATE arrives).
        send(calendarEventBody(UUID.randomUUID().toString(), entityId, "Standup (moved)",
            "DanielC", "2026-07-07T10:00:00-05:00", null, "UPDATED"),
            entityId, UUID.randomUUID().toString());

        // Then the AGENDA row is updated (still AGENDA), and the staged sync event is APPLE-origin.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = queryExecutable(entityId);
            assertThat(row).isNotNull();
            assertThat(row.get("name")).isEqualTo("Standup (moved)");
            assertThat(row.get("type")).isEqualTo("AGENDA");
        });
        // The outbox event carries source_system=APPLE — the OutboxWorker's origin != target() guard
        // therefore never hands it to the Apple propagator, so the edit does not loop back to Apple.
        // The Apple sync event carries the EKEvent id in aggregate_id and source_system=APPLE.
        String source = jdbcTemplate.queryForObject(
            "SELECT source_system FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'CalendarEventSyncedEvent' ORDER BY occurred_at DESC LIMIT 1",
            String.class, entityId);
        assertThat(source).isEqualTo("APPLE");
    }

    @Test
    @DisplayName("echo: our own event coming back under a reassigned id updates the AGENDA, never duplicating it as an ACTIVITY")
    void echo_under_reassigned_identifier_is_adopted() {
        // The production failure of 2026-08-13, reproduced. An AGENDA is written to its Google
        // calendar while its event already lived on an iCloud one; EventKit cannot move an event
        // across accounts, so it recreates it — new identifier — and our own write comes back looking
        // like somebody else's event. Ingesting it blindly gave Daniel a duplicate typed ACTIVITY and
        // left the mapping pointing at an event that no longer existed.
        UUID agenda = UUID.randomUUID();
        String deadId = "EKEvent-dead-" + UUID.randomUUID();
        String rebornId = "EKEvent-reborn-" + UUID.randomUUID();
        insertAgendaWithMapping(agenda, deadId, "Reunion Metlife",
            "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00");
        insertAppliedCalendarWrite(agenda, "Reunion Metlife",
            "2026-07-07T14:00:00Z", "2026-07-07T15:00:00Z");

        send(calendarEventBody(UUID.randomUUID().toString(), rebornId, "Reunion Metlife",
            "DanielC", "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00", "CREATED"),
            rebornId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countSyncMapping(rebornId)).isEqualTo(1));
        // One row, still the agenda entry, now reachable through the live identifier...
        assertThat(countExecutablesNamed("Reunion Metlife")).isEqualTo(1);
        assertThat(queryExecutable(rebornId)).isNotNull().containsEntry("type", "AGENDA");
        assertThat(localIdOf(rebornId)).isEqualTo(agenda);
        // ...and the dead mapping is gone, so the write-back stops aiming at a deleted event.
        assertThat(countSyncMapping(deadId)).isZero();
    }

    @Test
    @DisplayName("echo: two writes sharing title and window are ambiguous — the event is ingested as new, never merged into one of them")
    void ambiguous_echo_is_refused() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String incomingId = "EKEvent-ambiguous-" + UUID.randomUUID();
        insertAgendaWithMapping(first, "EKEvent-a-" + UUID.randomUUID(), "Daily",
            "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00");
        insertAgendaWithMapping(second, "EKEvent-b-" + UUID.randomUUID(), "Daily",
            "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00");
        insertAppliedCalendarWrite(first, "Daily", "2026-07-07T14:00:00Z", "2026-07-07T15:00:00Z");
        insertAppliedCalendarWrite(second, "Daily", "2026-07-07T14:00:00Z", "2026-07-07T15:00:00Z");

        send(calendarEventBody(UUID.randomUUID().toString(), incomingId, "Daily",
            "DanielC", "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00", "CREATED"),
            incomingId, UUID.randomUUID().toString());

        // Adopting either one would silently merge two distinct events, so a third row is the honest
        // outcome: a wrong merge is unrecoverable, a duplicate is visible and fixable.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(incomingId)).isNotNull());
        assertThat(queryExecutable(incomingId)).containsEntry("type", "ACTIVITY");
        assertThat(localIdOf(incomingId)).isNotIn(first, second);
    }

    @Test
    @DisplayName("echo: a matching write older than the echo window is not adopted — an event created by hand much later stays its own")
    void write_outside_the_echo_window_is_not_adopted() {
        UUID agenda = UUID.randomUUID();
        String incomingId = "EKEvent-late-" + UUID.randomUUID();
        insertAgendaWithMapping(agenda, "EKEvent-old-" + UUID.randomUUID(), "Psyco",
            "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00");
        insertAppliedCalendarWrite(agenda, "Psyco", "2026-07-07T14:00:00Z", "2026-07-07T15:00:00Z");
        // The write happened hours ago: whatever arrives now is a new event that merely looks alike.
        jdbcTemplate.update(
            "UPDATE sync_write_commands SET created_at = now() - interval '3 hours' WHERE local_id = ?",
            agenda);

        send(calendarEventBody(UUID.randomUUID().toString(), incomingId, "Psyco",
            "DanielC", "2026-07-07T09:00:00-05:00", "2026-07-07T10:00:00-05:00", "CREATED"),
            incomingId, UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(queryExecutable(incomingId)).isNotNull());
        assertThat(localIdOf(incomingId)).isNotEqualTo(agenda);
        assertThat(countExecutablesNamed("Psyco")).isEqualTo(2);
    }

    @Test
    @DisplayName("REMINDER_LIST and CALENDAR events are routed without error (no persistence)")
    void reminder_list_and_calendar_entities_are_accepted() {
        String listId = "EKCalList-" + UUID.randomUUID();
        String calId  = "EKCal-"    + UUID.randomUUID();

        send(calendarListBody(UUID.randomUUID().toString(), listId, "REMINDER_LIST"),
            listId, UUID.randomUUID().toString());
        send(calendarListBody(UUID.randomUUID().toString(), calId, "CALENDAR"),
            calId, UUID.randomUUID().toString());

        // Processed successfully (entries in processed_message) but no core_executable rows
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(countProcessed()).isGreaterThanOrEqualTo(2));

        assertThat(countSyncMapping(listId)).isEqualTo(0);
        assertThat(countSyncMapping(calId)).isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void send(String body, String groupId, String dedupId) {
        sqsTemplate.send(to -> to
            .queue(SYNC_QUEUE)
            .payload(body)
            .messageGroupId(groupId)
            .messageDeduplicationId(dedupId));
    }

    private Map<String, Object> queryExecutable(String externalId) {
        var rows = jdbcTemplate.queryForList("""
            SELECT e.name, e.type, e.status, e.source_calendar
            FROM core_executable e
            JOIN sync_mappings m ON m.local_id = e.id
            WHERE m.external_system = 'APPLE' AND m.external_id = ?
            """, externalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertAgendaWithMapping(UUID localId, String externalId, String name,
                                         String startTime, String endTime) {
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status, start_time, end_time,
                                         source_calendar)
            VALUES (?, ?, ?, 'AGENDA', 'TODO', ?::timestamptz, ?::timestamptz, 'DanielC')
            """, localId, DataFixture.SYSTEM_USER_ID, name, startTime, endTime);
        jdbcTemplate.update("""
            INSERT INTO sync_mappings (id, user_id, local_id, external_system, external_id,
                                       last_known_checksum, sync_status)
            VALUES (?, ?, ?, 'APPLE', ?, 'stale-checksum', 'SYNCED')
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, externalId);
    }

    /** The write-log row our own event is recognised by: title + window, as the payload carried them. */
    private void insertAppliedCalendarWrite(UUID localId, String title,
                                            String startTime, String endTime) {
        String payload = """
            {"title": "%s", "start_time": "%s", "end_time": "%s", "calendar_name": "DanielC"}
            """.formatted(title, startTime, endTime);
        jdbcTemplate.update("""
            INSERT INTO sync_write_commands
                (command_id, user_id, local_id, command_type, operation, entity_id, payload, status)
            VALUES (?, ?, ?, 'CALENDAR_EVENT', 'CREATED', 'dead-entity', ?::jsonb, 'APPLIED')
            """, UUID.randomUUID(), DataFixture.SYSTEM_USER_ID, localId, payload);
    }

    private UUID localIdOf(String externalId) {
        return jdbcTemplate.queryForObject(
            "SELECT local_id FROM sync_mappings WHERE external_system='APPLE' AND external_id=?",
            UUID.class, externalId);
    }

    private int countExecutablesNamed(String name) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM core_executable WHERE name = ?", Integer.class, name);
        return n == null ? 0 : n;
    }

    private int countSyncMapping(String externalId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM sync_mappings WHERE external_system='APPLE' AND external_id=?",
            Integer.class, externalId);
        return n == null ? 0 : n;
    }

    private int countOutbox(String eventType) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type=?", Integer.class, eventType);
        return n == null ? 0 : n;
    }

    private int countProcessed() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_message", Integer.class);
        return n == null ? 0 : n;
    }

    private static String calendarEventBody(String eventId, String entityId, String title,
                                             String calendarName, String startTime,
                                             String endTime, String operation) {
        String endField = endTime != null ? "\"end_time\": \"%s\",".formatted(endTime) : "";
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "CALENDAR_EVENT",
              "entity_id": "%s",
              "operation": "%s",
              "occurred_at": "2026-07-06T10:00:00-05:00",
              "payload": {
                "title": "%s",
                "start_time": "%s",
                %s
                "all_day": false,
                "notes": null,
                "calendar_id": "EKCalendar-id",
                "calendar_name": "%s",
                "location": null,
                "alarms": []
              }
            }
            """.formatted(eventId, entityId, operation, title, startTime, endField, calendarName);
    }

    private static String deletedBody(String eventId, String entityId, String entityType) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "%s",
              "entity_id": "%s",
              "operation": "DELETED",
              "occurred_at": "2026-07-06T11:00:00-05:00",
              "payload": null
            }
            """.formatted(eventId, entityType, entityId);
    }

    private static String calendarListBody(String eventId, String entityId, String entityType) {
        return """
            {
              "schema_version": "1",
              "event_id": "%s",
              "source_system": "APPLE",
              "entity_type": "%s",
              "entity_id": "%s",
              "operation": "CREATED",
              "occurred_at": "2026-07-06T10:00:00-05:00",
              "payload": {
                "title": "My List",
                "source_name": "iCloud",
                "color": "#FF0000",
                "is_default": false
              }
            }
            """.formatted(eventId, entityType, entityId);
    }
}
