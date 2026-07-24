package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter for {@link SleepScoreStore}: at most one {@code tel_sleep_record} row per user and
 * local day carries the manual score.
 *
 * <p><b>Row-shape contract (frontier vs. energy).</b> A marker row inserted here has
 * {@code end_time = NULL} and {@code start_time} anchored to the day's local midnight (the column
 * is NOT NULL; the value is a placeholder, never sleep data). The sleep-frontier queries filter on
 * {@code end_time IS NOT NULL} — both for the samples and for the freshness guard — so a marker
 * row cannot skew the wake/bedtime median, while the last-night score query does pick it up for
 * the F3/F6 energy resolution.
 *
 * <p><b>Device precedence (Daniel, 2026-07-11).</b> The day's governing record is resolved
 * preferring rows with real hours over markers. When it is a complete device record
 * (hours + score), the manual score is discarded ({@code false}). A device record with hours but
 * no score does accept the manual score — and from then on the row counts as device-owned, so a
 * later manual correction is rejected; accepted consequence of the sanctioned rule.
 *
 * <p>The read-then-write pair is not guarded against concurrent writers: {@code user-commands.fifo}
 * is FIFO with a single message group, so commands for the single MVP user are processed strictly
 * sequentially.
 */
@Repository
class JdbcSleepScoreStore implements SleepScoreStore {

    /**
     * Resolves the day's governing record: a real telemetry record is "of the day" when its wake
     * ({@code end_time}) falls on it; a manual marker ({@code end_time IS NULL}) when its midnight
     * anchor ({@code start_time}) does. Rows with real hours take precedence over markers; recency
     * breaks ties within the same class — mirroring the energy query's ordering.
     */
    private static final String DAY_GOVERNING_ROW_SQL = """
        SELECT id, (end_time IS NOT NULL AND sleep_score IS NOT NULL) AS device_owned
        FROM tel_sleep_record
        WHERE user_id = ?
          AND (COALESCE(end_time, start_time) AT TIME ZONE ?)::date = ?
        ORDER BY (end_time IS NOT NULL) DESC, collected_at DESC
        LIMIT 1
        """;

    private static final String UPDATE_SCORE_SQL =
        "UPDATE tel_sleep_record SET sleep_score = ?, collected_at = ? WHERE id = ?";

    private static final String INSERT_MARKER_SQL = """
        INSERT INTO tel_sleep_record (id, user_id, start_time, end_time, sleep_score, collected_at)
        VALUES (?, ?, ?, NULL, ?, ?)
        """;

    /**
     * The id of the row governing a given local day (any class), used to decide UPDATE vs INSERT when
     * a device record lands. Rows with real hours rank over markers; recency breaks ties.
     */
    private static final String GOVERNING_ROW_ID_SQL = """
        SELECT id
        FROM tel_sleep_record
        WHERE user_id = ?
          AND (COALESCE(end_time, start_time) AT TIME ZONE ?)::date = ?
        ORDER BY (end_time IS NOT NULL) DESC, collected_at DESC
        LIMIT 1
        """;

    private static final String UPDATE_DEVICE_RECORD_SQL = """
        UPDATE tel_sleep_record
        SET start_time = ?, end_time = ?, duration_minutes = ?, sleep_score = ?, stages = ?::jsonb,
            collected_at = ?, context_event_id = ?
        WHERE id = ?
        """;

    private static final String INSERT_DEVICE_RECORD_SQL = """
        INSERT INTO tel_sleep_record
            (id, user_id, start_time, end_time, duration_minutes, sleep_score, stages, collected_at, context_event_id)
        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcSleepScoreStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean upsertDailyScore(UUID userId, LocalDate date, int score,
                                    OffsetDateTime collectedAt, ZoneId zone) {
        List<DayRow> rows = jdbcTemplate.query(DAY_GOVERNING_ROW_SQL,
            (rs, rowNum) -> new DayRow(rs.getObject("id", UUID.class), rs.getBoolean("device_owned")),
            userId, zone.getId(), Date.valueOf(date));

        if (rows.isEmpty()) {
            jdbcTemplate.update(INSERT_MARKER_SQL,
                UUID.randomUUID(), userId,
                date.atStartOfDay(zone).toOffsetDateTime(), score, collectedAt);
            return true;
        }
        DayRow governing = rows.getFirst();
        if (governing.deviceOwned()) {
            return false;
        }
        jdbcTemplate.update(UPDATE_SCORE_SQL, score, collectedAt, governing.id());
        return true;
    }

    @Override
    public void upsertDeviceSleepRecord(UUID userId, DeviceSleepRecord record, ZoneId zone) {
        LocalDate wakeDay = record.endTime().atZoneSameInstant(zone).toLocalDate();
        List<UUID> governing = jdbcTemplate.query(GOVERNING_ROW_ID_SQL,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            userId, zone.getId(), Date.valueOf(wakeDay));

        if (governing.isEmpty()) {
            jdbcTemplate.update(INSERT_DEVICE_RECORD_SQL,
                UUID.randomUUID(), userId, record.startTime(), record.endTime(),
                record.durationMinutes(), record.sleepScore(), record.stagesJson(),
                record.collectedAt(), record.contextEventId());
            return;
        }
        // Device precedence: convert an existing manual marker — or supersede a prior device record —
        // into this complete device record, keeping at most one row per day.
        jdbcTemplate.update(UPDATE_DEVICE_RECORD_SQL,
            record.startTime(), record.endTime(), record.durationMinutes(), record.sleepScore(),
            record.stagesJson(), record.collectedAt(), record.contextEventId(), governing.getFirst());
    }

    private record DayRow(UUID id, boolean deviceOwned) {
    }
}
