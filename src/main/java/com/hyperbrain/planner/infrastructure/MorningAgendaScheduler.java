package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.application.AgendaDeliveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Fires the morning agenda dispatch (HU-01b delivery slice). The trigger minute is dynamic — the
 * observed wake edge plus a lead offset, clamped by ±hysteresis — so a fixed cron cannot express it;
 * instead a short-cadence cron (matching the dispatch tolerance) polls {@link AgendaDeliveryService},
 * which decides whether the minute has arrived and guards against a second fire the same day.
 *
 * <p>Modeled on {@code PriorityTickScheduler}: thin, switched off in tests via
 * {@code app.planner.delivery.scheduling-enabled=false}, and anchored to {@code America/Bogota} so
 * the wake-derived trigger is reasoned about in the user's local day.
 */
@Component
@ConditionalOnProperty(prefix = "app.planner.delivery", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class MorningAgendaScheduler {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private final AgendaDeliveryService deliveryService;
    private final UUID defaultUserId;

    public MorningAgendaScheduler(
        AgendaDeliveryService deliveryService,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.deliveryService = deliveryService;
        this.defaultUserId = defaultUserId;
    }

    @Scheduled(cron = "${app.planner.delivery.poll-cron:0 */5 * * * *}", zone = "America/Bogota")
    public void poll() {
        deliveryService.dispatchIfDue(defaultUserId, ZONE, OffsetDateTime.now());
    }
}
