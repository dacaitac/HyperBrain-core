package com.hyperbrain.core.application;

import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.stereotype.Component;

/**
 * MVP implementation of {@link DomainChangeProcessor}: the task domain engines do not exist
 * yet, so the merged state is returned after enforcing structural domain invariants
 * (ADR-012 D2). The Prioritizer (HU-01) replaces this with the Priority Score computation
 * without touching the sync pipeline.
 */
@Component
public class PassthroughDomainChangeProcessor implements DomainChangeProcessor {

    @Override
    public ExecutableSnapshot process(ExecutableSnapshot merged, ExternalSystem origin) {
        return enforceEndTimeInvariant(merged);
    }

    /**
     * Only ACTIVITY and AGENDA executables represent fixed time blocks with a meaningful end
     * boundary. For all other types the end_time is cleared to prevent invalid date ranges
     * reaching downstream systems (e.g. Notion 400 when end < start).
     */
    private static ExecutableSnapshot enforceEndTimeInvariant(ExecutableSnapshot s) {
        if (s.endTime() == null || "ACTIVITY".equals(s.type()) || "AGENDA".equals(s.type())) {
            return s;
        }
        return new ExecutableSnapshot(
            s.id(), s.userId(), s.parentId(), s.cycleId(),
            s.name(), s.description(), s.type(), s.status(),
            s.priorityScore(), s.urgencyScore(), s.effortScore(),
            s.isImportant(), s.frequency(),
            s.startTime(), null, s.sourceCalendar(),
            s.energyDrain(), s.mentalLoad(), s.impact());
    }
}
