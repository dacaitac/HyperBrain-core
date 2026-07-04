package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skeleton handler for {@link EntityType#CALENDAR_EVENT}. No domain logic yet; real handling
 * arrives with HU-09.
 */
@Component
public class CalendarEventHandler implements IEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventHandler.class);

    @Override
    public EntityType supportedType() {
        return EntityType.CALENDAR_EVENT;
    }

    @Override
    public void handle(SentinelEvent event) {
        log.info("stub: handling CALENDAR_EVENT event {} (operation {})", event.eventId(), event.operation());
    }
}
