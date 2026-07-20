package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.stereotype.Component;

/**
 * DR-02 — DONE → IN_PROGRESS on reactivation.
 *
 * When a previously-DONE executable is re-opened (merged status becomes TODO after having been
 * DONE), the system returns it to IN_PROGRESS instead of TODO: work already started does not
 * restart from scratch. The rule fires independently of the originating system (Apple or
 * Notion) so that unchecking from either side converges on the same domain state.
 *
 * Guard: previous == null on CREATE — rule does not apply.
 */
@Component
public class CompletionReactivationRule implements DomainRule {

    private static final String TODO = "TODO";
    private static final String DONE = "DONE";
    private static final String IN_PROGRESS = "IN_PROGRESS";

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (previous == null || !DONE.equals(previous.status()) || !TODO.equals(merged.status())) {
            return merged;
        }
        return new ExecutableSnapshot(
            merged.id(), merged.userId(), merged.parentId(), merged.cycleId(),
            merged.name(), merged.description(), merged.type(), IN_PROGRESS,
            merged.priorityScore(), merged.urgencyScore(), merged.effortScore(),
            merged.isImportant(), merged.frequency(),
            merged.startTime(), merged.endTime(), merged.sourceCalendar(),
            merged.energyDrain(), merged.mentalLoad(), merged.impact(),
            merged.systemGenerated());
    }
}
