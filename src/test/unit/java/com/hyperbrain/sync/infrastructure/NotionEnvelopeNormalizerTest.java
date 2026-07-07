package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionEnvelopeNormalizer — NotionWebhookEnvelope → SentinelEvent (CA-1/CA-2)")
class NotionEnvelopeNormalizerTest {

    private static final String TASKS_DS = "1bf8bc9c5d918171b7ea000b7e326082";
    private static final String TASKS_DB = "1bf8bc9c5d91812b8c97e5e6450858aa";
    private static final String CYCLES_DS = "1bf8bc9c5d9181e78737000b45812f45";
    private static final String CYCLES_DB = "1bf8bc9c5d9181d882cfe1f4aa38f295";
    private static final String PAGE_ID = "2fa8bc9c-5d91-81ba-b3c9-f2a27fa48cc9";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotionEnvelopeNormalizer normalizer;

    @BeforeEach
    void setUp() {
        NotionSyncProperties properties = new NotionSyncProperties();
        properties.setTasksDataSourceId(TASKS_DS);
        properties.setTasksDatabaseId(TASKS_DB);
        properties.setCyclesDataSourceId(CYCLES_DS);
        properties.setCyclesDatabaseId(CYCLES_DB);
        normalizer = new NotionEnvelopeNormalizer(properties);
    }

    @Test
    @DisplayName("subscription delivery (thin): resolves TASK from data.parent and normalizes the page id")
    void normalizes_subscription_delivery() throws Exception {
        // Given the thin shape Notion subscriptions send (no properties)
        String envelope = """
            {
              "source_system": "NOTION",
              "message_id": "delivery-1",
              "delivery_channel": "subscription",
              "timestamp": "2026-07-07T15:00:00Z",
              "payload": {
                "id": "delivery-1",
                "type": "page.properties_updated",
                "entity": { "id": "%s", "type": "page" },
                "data": { "parent": { "id": "%s", "type": "data_source" } }
              }
            }
            """.formatted(PAGE_ID, TASKS_DS);

        // When
        Optional<SentinelEvent> event = normalizer.normalize(objectMapper.readTree(envelope));

        // Then
        assertThat(event).isPresent();
        assertThat(event.get().entityType()).isEqualTo(EntityType.TASK);
        assertThat(event.get().entityId()).isEqualTo("2fa8bc9c5d9181bab3c9f2a27fa48cc9");
        assertThat(event.get().eventId()).isEqualTo("delivery-1");
        assertThat(event.get().sourceSystem()).isEqualTo("NOTION");
        assertThat(event.get().operation()).isEqualTo(Operation.UPDATED);
        assertThat(event.get().occurredAt()).isEqualTo(OffsetDateTime.parse("2026-07-07T15:00:00Z"));
    }

    @Test
    @DisplayName("automation delivery (embedded page): resolves CYCLE from data.parent.database_id")
    void normalizes_automation_delivery() throws Exception {
        // Given the automation shape (full page under data, parent as database_id)
        String envelope = """
            {
              "source_system": "NOTION",
              "message_id": "delivery-2",
              "delivery_channel": "automation",
              "timestamp": "2026-07-07T15:00:00Z",
              "payload": {
                "source": { "type": "automation" },
                "data": {
                  "object": "page",
                  "id": "%s",
                  "parent": { "type": "database_id", "database_id": "%s" },
                  "properties": {}
                }
              }
            }
            """.formatted(PAGE_ID, CYCLES_DB);

        // When
        Optional<SentinelEvent> event = normalizer.normalize(objectMapper.readTree(envelope));

        // Then
        assertThat(event).isPresent();
        assertThat(event.get().entityType()).isEqualTo(EntityType.CYCLE);
        assertThat(event.get().entityId()).isEqualTo("2fa8bc9c5d9181bab3c9f2a27fa48cc9");
        // The payload travels untouched so the handler can reuse the embedded page
        assertThat(event.get().payload()).contains("\"object\":\"page\"");
    }

    @Test
    @DisplayName("deliveries of unmapped databases are discarded with a log (CA-1)")
    void discards_unmapped_database() throws Exception {
        String envelope = """
            {
              "source_system": "NOTION",
              "message_id": "delivery-3",
              "payload": {
                "entity": { "id": "%s", "type": "page" },
                "data": { "parent": { "id": "ffffffffffffffffffffffffffffffff", "type": "data_source" } }
              }
            }
            """.formatted(PAGE_ID);

        assertThat(normalizer.normalize(objectMapper.readTree(envelope))).isEmpty();
    }

    @Test
    @DisplayName("non-page entities (data source schema events) are discarded")
    void discards_non_page_entities() throws Exception {
        String envelope = """
            {
              "source_system": "NOTION",
              "message_id": "delivery-4",
              "payload": {
                "type": "data_source.schema_updated",
                "entity": { "id": "%s", "type": "data_source" },
                "data": { "parent": { "id": "%s", "type": "database" } }
              }
            }
            """.formatted(TASKS_DS, TASKS_DB);

        assertThat(normalizer.normalize(objectMapper.readTree(envelope))).isEmpty();
    }

    @Test
    @DisplayName("an envelope without message_id is discarded")
    void discards_missing_message_id() throws Exception {
        String envelope = """
            {"source_system":"NOTION","payload":{"entity":{"id":"%s","type":"page"}}}
            """.formatted(PAGE_ID);

        assertThat(normalizer.normalize(objectMapper.readTree(envelope))).isEmpty();
    }
}
