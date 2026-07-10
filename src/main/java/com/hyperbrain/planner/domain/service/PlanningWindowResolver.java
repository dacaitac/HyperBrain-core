package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.LocalTimeOfDay;
import com.hyperbrain.planner.domain.model.PlanningWindow;
import com.hyperbrain.planner.domain.model.SleepWindow;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure domain service that resolves a {@link SleepWindow} (wall-clock times of day) into concrete
 * instants for a target day in the user's timezone, and derives the lower bound for the two run
 * modes. Kept free of the ambient clock: the caller supplies {@code now} and the {@code zone}.
 *
 * <p><b>Midnight crossing.</b> When the bedtime edge is earlier in the day than the wake edge (a
 * bedtime past 00:00), the frontier spans into the next calendar day; the resolver lifts the bedtime
 * onto {@code targetDay + 1} so the window is a single continuous interval.
 *
 * <p><b>Modes.</b> Full-day: {@code lowerBound = frontierStart} (wake). Replan-from-now:
 * {@code lowerBound = clamp(now, frontierStart, frontierEnd)} so the past is never re-planned and a
 * late run still yields a (possibly empty) forward window.
 */
public class PlanningWindowResolver {

    /**
     * Resolves the concrete planning window for a target day.
     *
     * @param sleepWindow the frontier as times of day; never null
     * @param targetDay   the calendar day being planned; never null
     * @param zone        the user's timezone; never null
     * @param now         the reference instant (for replan mode); never null
     * @param fromNow     true for replan-from-now, false for a full-day run
     * @return the concrete window with its resolved frontier and lower bound
     */
    public PlanningWindow resolve(SleepWindow sleepWindow, LocalDate targetDay, ZoneId zone,
                                  OffsetDateTime now, boolean fromNow) {
        OffsetDateTime frontierStart = at(targetDay, sleepWindow.wakeEstimate(), zone);
        LocalDate bedtimeDay = crossesMidnight(sleepWindow) ? targetDay.plusDays(1) : targetDay;
        OffsetDateTime frontierEnd = at(bedtimeDay, sleepWindow.bedtimeEstimate(), zone);

        OffsetDateTime lowerBound = fromNow ? clamp(now, frontierStart, frontierEnd) : frontierStart;
        return new PlanningWindow(frontierStart, frontierEnd, lowerBound);
    }

    private static boolean crossesMidnight(SleepWindow window) {
        return window.bedtimeEstimate().minutesOfDay() <= window.wakeEstimate().minutesOfDay();
    }

    private static OffsetDateTime at(LocalDate day, LocalTimeOfDay timeOfDay, ZoneId zone) {
        ZonedDateTime zoned = day.atStartOfDay(zone).plusMinutes(timeOfDay.minutesOfDay());
        return zoned.toOffsetDateTime();
    }

    private static OffsetDateTime clamp(OffsetDateTime value, OffsetDateTime min, OffsetDateTime max) {
        if (value.isBefore(min)) {
            return min;
        }
        if (value.isAfter(max)) {
            return max;
        }
        return value;
    }
}
