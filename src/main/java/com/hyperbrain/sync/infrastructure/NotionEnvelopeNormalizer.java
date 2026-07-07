package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Normalizes a {@code NotionWebhookEnvelope} (AsyncAPI v1.4.0) into the pipeline's
 * {@link SentinelEvent} (HU-14 CA-1/CA-2): resolves the page id, determines the parent data
 * source from the raw payload and filters Tasks ({@link EntityType#TASK}) and Cycles
 * ({@link EntityType#CYCLE}); deliveries of any other database — or non-page entities such
 * as data source schema events — return empty and are discarded with a log by the caller.
 *
 * <p>Shape tolerance: subscription deliveries are thin ({@code entity} + {@code data.parent});
 * automation deliveries embed the full page under {@code data}. Parent candidates are matched
 * against both the data source ids and the database ids of the configured Tasks/Cycles DBs.
 *
 * <p>The normalized event carries {@code operation=UPDATED} as a nominal upsert marker only:
 * the effective operation is derived by the handler from the {@code sync_mappings} state and
 * the page lifecycle, never from the webhook {@code type} (CA-28).
 */
@Component
public class NotionEnvelopeNormalizer {

    private static final Logger log = LoggerFactory.getLogger(NotionEnvelopeNormalizer.class);

    private static final String SOURCE_SYSTEM = "NOTION";
    private static final String SCHEMA_VERSION = "1";

    private final NotionSyncProperties properties;

    public NotionEnvelopeNormalizer(NotionSyncProperties properties) {
        this.properties = properties;
    }

    /**
     * Normalizes one envelope, or returns empty when the delivery does not concern a page of
     * the Tasks or Cycles databases (logged at INFO — deliberate discard, CA-1).
     *
     * @param envelope the deserialized envelope ({@code source_system=NOTION})
     * @return the normalized event, or empty when the delivery must be discarded
     */
    public Optional<SentinelEvent> normalize(JsonNode envelope) {
        String messageId = envelope.path("message_id").asText(null);
        if (messageId == null || messageId.isBlank()) {
            log.warn("Notion envelope without message_id discarded");
            return Optional.empty();
        }
        JsonNode payload = envelope.path("payload");
        String pageId = pageId(payload);
        if (pageId == null) {
            log.info("Notion delivery {} carries no page entity ({}); discarded",
                messageId, payload.path("type").asText("unknown"));
            return Optional.empty();
        }
        EntityType entityType = resolveEntityType(payload);
        if (entityType == null) {
            log.info("Notion delivery {} for page {} belongs to an unmapped database; discarded",
                messageId, pageId);
            return Optional.empty();
        }
        return Optional.of(new SentinelEvent(
            SCHEMA_VERSION,
            messageId,
            SOURCE_SYSTEM,
            entityType,
            pageId,
            Operation.UPDATED,
            timestamp(envelope),
            payload.toString()));
    }

    /** Page id from the subscription {@code entity} or the automation's embedded page. */
    private static String pageId(JsonNode payload) {
        JsonNode entity = payload.path("entity");
        if (entity.isObject()) {
            if (!"page".equals(entity.path("type").asText(null))) {
                return null;
            }
            return NotionPageParser.normalizeId(entity.path("id").asText(null));
        }
        JsonNode data = payload.path("data");
        if ("page".equals(data.path("object").asText(null))) {
            return NotionPageParser.normalizeId(data.path("id").asText(null));
        }
        return null;
    }

    private EntityType resolveEntityType(JsonNode payload) {
        Set<String> candidates = parentCandidates(payload);
        String tasksDataSource = NotionPageParser.normalizeId(properties.getTasksDataSourceId());
        String tasksDatabase = NotionPageParser.normalizeId(properties.getTasksDatabaseId());
        if (candidates.contains(tasksDataSource) || candidates.contains(tasksDatabase)) {
            return EntityType.TASK;
        }
        String cyclesDataSource = NotionPageParser.normalizeId(properties.getCyclesDataSourceId());
        String cyclesDatabase = NotionPageParser.normalizeId(properties.getCyclesDatabaseId());
        if (candidates.contains(cyclesDataSource) || candidates.contains(cyclesDatabase)) {
            return EntityType.CYCLE;
        }
        return null;
    }

    /** Collects every plausible parent id the two delivery shapes may carry. */
    private static Set<String> parentCandidates(JsonNode payload) {
        Set<String> candidates = new LinkedHashSet<>();
        addParentIds(payload.path("data").path("parent"), candidates);
        addParentIds(payload.path("data").path("page").path("parent"), candidates);
        return candidates;
    }

    private static void addParentIds(JsonNode parent, Set<String> candidates) {
        if (!parent.isObject()) {
            return;
        }
        for (String field : new String[] {"id", "data_source_id", "database_id"}) {
            String value = parent.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                candidates.add(NotionPageParser.normalizeId(value));
            }
        }
    }

    private static OffsetDateTime timestamp(JsonNode envelope) {
        String raw = envelope.path("timestamp").asText(null);
        if (raw == null) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ex) {
            return OffsetDateTime.now();
        }
    }
}
