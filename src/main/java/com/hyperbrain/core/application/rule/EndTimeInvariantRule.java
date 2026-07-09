package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DR-01: only the event-backed executables (ACTIVITY, AGENDA, LEARNING_SESSION) represent fixed
 * time blocks with a meaningful end boundary. For all other types (the reminder-backed ones and
 * any unsynced type) the end_time is cleared to prevent invalid date ranges reaching downstream
 * systems (e.g. Notion 400 when end &lt; start) and to keep a reminder's due date at its start.
 */
@Component
public class EndTimeInvariantRule implements DomainRule {

    /** Executable types whose end_time is a real time-block boundary and must be preserved. */
    private static final Set<String> EVENT_TYPES = Set.of("ACTIVITY", "AGENDA", "LEARNING_SESSION");

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        if (merged.endTime() == null || EVENT_TYPES.contains(merged.type())) {
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
