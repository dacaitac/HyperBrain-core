package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DR-06 confirmation half (ADR-013 D3): after a focus switch emptied a task's effort values,
 * any non-null effort value arriving from a human source (Notion today; Appsmith/TD-05 when it
 * exists) <b>is</b> the re-estimation confirmation — the mirrors were emptied, so a fresh value
 * can only come from a person. Clears {@code pending_reestimation}; a no-op for tasks not
 * awaiting re-estimation (conditional update, no read).
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
