package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.MorningTriggerState;
import com.hyperbrain.planner.domain.port.out.MorningTriggerStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * JDBC adapter for {@link MorningTriggerStore} backed by {@code sys_user.settings} (JSONB). The state
 * lives under {@code planner_state.morning_trigger} as
 * {@code {"previous_trigger_minute": <int>, "last_fired_day": "YYYY-MM-DD"}}; storing it in the
 * existing settings column avoids a schema change for the MVP's single user (a dedicated column can
 * replace it later without touching the port).
 *
 * <p>Writes use {@code jsonb_set} with {@code create_missing = true} so the sibling settings keys
 * ({@code planner_constants}, {@code planner_constraints}) are preserved; the intermediate
 * {@code planner_state} object is created when absent.
 */
@Repository
class JdbcMorningTriggerStore implements MorningTriggerStore {

    private static final String LOAD_SQL = """
        SELECT (settings #>> '{planner_state,morning_trigger,previous_trigger_minute}')::int AS previous_minute,
                settings #>> '{planner_state,morning_trigger,last_fired_day}'                AS last_fired_day
        FROM sys_user
        WHERE id = ?
        """;

    // Ensures the planner_state parent object exists, then sets the morning_trigger subtree in one go.
    private static final String SAVE_SQL = """
        UPDATE sys_user
        SET settings = jsonb_set(
                jsonb_set(
                    COALESCE(settings, '{}'::jsonb),
                    '{planner_state}',
                    COALESCE(settings #> '{planner_state}', '{}'::jsonb),
                    true),
                '{planner_state,morning_trigger}',
                ?::jsonb,
                true)
        WHERE id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    JdbcMorningTriggerStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MorningTriggerState load(UUID userId) {
        return jdbcTemplate.query(LOAD_SQL, rs -> {
            if (!rs.next()) {
                return MorningTriggerState.EMPTY;
            }
            Integer previousMinute = rs.getObject("previous_minute", Integer.class);
            String lastFiredDay = rs.getString("last_fired_day");
            LocalTimeOfDay anchor = previousMinute != null ? new LocalTimeOfDay(previousMinute) : null;
            LocalDate firedDay = lastFiredDay != null ? LocalDate.parse(lastFiredDay) : null;
            return new MorningTriggerState(anchor, firedDay);
        }, userId);
    }

    @Override
    public void save(UUID userId, MorningTriggerState state) {
        jdbcTemplate.update(SAVE_SQL, toJson(state), userId);
    }

    private static String toJson(MorningTriggerState state) {
        String previousMinute = state.previousTriggerMinuteOfDay() != null
            ? String.valueOf(state.previousTriggerMinuteOfDay().minutesOfDay())
            : "null";
        String lastFiredDay = state.lastFiredDay() != null
            ? "\"" + state.lastFiredDay() + "\""
            : "null";
        return "{\"previous_trigger_minute\": " + previousMinute
            + ", \"last_fired_day\": " + lastFiredDay + "}";
    }
}
