package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler for {@link EntityType#REMINDER_LIST} (EKCalendar of type .reminder).
 *
 * <p>REMINDER_LIST entities represent iCloud list containers; they do not map to
 * {@code core_executable} in MVP. The handler logs the event and discards it.
 * A future iteration may persist list metadata if the product requires it.
 */
@Component
public class ReminderListEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReminderListEventHandler.class);

    @Override
    public EntityType supportedType() {
        return EntityType.REMINDER_LIST;
    }

    @Override
    public void handle(SentinelEvent event) {
        log.info("REMINDER_LIST {} ({}) received — no persistence in MVP",
            event.entityId(), event.operation());
    }
}
