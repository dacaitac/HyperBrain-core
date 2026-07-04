package com.hyperbrain.sync.application;

import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.in.IEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches a {@link SentinelEvent} to the {@link IEventHandler} registered for its
 * {@link EntityType}. Handlers are discovered by Spring and indexed once at construction.
 *
 * <p>An entity type without a handler is logged and skipped rather than failing: new producer
 * entity types can roll out before the Core grows a handler for them, without poisoning the queue.
 */
@Service
public class EventRouter {

    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    private final Map<EntityType, IEventHandler> handlers;

    public EventRouter(List<IEventHandler> handlers) {
        this.handlers = new EnumMap<>(EntityType.class);
        for (IEventHandler handler : handlers) {
            this.handlers.put(handler.supportedType(), handler);
        }
    }

    /**
     * Routes an event to its handler.
     *
     * @param event the event to dispatch
     */
    public void route(SentinelEvent event) {
        IEventHandler handler = handlers.get(event.entityType());
        if (handler == null) {
            log.warn("No handler registered for entity type {}; event {} skipped",
                event.entityType(), event.eventId());
            return;
        }
        handler.handle(event);
    }
}
