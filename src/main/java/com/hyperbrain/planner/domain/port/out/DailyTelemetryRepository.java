package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.DailyBlockObservation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Read-only out-port feeding the H0 adherence rollup (#17): the day's planner blocks (with their
 * settled execution signal and WIG flag) and the count of replan commands issued that day. Kept
 * separate from {@code PlannerStateRepository} (ISP) — the rollup only reads telemetry, it never
 * materializes agenda state.
 */
public interface DailyTelemetryRepository {

    /**
     * Loads the planner-origin blocks placed for the user on the given local day, each carrying its
     * WIG flag and settled actual duration.
     *
     * @param userId the owning user; never null
     * @param day    the local day to project the blocks onto; never null
     * @param zone   the user's timezone the day boundaries are computed in; never null
     * @return the day's block observations (empty when nothing was planned)
     */
    List<DailyBlockObservation> loadPlannerBlockObservations(UUID userId, LocalDate day, ZoneId zone);

    /**
     * Counts the {@code REPLAN_AGENDA} commands processed on the given local day. Single-user MVP:
     * the {@code processed_message} dedup log is not user-scoped, so the count is global.
     *
     * @param day  the local day to count replans on; never null
     * @param zone the user's timezone the day boundaries are computed in; never null
     * @return the number of replans issued that day; &ge; 0
     */
    int countReplans(LocalDate day, ZoneId zone);
}
