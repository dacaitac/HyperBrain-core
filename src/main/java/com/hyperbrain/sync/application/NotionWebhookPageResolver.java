package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.EventProcessingException;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the raw page object for one normalized Notion event (HU-14): automation
 * deliveries embed the full page under {@code data} and are used as-is; subscription
 * deliveries are thin, so the current page state is fetched from the Notion API — which also
 * makes every burst delivery converge to the latest state (CA-28).
 *
 * <p>An empty result means the page is no longer accessible ({@code 404}): the caller must
 * process it as DELETED. A transient API failure propagates as {@link EventProcessingException}
 * so SQS redelivers and eventually parks the message in the DLQ (CA-22).
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionWebhookPageResolver {

    private static final Logger log = LoggerFactory.getLogger(NotionWebhookPageResolver.class);

    private final NotionPort notion;
    private final ObjectMapper objectMapper;

    public NotionWebhookPageResolver(NotionPort notion, ObjectMapper objectMapper) {
        this.notion = notion;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the page object for the event, or empty when the page no longer exists.
     *
     * @param event normalized Notion event whose payload is the raw webhook body
     * @return the raw page JSON, or empty (treat as DELETED)
     */
    public Optional<JsonNode> resolve(SentinelEvent event) {
        JsonNode webhook = parse(event.payload());
        JsonNode embedded = webhook.path("data");
        if ("page".equals(embedded.path("object").asText(null)) && embedded.has("properties")) {
            return Optional.of(embedded);
        }
        try {
            return Optional.of(parse(notion.retrievePage(event.entityId())));
        } catch (NotionPageNotFoundException ex) {
            log.info("Notion page {} is gone (404 on fetch); processing event {} as DELETED",
                event.entityId(), event.eventId());
            return Optional.empty();
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Malformed Notion payload", ex);
        }
    }
}
