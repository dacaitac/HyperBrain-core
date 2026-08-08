package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.DeviceSleepSamples;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Out-port for the raw archive of the HealthKit dumps a {@code REPLAN_AGENDA} carries: the stage
 * samples exactly as they arrived, filed under the night they are about, before anything interprets
 * them.
 *
 * <p><b>Why this exists.</b> The scored row keeps only collapsed totals, so when the way sleep is read
 * changes — and it has — the nights already recorded cannot be re-derived. That is not hypothetical:
 * six production rows were written under a stage-precedence rule that inflated deep sleep, and by the
 * time it was replaced there was nothing left to recompute them from. Raw-first is the principle
 * (ADR-016) and this is the seam that honours it for the replan bridge.
 *
 * <p><b>One row per night, always the latest.</b> The dump is re-sent on every replan of the day and
 * the watch revises its staging between sends, so the archive is keyed on the sleep day and each
 * arrival overwrites the previous version in place (Daniel, 2026-08-08 — «teniendo en cuenta que se
 * puede reescribir»). What is stored is therefore the most recent reading of that night, never a pile
 * of near-duplicates.
 *
 * <p><b>A night at a time, not a send at a time.</b> One command may carry weeks of history, and what
 * is filed under a night's key is that night's <em>own</em> samples — never the whole send under the
 * key of one of its nights. Re-deriving a single night when the reading of sleep changes again is the
 * point, and it only works if the bytes under a key are that night's and nothing else.
 */
public interface SleepDumpArchive {

    /**
     * The {@code context_event.event_type} every archived dump carries.
     *
     * <p>Deliberately <b>not</b> the canonical {@code SLEEP_SESSION} of the telemetry pipeline, whose
     * payload is one already-aggregated session rather than a list of raw stage intervals: sharing the
     * type would let a future re-normalizer hand one shape to the strategy written for the other.
     *
     * <p>It is published on the port rather than hidden in the adapter because the retention sweep has
     * to recognise these rows to spare them — see {@link RawTelemetryStore#purgeProcessedOlderThan}.
     * Single-sourcing it here means renaming the type can never silently un-exempt the archive.
     */
    String EVENT_TYPE = "SLEEP_STAGE_DUMP";

    /**
     * Files one night's samples under that night, replacing whatever was filed there before, and
     * returns the archived row's identity so the typed record can point back at it.
     *
     * <p>Called <b>before</b> the samples are interpreted: an archive that only kept the nights that
     * parsed would be missing exactly the ones worth looking at.
     *
     * @param samples    the night's own stage samples, as they arrived; never null
     * @param userId     the owning user; never null
     * @param sleepDay   the local day those samples belong to — the archive key; never null
     * @param occurredAt when the dump was captured or the command fired; never null
     * @return the archived raw row's id
     */
    UUID archive(UUID userId, DeviceSleepSamples samples, LocalDate sleepDay,
                 OffsetDateTime occurredAt);

    /**
     * Marks an archived dump as the one the stored sleep record was derived from.
     *
     * @param archivedDumpId the id returned by {@link #archive}; never null
     */
    void markInterpreted(UUID archivedDumpId);

    /**
     * Marks an archived dump as one nothing could be derived from — a payload that did not parse or
     * that the plausibility guards refused. Kept rather than discarded: this is the row that explains
     * why a night has no score, and the retention sweep spares it.
     *
     * @param archivedDumpId the id returned by {@link #archive}; never null
     */
    void markUnusable(UUID archivedDumpId);
}
