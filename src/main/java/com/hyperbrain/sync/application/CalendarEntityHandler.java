package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handler for {@link EntityType#CALENDAR} (EKCalendar of type .event).
 *
 * <p>CALENDAR entities represent iCloud calendar containers; they do not map to
 * {@code core_executable} in MVP. The handler logs the event and discards it.
 */
@Component
public class CalendarEntityHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarEntityHandler.class);

    @Override
    public EntityType supportedType() {
        return EntityType.CALENDAR;
    }

    @Override
    public void handle(SentinelEvent event) {
        log.info("CALENDAR {} ({}) received — no persistence in MVP",
            event.entityId(), event.operation());
    }
}
