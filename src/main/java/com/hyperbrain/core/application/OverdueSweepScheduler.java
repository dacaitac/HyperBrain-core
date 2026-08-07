package com.hyperbrain.core.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Fires the ADR-040 D4 day-close sweep once per local day, in the small hours: late enough that the
 * day that just closed is really over, early enough that the bag is clean before the morning agenda
 * is generated. Thin by design — {@link OverdueSweepService} decides everything, so tests drive the
 * sweep deterministically without the clock.
 *
 * <p><b>The reference day is today.</b> The sweep opens day D and closes day D−1: everything whose
 * window ended before D expired, and a task that expired is re-dated ONTO D — literally "the next
 * day" relative to the day that just closed, and exactly what the admission rule needs to see it as
 * a candidate again.
 *
 * <p><b>Off by default, on purpose.</b> Unlike its sibling schedulers this bean does not exist
 * unless {@code app.core.overdue-sweep.enabled=true}: the sweep mutates real rows and pushes those
 * changes out to Apple and to Notion, so it ships inert and Daniel turns it on when he has looked at
 * what it would touch. The switch is its own — the sweep is a domain concern, not a planner one, and
 * it must be able to run while the planner is still dark (in fact it has to run <em>before</em> the
 * window model lands).
 */
@Component
@ConditionalOnProperty(prefix = "app.core.overdue-sweep", name = "enabled", havingValue = "true")
public class OverdueSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueSweepScheduler.class);

    private final OverdueSweepService sweepService;
    private final UUID defaultUserId;
    private final ZoneId zone;

    public OverdueSweepScheduler(
        OverdueSweepService sweepService,
        @Value("${app.sync.default-user-id}") UUID defaultUserId,
        @Value("${app.core.overdue-sweep.zone:America/Bogota}") String zoneId
    ) {
        this.sweepService = sweepService;
        this.defaultUserId = defaultUserId;
        this.zone = ZoneId.of(zoneId);
        log.info("Overdue sweep enabled; reference day resolved in {}", this.zone);
    }

    @Scheduled(
        cron = "${app.core.overdue-sweep.cron:0 0 3 * * *}",
        zone = "${app.core.overdue-sweep.zone:America/Bogota}")
    public void sweepPreviousDay() {
        sweepService.sweep(defaultUserId, LocalDate.now(zone), zone);
    }
}
