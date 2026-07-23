package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.DailyAdherenceReport;

import java.util.UUID;

/**
 * Out-port for the persisted daily adherence projection (ADR-025 D4): the narrow read-model that
 * single-sources the adherence formula of {@code AdherenceCalculator} for the iOS Scoreboard, which
 * consumes it through the {@code api.v_daily_adherence} view. Kept separate from
 * {@link DailyTelemetryRepository} (ISP): that port only reads the rollup inputs, this one only
 * persists the computed result.
 *
 * <p>This is the acotada, additive amendment to the raw-first telemetry of ADR-016: the rollup keeps
 * being logged, and is now also projected here — it is not the full #59 telemetry stack.
 */
public interface DailyRollupRepository {

    /**
     * Upserts a day's rollup for a user, replacing any prior projection of the same
     * {@code (userId, agendaDate)}. Idempotent by design: a rollup replay or a late-settling block
     * refreshes the existing row rather than duplicating or failing on the primary key.
     *
     * @param userId the owning user; never null
     * @param report the computed rollup to persist; its {@code date} is the local agenda day and its
     *               {@code zone} is not stored (only used upstream to derive that day); never null
     */
    void upsert(UUID userId, DailyAdherenceReport report);
}
