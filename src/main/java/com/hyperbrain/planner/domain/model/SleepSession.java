package com.hyperbrain.planner.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * One observed stretch of sleep on the clock: when it started, when it ended, and how much of it was
 * actually asleep. A night is a session and so is a nap — the concept makes no distinction, because
 * the day only cares about <em>when</em> the user was asleep.
 *
 * <p>These are real instants, unlike the window of the aggregate {@link AggregatedSleep#totals()}
 * carries: a session is safe to show, to store and to reason about as wall-clock truth.
 *
 * @param start         when the session began; never null
 * @param end           when it ended; never null and after {@code start}
 * @param asleepSeconds seconds actually asleep inside the window (never more than its span)
 */
public record SleepSession(OffsetDateTime start, OffsetDateTime end, long asleepSeconds) {

    public SleepSession {
        if (start == null || end == null) {
            throw new IllegalArgumentException("sleep session requires start and end instants");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("sleep session end must be after start: " + start + " .. " + end);
        }
        if (asleepSeconds < 0) {
            throw new IllegalArgumentException("asleep seconds must be non-negative: " + asleepSeconds);
        }
    }

    /** @return the session's span in seconds (time in bed for this session) */
    public long windowSeconds() {
        return Duration.between(start, end).toSeconds();
    }
}
