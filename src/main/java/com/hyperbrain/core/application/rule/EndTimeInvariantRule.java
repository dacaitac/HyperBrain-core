package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.stereotype.Component;

/**
 * DR-01: only ACTIVITY and AGENDA executables represent fixed time blocks with a meaningful
 * end boundary. For all other types the end_time is cleared to prevent invalid date ranges
 * reaching downstream systems (e.g. Notion 400 when end &lt; start).
 */
@Component
public class EndTimeInvariantRule implements DomainRule {

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.endTime() == null
            || "ACTIVITY".equals(merged.type()) || "AGENDA".equals(merged.type())) {
            return merged;
        }
        return new ExecutableSnapshot(
            merged.id(), merged.userId(), merged.parentId(), merged.cycleId(),
            merged.name(), merged.description(), merged.type(), merged.status(),
            merged.priorityScore(), merged.urgencyScore(), merged.effortScore(),
            merged.isImportant(), merged.frequency(),
            merged.startTime(), null, merged.sourceCalendar(),
            merged.energyDrain(), merged.mentalLoad(), merged.impact());
    }
}
