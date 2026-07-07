package com.hyperbrain.sync.application;

import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import com.hyperbrain.sync.domain.model.EntityType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SentinelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventIngestionService — dedup, loop protection and routing")
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
    @DisplayName("routes a first-time event after recording the dedup row (CA-3)")
    void routes_first_time_event() {
        // Given
        SentinelEvent event = notionTaskEvent();
        when(processedMessageStore.markProcessed(MESSAGE_ID, "TASK")).thenReturn(true);

        // When
        service.ingest(event);

        // Then
        verify(processedMessageStore).markProcessed(MESSAGE_ID, "TASK");
        verify(eventRouter).route(event);
    }

    @Test
    @DisplayName("ignores a redelivered event without routing it twice (CA-19)")
    void ignores_duplicate_event() {
        // Given
        when(processedMessageStore.markProcessed(MESSAGE_ID, "TASK")).thenReturn(false);

        // When
        service.ingest(notionTaskEvent());

        // Then
        verify(processedMessageStore).markProcessed(MESSAGE_ID, "TASK");
        verify(eventRouter, never()).route(any());
    }

    @Test
    @DisplayName("drops a self-originated event before dedup and routing (RF-17)")
    void drops_self_originated_event() {
        // Given
        SentinelEvent event = new SentinelEvent("1", MESSAGE_ID, "HYPERBRAIN_CORE",
            EntityType.TASK, "page1", Operation.UPDATED, OffsetDateTime.now(), "{}");

        // When
        service.ingest(event);

        // Then
        verifyNoInteractions(processedMessageStore, eventRouter);
    }

    private static SentinelEvent notionTaskEvent() {
        return new SentinelEvent("1", MESSAGE_ID, "NOTION", EntityType.TASK,
            "1bf8bc9c5d9181de8888000000000001", Operation.UPDATED, OffsetDateTime.now(), "{}");
    }
}
