package com.hyperbrain.sync.application;

import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventIngestionService — Notion webhook acknowledgement")
class SyncEventIngestionServiceTest {

    private static final String MESSAGE_ID = "8f1c4c53-notion-delivery";

    @Mock private ProcessedMessageStore processedMessageStore;
    @Mock private EventRouter eventRouter;

    private SyncEventIngestionService service;

    @BeforeEach
    void setUp() {
        service = new SyncEventIngestionService(processedMessageStore, eventRouter);
    }

    @Test
    @DisplayName("acknowledges a first-time Notion envelope: dedup row written, nothing routed")
    void acknowledges_first_time_notion_event() {
        // Given
        when(processedMessageStore.markProcessed(MESSAGE_ID, "NOTION_WEBHOOK")).thenReturn(true);

        // When
        service.acknowledgeNotionEvent(MESSAGE_ID);

        // Then
        verify(processedMessageStore).markProcessed(MESSAGE_ID, "NOTION_WEBHOOK");
        verify(eventRouter, never()).route(any());
    }

    @Test
    @DisplayName("ignores a redelivered Notion envelope without failing or routing")
    void ignores_duplicate_notion_event() {
        // Given
        when(processedMessageStore.markProcessed(MESSAGE_ID, "NOTION_WEBHOOK")).thenReturn(false);

        // When / Then
        assertThatCode(() -> service.acknowledgeNotionEvent(MESSAGE_ID)).doesNotThrowAnyException();
        verify(processedMessageStore).markProcessed(MESSAGE_ID, "NOTION_WEBHOOK");
        verify(eventRouter, never()).route(any());
    }
}
