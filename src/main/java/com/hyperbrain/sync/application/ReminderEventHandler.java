package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skeleton handler for {@link EntityType#REMINDER}. Verifies the pipeline end to end without any
 * domain logic; the real persistence + checksum + outbox propagation arrives with HU-09.
 */
@Component
public class ReminderEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReminderEventHandler.class);

    @Override
    public EntityType supportedType() {
        return EntityType.REMINDER;
    }

    @Override
    public void handle(SentinelEvent event) {
        log.info("stub: handling REMINDER event {} (operation {})", event.eventId(), event.operation());
    }
}
