package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A proposed {@code core_time_block} the generator places in the day, before persistence. Carries a
 * human-readable {@code reason} (legibilidad obligatoria — every block explains why it is there) and
 * the {@code wig} / {@code highLoad} flags the {@code AgendaValidator} re-checks against the walls
 * and the F6 quota. Persisted as {@code PLANNED} with {@code origin = PLANNER} once validated.
 *
 * @param executableId the executable this block schedules; never null
 * @param start        the block start instant; never null
 * @param end          the block end instant; never null, strictly after {@code start}
 * @param wig          true for the reserved WIG block (F1) — never expellable by energy nor validator
 * @param highLoad     true when the executable is high-load (counts against the F6 quota)
 * @param reason       the readable placement reason; never blank
 */
public record AgendaBlock(
    UUID executableId,
    OffsetDateTime start,
    OffsetDateTime end,
    boolean wig,
    boolean highLoad,
    String reason
) {

    public AgendaBlock {
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be strictly after start: " + start + " .. " + end);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    /** @return the block duration in minutes */
    public long durationMinutes() {
        return java.time.Duration.between(start, end).toMinutes();
    }
}
