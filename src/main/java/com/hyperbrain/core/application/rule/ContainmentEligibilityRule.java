package com.hyperbrain.core.application.rule;

import com.hyperbrain.core.domain.model.ContainmentPolicy;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DR-11 — containment eligibility, on the ingestion path: only the reminder-backed types
 * ({@code TASK}, {@code HABIT}, {@code LEAD_MEASURE}, {@code BUYING}) may be members of a
 * {@code TIME_BLOCK}. A merged state that arrives with a {@code container_block_id} on any other
 * type gets it cleared before it is persisted.
 *
 * <p>The rule itself lives in {@link ContainmentPolicy#containable} — a pure policy with two callers
 * (ADR-040 D11): this chain link, and the containment operation core publishes for the Planner. This
 * class is the ingestion-path caller and nothing else; it holds no criterion of its own.
 *
 * <p>Placed <b>before</b> {@link ContainmentCopyRule} in the chain (source-agnostic, covering both
 * the Notion and Apple ingestion paths at one point): clearing the ineligible containment here
 * stops the hard-copy rule from re-stamping the block's date/cycle onto a type that owns its own
 * schedule. The type keeps its own calendar projection untouched — this rule only nulls the
 * containment link, never the window.
 */
@Component
public class ContainmentEligibilityRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(ContainmentEligibilityRule.class);

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.containerBlockId() == null || ContainmentPolicy.containable(merged.type())) {
            return merged;
        }
        log.info("Executable {} of type {} cannot be contained in a block (it is a calendar event); "
            + "containment cleared", merged.id(), merged.type());
        return new ExecutableSnapshot(
            merged.id(), merged.userId(), merged.parentId(), merged.cycleId(),
            merged.name(), merged.description(), merged.type(), merged.status(),
            merged.priorityScore(), merged.urgencyScore(), merged.effortScore(),
            merged.isImportant(), merged.frequency(),
            merged.startTime(), merged.endTime(), merged.sourceCalendar(),
            merged.energyDrain(), merged.mentalLoad(), merged.impact(),
            merged.systemGenerated(), null);
    }
}
