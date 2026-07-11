package com.hyperbrain.planner.domain.port.out;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Out-port for recording a manual sleep score (HU-01b slice 2) as at most one
 * {@code tel_sleep_record} row per user and local day.
 *
 * <p><b>Frontier vs. energy invariant.</b> A manual score carries no real bedtime/wake instants,
 * so the row it produces must feed the energy resolution ({@code sleep_score} + fresh
 * {@code collected_at}) while staying invisible to the sleep-frontier median. The adapter encodes
 * this with the {@code end_time IS NULL} marker: frontier sample queries require a non-null
 * {@code end_time}, so a marker row can never inject synthetic hours into the wake/bedtime
 * statistics.
 *
 * <p><b>Device precedence (Daniel, 2026-07-11).</b> A record with real hours owns its day: once
 * the day's governing record carries {@code end_time} <em>and</em> a {@code sleep_score}, a manual
 * score is discarded rather than overwriting it.
 */
public interface SleepScoreStore {

    /**
     * Upserts the day's sleep score for a user: when a record for that local day already exists
     * (a real telemetry record whose wake falls on the day, or a previous manual marker), its
     * {@code sleep_score} and {@code collected_at} are updated in place; otherwise a score-only
     * marker row is inserted ({@code end_time = NULL}, {@code start_time} anchored to the day's
     * local midnight solely to satisfy the NOT NULL constraint).
     *
     * @param userId      the owning user; never null
     * @param date        the local calendar day the score refers to; never null
     * @param score       the sleep score in {@code [0, 100]}
     * @param collectedAt the instant the score was reported; recorded as {@code collected_at}
     * @param zone        the user's timezone, used to bound the local day; never null
     * @return {@code true} when the score was written (updated or inserted); {@code false} when
     *         the day is owned by a complete device record (hours + score) and the manual score
     *         was discarded — the caller logs the discard
     */
    boolean upsertDailyScore(UUID userId, LocalDate date, int score, OffsetDateTime collectedAt,
                             ZoneId zone);
}
