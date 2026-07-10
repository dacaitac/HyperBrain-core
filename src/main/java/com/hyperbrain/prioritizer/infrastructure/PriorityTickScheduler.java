package com.hyperbrain.prioritizer.infrastructure;

import com.hyperbrain.prioritizer.application.PriorityReflectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Schedules the periodic priority tick (#66a, tier (a)): recomputes the day's Priority Scores as the
 * urgency ramp advances with wall-clock time and reflects the moved rows to the satellites. Separated
 * from {@link PriorityReflectionService} so tests drive {@code reflectDailyReprioritization} directly;
 * the bean is switched off via {@code app.prioritizer.scheduling-enabled=false} in the
 * integration-test profile, mirroring the {@code OutboxScheduler} / {@code TimeBlockExpiryScheduler}
 * pattern.
 *
 * <p>The cron fires in {@code America/Bogota} so the tick is anchored to the user's local day, where
 * deadlines and the urgency horizon are reasoned about.
 */
@Component
@ConditionalOnProperty(prefix = "app.prioritizer", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class PriorityTickScheduler {

    private final PriorityReflectionService reflectionService;
    private final UUID defaultUserId;

    public PriorityTickScheduler(
        PriorityReflectionService reflectionService,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.reflectionService = reflectionService;
        this.defaultUserId = defaultUserId;
    }

    @Scheduled(cron = "${app.prioritizer.tick-cron:0 0 * * * *}", zone = "America/Bogota")
    public void tick() {
        reflectionService.reflectDailyReprioritization(defaultUserId);
    }
}
