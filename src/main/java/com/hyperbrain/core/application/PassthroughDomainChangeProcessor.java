package com.hyperbrain.core.application;

import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.springframework.stereotype.Component;

/**
 * MVP implementation of {@link DomainChangeProcessor}: the task domain engines do not exist
 * yet, so the merged state passes through unchanged (ADR-012 D2). The Prioritizer (HU-01)
 * replaces this with the Priority Score computation without touching the sync pipeline.
 */
@Component
public class PassthroughDomainChangeProcessor implements DomainChangeProcessor {

    @Override
    public ExecutableSnapshot process(ExecutableSnapshot merged, ExternalSystem origin) {
        return merged;
    }
}
