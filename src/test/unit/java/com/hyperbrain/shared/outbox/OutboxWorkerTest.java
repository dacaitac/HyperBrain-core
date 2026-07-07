package com.hyperbrain.shared.outbox;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.shared.messaging.IEventPropagator;
import com.hyperbrain.shared.messaging.IEventPublisher;
import com.hyperbrain.shared.messaging.SyncedEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxWorker — propagator framework (CA-10/13/14/23)")
class OutboxWorkerTest {

    @Mock private OutboxRepository repository;
    @Mock private IEventPublisher publisher;

    private final OutboxProperties properties = new OutboxProperties();
    private SimpleAsyncTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SimpleAsyncTaskExecutor("test-propagation-");
        executor.setVirtualThreads(true);
    }

    @Test
    @DisplayName("CA-10: framework loop protection excludes the propagator whose target equals the origin")
    void origin_equal_to_target_is_excluded_before_shouldPropagate() {
        // Given an APPLE-origin event and one propagator per satellite
        OutboxEvent event = event("CORE_EXECUTABLE", "APPLE");
        when(repository.lockUnprocessedBatch(properties.getBatchSize())).thenReturn(List.of(event));
        RecordingPropagator apple = new RecordingPropagator(ExternalSystem.APPLE);
        RecordingPropagator notion = new RecordingPropagator(ExternalSystem.NOTION);

        // When
        worker(apple, notion).drainBatch();

        // Then Apple was never consulted; Notion propagated
        assertThat(apple.shouldPropagateCalls).isEmpty();
        assertThat(apple.propagated).isEmpty();
        assertThat(notion.propagated).containsExactly(event);
        verify(repository).markProcessed(event.id());
    }

    @Test
    @DisplayName("CA-13/CA-14: eligible propagators run concurrently on virtual threads")
    void propagators_run_on_virtual_threads() {
        // Given a SYSTEM-origin event eligible for both propagators
        OutboxEvent event = event("CORE_EXECUTABLE", "SYSTEM");
        when(repository.lockUnprocessedBatch(properties.getBatchSize())).thenReturn(List.of(event));
        RecordingPropagator apple = new RecordingPropagator(ExternalSystem.APPLE);
        RecordingPropagator notion = new RecordingPropagator(ExternalSystem.NOTION);

        // When
        worker(apple, notion).drainBatch();

        // Then both ran, each on a virtual thread
        assertThat(apple.propagated).containsExactly(event);
        assertThat(notion.propagated).containsExactly(event);
        assertThat(apple.ranOnVirtualThread).isTrue();
        assertThat(notion.ranOnVirtualThread).isTrue();
        verify(repository).markProcessed(event.id());
    }

    @Test
    @DisplayName("CA-23: a failing propagator does not cancel its sibling; the event stays unprocessed for retry")
    void failing_propagator_does_not_cancel_sibling() {
        // Given Apple fails while Notion succeeds
        OutboxEvent event = event("CORE_EXECUTABLE", "SYSTEM");
        when(repository.lockUnprocessedBatch(properties.getBatchSize())).thenReturn(List.of(event));
        RecordingPropagator apple = new RecordingPropagator(ExternalSystem.APPLE);
        apple.failing = true;
        RecordingPropagator notion = new RecordingPropagator(ExternalSystem.NOTION);

        // When
        int published = worker(apple, notion).drainBatch();

        // Then Notion completed anyway and the event was left unprocessed
        assertThat(published).isZero();
        assertThat(notion.propagated).containsExactly(event);
        verify(repository, never()).markProcessed(event.id());
    }

    @Test
    @DisplayName("shouldPropagate filters by entity classification (cycles never reach Apple)")
    void should_propagate_filters_by_entity_type() {
        // Given a cycle event: Apple rejects CYCLE, Notion accepts it
        OutboxEvent event = event("CORE_CYCLE", "SYSTEM");
        when(repository.lockUnprocessedBatch(properties.getBatchSize())).thenReturn(List.of(event));
        RecordingPropagator apple = new RecordingPropagator(ExternalSystem.APPLE);
        apple.acceptedEntityType = SyncedEntityType.EXECUTABLE;
        RecordingPropagator notion = new RecordingPropagator(ExternalSystem.NOTION);

        // When
        worker(apple, notion).drainBatch();

        // Then
        assertThat(apple.propagated).isEmpty();
        assertThat(notion.propagated).containsExactly(event);
        assertThat(apple.shouldPropagateCalls)
            .containsExactly(SyncedEntityType.CYCLE);
    }

    private OutboxWorker worker(IEventPropagator... propagators) {
        return new OutboxWorker(repository, publisher, List.of(propagators), properties, executor);
    }

    private static OutboxEvent event(String aggregateType, String sourceSystem) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType,
            UUID.randomUUID().toString(), "ExecutableUpdatedEvent", "{}",
            sourceSystem, OffsetDateTime.now());
    }

    /** Minimal propagator double recording invocations and the thread kind it ran on. */
    private static final class RecordingPropagator implements IEventPropagator {

        private final ExternalSystem target;
        private SyncedEntityType acceptedEntityType;
        private boolean failing;
        private final List<SyncedEntityType> shouldPropagateCalls = new CopyOnWriteArrayList<>();
        private final List<OutboxEvent> propagated = new CopyOnWriteArrayList<>();
        private volatile boolean ranOnVirtualThread;

        private RecordingPropagator(ExternalSystem target) {
            this.target = target;
        }

        @Override
        public ExternalSystem target() {
            return target;
        }

        @Override
        public boolean shouldPropagate(ExternalSystem origin, SyncedEntityType entityType) {
            shouldPropagateCalls.add(entityType);
            return acceptedEntityType == null || acceptedEntityType == entityType;
        }

        @Override
        public void propagate(OutboxEvent event) {
            ranOnVirtualThread = Thread.currentThread().isVirtual();
            propagated.add(event);
            if (failing) {
                throw new IllegalStateException("propagation failed");
            }
        }
    }
}
