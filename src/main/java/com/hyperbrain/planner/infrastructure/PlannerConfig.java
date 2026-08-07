package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.model.AdherenceThresholds;
import com.hyperbrain.planner.domain.model.DayTemplate;
import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.HumanizationSettings;
import com.hyperbrain.planner.domain.model.PlannerConstraints;
import com.hyperbrain.planner.domain.service.AdherenceCalculator;
import com.hyperbrain.planner.domain.service.AgendaGenerator;
import com.hyperbrain.planner.domain.service.AgendaInputHasher;
import com.hyperbrain.planner.domain.service.AgendaValidator;
import com.hyperbrain.planner.domain.service.ContextBatcher;
import com.hyperbrain.planner.domain.service.DayWindowResolver;
import com.hyperbrain.planner.domain.service.EnergyResolver;
import com.hyperbrain.planner.domain.service.HumanizedAgendaFloor;
import com.hyperbrain.planner.domain.service.LearnedUnitCostCalculator;
import com.hyperbrain.planner.domain.service.MorningTriggerCalculator;
import com.hyperbrain.planner.domain.service.PlanningWindowResolver;
import com.hyperbrain.planner.domain.service.SleepFrontierCalculator;
import com.hyperbrain.planner.domain.service.SleepSampleSessionParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the framework-free planner domain services as Spring beans, keeping the
 * {@code planner.domain} package free of framework annotations (spike #63, #6a). The calibrable
 * {@link PlannerConstraints} and {@link EnergyThresholds} are resolved from
 * {@code sys_user.settings.planner_constants} through {@link PlannerConstantsLoader} (F3 — no formula
 * constant hard-coded in a service), falling back to the sanctioned defaults when settings carry none.
 */
@Configuration
@EnableConfigurationProperties({AgendaDeliveryProperties.class, HumanizationProperties.class})
class PlannerConfig {

    @Bean
    PlannerConstraints plannerConstraints(PlannerConstantsLoader loader) {
        return loader.resolveConstraints();
    }

    @Bean
    EnergyThresholds energyThresholds(PlannerConstantsLoader loader) {
        return loader.resolveThresholds();
    }

    /**
     * The day template (ADR-040 D14), resolved from {@code planner_constants.day_template} with the
     * sanctioned table as the fallback. Resolved once at wiring time like the other calibration seams:
     * for the single-user MVP a settings edit takes effect on the next boot, and the template enters
     * the replan digest through the windows it resolves into (so once loaded, editing it does change
     * the plan).
     */
    @Bean
    DayTemplate dayTemplate(PlannerConstantsLoader loader) {
        return loader.resolveDayTemplate();
    }

    @Bean
    LearnedUnitCostCalculator learnedUnitCostCalculator() {
        return new LearnedUnitCostCalculator();
    }

    @Bean
    SleepFrontierCalculator sleepFrontierCalculator() {
        return new SleepFrontierCalculator();
    }

    /**
     * Parses the iOS Shortcut's raw HealthKit sleep dump into the most-recent scorable night
     * (user-command sleep bridge). Framework-free; uses the sanctioned session-gap default.
     */
    @Bean
    SleepSampleSessionParser sleepSampleSessionParser() {
        return new SleepSampleSessionParser();
    }

    @Bean
    EnergyResolver energyResolver(EnergyThresholds energyThresholds) {
        return new EnergyResolver(energyThresholds);
    }

    @Bean
    PlanningWindowResolver planningWindowResolver() {
        return new PlanningWindowResolver();
    }

    /**
     * The humanized floor's calibration (H1, HU-01c), from {@code app.planner.humanize.*}. Sanctioned
     * MVP defaults (pending Daniel) live in {@code application.yml}; the mapper falls back to
     * {@link HumanizationSettings#DEFAULT} for any absent section.
     */
    @Bean
    HumanizationSettings humanizationSettings(HumanizationProperties properties) {
        return properties.toSettings();
    }

    @Bean
    AgendaGenerator agendaGenerator(PlannerConstraints plannerConstraints) {
        return new AgendaGenerator(plannerConstraints);
    }

    /**
     * Lays the day's windows from the template (ADR-040): the structure that replaces the cursor sweep,
     * and without which the day collapses against its first free hour.
     */
    @Bean
    DayWindowResolver dayWindowResolver(DayTemplate dayTemplate) {
        return new DayWindowResolver(dayTemplate);
    }

    @Bean
    ContextBatcher contextBatcher() {
        return new ContextBatcher();
    }

    /**
     * The deterministic floor: batching plus window filling. Also the DEGRADED fallback the ADR-019
     * propose-then-validate orchestrator invokes — a day laid and ordered, but neither grouped nor
     * named (ADR-040 D6).
     */
    @Bean
    HumanizedAgendaFloor humanizedAgendaFloor(ContextBatcher contextBatcher,
                                              AgendaGenerator agendaGenerator,
                                              HumanizationSettings humanizationSettings) {
        return new HumanizedAgendaFloor(contextBatcher, agendaGenerator, humanizationSettings);
    }

    @Bean
    AgendaValidator agendaValidator() {
        return new AgendaValidator();
    }

    /**
     * The stable digest of a day's generation input (HU-01c H2). Framework-free like the other domain
     * services; the single-owner materialization claims {@code (user, day, hash)} before generating.
     */
    @Bean
    AgendaInputHasher agendaInputHasher() {
        return new AgendaInputHasher();
    }

    /**
     * H0 adherence rollup (#17). The tolerance and abandonment threshold are sanctioned MVP defaults
     * (pending Daniel — domain formula), overridable via {@code app.telemetry.rollup.*}.
     */
    @Bean
    AdherenceCalculator adherenceCalculator(
        @Value("${app.telemetry.rollup.executed-min-minutes:5}") int executedMinMinutes,
        @Value("${app.telemetry.rollup.abandonment-adherence-threshold:0.5}") double abandonmentThreshold
    ) {
        return new AdherenceCalculator(
            new AdherenceThresholds(executedMinMinutes, abandonmentThreshold));
    }

    @Bean
    MorningTriggerCalculator morningTriggerCalculator(AgendaDeliveryProperties properties) {
        return new MorningTriggerCalculator(
            properties.leadOffsetMinutes(), properties.hysteresisMarginMinutes());
    }

    /**
     * Wall clock for time-sensitive guards (e.g. the replan staleness bound in
     * {@code UserCommandService}). A bean — not {@code OffsetDateTime.now()} inline — so tests can
     * pin it with a fixed {@code @Primary} clock and stay deterministic.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
