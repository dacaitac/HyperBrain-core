package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing of Time Blocks webhook deliveries (ADR-038 condition 8): classified as
 * {@link EntityType#TIME_BLOCK} only while the inbound kill-switch is on; discarded at the
 * door (acknowledged, never DLQ'd) while it is off.
 */
@DisplayName("NotionEnvelopeNormalizer — Time Blocks routing behind the inbound kill-switch (ADR-038)")
class NotionEnvelopeNormalizerTimeBlockTest {

    private static final String BLOCKS_DS = "af6834d49fe242d5a04dcf4b5f146b5c";
    private static final String BLOCKS_DB = "0b48d06e677344c0a615a6502bddea54";
    private static final String PAGE_ID = "0b48d06e-6773-44c0-a615-a6502bddea99";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotionSyncProperties properties;

    @BeforeEach
    void setUp() {
        properties = new NotionSyncProperties();
        properties.setTasksDataSourceId("1bf8bc9c5d918171b7ea000b7e326082");
        properties.setCyclesDataSourceId("1bf8bc9c5d9181e78737000b45812f45");
        properties.setTimeblocksDataSourceId(BLOCKS_DS);
        properties.setTimeblocksDatabaseId(BLOCKS_DB);
    }

    @Test
    @DisplayName("kill-switch ON: a Time Blocks subscription delivery routes as TIME_BLOCK")
    void routes_time_block_when_inbound_enabled() throws Exception {
        properties.setTimeblocksInboundEnabled(true);

        Optional<SentinelEvent> event = new NotionEnvelopeNormalizer(properties)
            .normalize(objectMapper.readTree(envelope(BLOCKS_DS)));

        assertThat(event).isPresent();
        assertThat(event.get().entityType()).isEqualTo(EntityType.TIME_BLOCK);
        assertThat(event.get().entityId()).isEqualTo("0b48d06e677344c0a615a6502bddea99");
    }

    @Test
    @DisplayName("kill-switch OFF (default): the delivery is discarded — acknowledged, no event, no DLQ")
    void discards_time_block_when_inbound_disabled() throws Exception {
        Optional<SentinelEvent> event = new NotionEnvelopeNormalizer(properties)
            .normalize(objectMapper.readTree(envelope(BLOCKS_DS)));

        assertThat(event).isEmpty();
    }

    @Test
    @DisplayName("the database id (automation parent shape) matches like the data source id")
    void matches_database_id_parent() throws Exception {
        properties.setTimeblocksInboundEnabled(true);

        Optional<SentinelEvent> event = new NotionEnvelopeNormalizer(properties)
            .normalize(objectMapper.readTree(envelope(BLOCKS_DB)));

        assertThat(event).isPresent();
        assertThat(event.get().entityType()).isEqualTo(EntityType.TIME_BLOCK);
    }

    private static String envelope(String parentId) {
        return """
            {
              "source_system": "NOTION",
              "message_id": "delivery-tb-1",
              "delivery_channel": "subscription",
              "timestamp": "2026-08-05T15:00:00Z",
              "payload": {
                "id": "delivery-tb-1",
                "type": "page.properties_updated",
                "entity": { "id": "%s", "type": "page" },
                "data": { "parent": { "id": "%s", "type": "data_source" } }
              }
            }
            """.formatted(PAGE_ID, parentId);
    }
}
