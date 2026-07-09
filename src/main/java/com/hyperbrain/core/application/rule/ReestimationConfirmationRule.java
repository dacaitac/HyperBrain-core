package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DR-06 confirmation half (ADR-013 D3): after a focus switch flagged a task
 * {@code pending_reestimation}, an effort value arriving from a human source (Notion today;
 * Appsmith/TD-05 when it exists) <b>is</b> the re-estimation confirmation and clears the flag.
 *
 * <p>The cut now preserves the last known effort instead of emptying it, so the propagators
 * keep the satellites in sync with those preserved values. The machine echo of that write is
 * discarded by checksum before it ever reaches this chain (NotionEventPropagator CA-7), so this
 * rule only fires on a genuine human edit that changes the value — the confirmation semantics
 * hold without relying on the effort having been emptied. A no-op for tasks not awaiting
 * re-estimation (conditional update, no read).
 */
@Component
public class ReestimationConfirmationRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(ReestimationConfirmationRule.class);

    private final ExecutableStateRepository stateRepo;

    public ReestimationConfirmationRule(ExecutableStateRepository stateRepo) {
        this.stateRepo = stateRepo;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (isHumanSource(origin) && carriesEffort(merged)
            && stateRepo.clearPendingReestimation(merged.id())) {
            log.info("Executable {} re-estimation confirmed from {}", merged.id(), origin);
        }
        return merged;
    }

    private static boolean isHumanSource(ExternalSystem origin) {
        return origin == ExternalSystem.APPLE || origin == ExternalSystem.NOTION;
    }

    private static boolean carriesEffort(ExecutableSnapshot s) {
        return s.effortScore() != null || s.energyDrain() != null
            || s.mentalLoad() != null || s.impact() != null;
    }
}
