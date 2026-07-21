package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.application.DailyAdherenceRollupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Fires the H0 daily adherence rollup (#17) over the previous local day. Runs early (before the
 * wake-triggered morning dispatch), so the day is fully settled — the DR-08 expiry sweep has closed
 * yesterday's open blocks — and the ritual proxy ({@code lastFiredDay}) still points at yesterday.
 *
 * <p>Modeled on {@code MorningAgendaScheduler}: thin, anchored to {@code America/Bogota}, and switched
 * off in tests via {@code app.telemetry.rollup.scheduling-enabled=false} so the rollup can be driven
 * deterministically.
 */
@Component
@ConditionalOnProperty(prefix = "app.telemetry.rollup", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class DailyAdherenceRollupScheduler {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final DailyAdherenceRollupService rollupService;
    private final UUID defaultUserId;

    public DailyAdherenceRollupScheduler(
        DailyAdherenceRollupService rollupService,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.rollupService = rollupService;
        this.defaultUserId = defaultUserId;
    }

    @Scheduled(cron = "${app.telemetry.rollup.cron:0 30 3 * * *}", zone = "America/Bogota")
    public void rollupPreviousDay() {
        rollupService.rollup(defaultUserId, LocalDate.now(ZONE).minusDays(1));
    }
}
