package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ADR-039 (Daniel, 2026-08-06) — containment eligibility: only the reminder-backed types
 * ({@code TASK}, {@code HABIT}, {@code LEAD_MEASURE}, {@code BUYING}) may be members of a
 * {@code TIME_BLOCK}. The calendar-event types ({@code ACTIVITY}, {@code LEARNING_SESSION}) and
 * the read-only {@code AGENDA} are <b>already</b> time blocks in themselves (they carry their own
 * EKEvent window), so they can never be contained. A merged state that arrives with a
 * {@code container_block_id} on such a type gets it cleared before it is persisted.
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

    /** Types that are calendar events (or read-only agenda) and thus can never be contained. */
    private static final Set<String> NON_CONTAINABLE_TYPES =
        Set.of("ACTIVITY", "LEARNING_SESSION", "AGENDA");

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.containerBlockId() == null || !NON_CONTAINABLE_TYPES.contains(merged.type())) {
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
