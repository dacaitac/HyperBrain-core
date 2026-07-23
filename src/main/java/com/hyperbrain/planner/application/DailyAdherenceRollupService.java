package com.hyperbrain.planner.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DailyAdherenceReport;
import com.hyperbrain.planner.domain.model.DailyBlockObservation;
import com.hyperbrain.planner.domain.port.out.DailyRollupRepository;
import com.hyperbrain.planner.domain.port.out.DailyTelemetryRepository;
import com.hyperbrain.planner.domain.port.out.MorningTriggerStore;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.service.AdherenceCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consolidates the H0 daily rollup (#17): reads a user's local day, computes adherence and the three
 * behavioral lead measures, emits them as a single structured JSON line to stdout, and persists them
 * as a narrow projection in {@code plnr_daily_rollup}. Returns the report so callers/tests can assert
 * on it beyond the side effects.
 *
 * <p><b>Log + projection (ADR-025 D4, amending ADR-016).</b> The structured log stays as the raw-first
 * telemetry sink (ADR-016); the persisted upsert is an acotada, additive amendment that single-sources
 * the adherence formula for the iOS Scoreboard (read through the {@code api.v_daily_adherence} view),
 * avoiding a SQL reimplementation of {@link AdherenceCalculator}. It is not the full #59 telemetry stack.
 *
 * <p><b>Ritual proxy (partial lead measure).</b> ADR-018's morning commitment ritual has no explicit
 * "user committed" signal yet, so {@code ritual_completed} is logged as a proxy: whether the morning
 * agenda dispatch fired for the day ({@code MorningTriggerState.firedOn}). The true user-commitment
 * signal is deferred to Daniel — logged today is what is measurable.
 */
@Service
public class DailyAdherenceRollupService {

    private static final Logger log = LoggerFactory.getLogger(DailyAdherenceRollupService.class);

    private final DailyTelemetryRepository telemetryRepository;
    private final PlannerStateRepository plannerStateRepository;
    private final MorningTriggerStore morningTriggerStore;
    private final DailyRollupRepository dailyRollupRepository;
    private final AdherenceCalculator adherenceCalculator;
    private final ObjectMapper objectMapper;

    public DailyAdherenceRollupService(
        DailyTelemetryRepository telemetryRepository,
        PlannerStateRepository plannerStateRepository,
        MorningTriggerStore morningTriggerStore,
        DailyRollupRepository dailyRollupRepository,
        AdherenceCalculator adherenceCalculator,
        ObjectMapper objectMapper
    ) {
        this.telemetryRepository = telemetryRepository;
        this.plannerStateRepository = plannerStateRepository;
        this.morningTriggerStore = morningTriggerStore;
        this.dailyRollupRepository = dailyRollupRepository;
        this.adherenceCalculator = adherenceCalculator;
        this.objectMapper = objectMapper;
    }

    /**
     * Rolls up one local day for a user: logs the structured result and upserts the projection into
     * {@code plnr_daily_rollup}. Read-write ({@code @Transactional}, not {@code readOnly}): the input
     * reads and the single projection write share one transaction, so a partial rollup never persists.
     *
     * @param userId the user to roll up; never null
     * @param day    the local day to consolidate (typically the previous day); never null
     * @return the computed rollup
     */
    @Transactional
    public DailyAdherenceReport rollup(UUID userId, LocalDate day) {
        ZoneId zone = plannerStateRepository.loadUserZone(userId);
        List<DailyBlockObservation> blocks =
            telemetryRepository.loadPlannerBlockObservations(userId, day, zone);
        int replanCount = telemetryRepository.countReplans(day, zone);
        Boolean ritualCompleted = morningTriggerStore.load(userId).firedOn(day);

        DailyAdherenceReport report =
            adherenceCalculator.compute(day, zone, blocks, replanCount, ritualCompleted);
        logStructured(report);
        dailyRollupRepository.upsert(userId, report);
        return report;
    }

    private void logStructured(DailyAdherenceReport report) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("date", report.date().toString());
        line.put("zone", report.zone().getId());
        line.put("blocks_planned", report.blocksPlanned());
        line.put("blocks_executed", report.blocksExecuted());
        line.put("adherence", report.adherence());
        line.put("wig_hit", report.wigHit());
        line.put("ritual_completed", report.ritualCompleted());
        line.put("replan_count", report.replanCount());
        line.put("abandoned", report.abandoned());
        try {
            log.info("daily_adherence_rollup {}", objectMapper.writeValueAsString(line));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize daily adherence rollup for {}", report.date(), ex);
        }
    }
}
