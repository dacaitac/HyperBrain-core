package com.hyperbrain.core.application;

import com.hyperbrain.core.domain.model.TimeBlockExecutable;
import com.hyperbrain.core.domain.port.out.TimeBlockExecutableRepository;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("TimeBlockExpiryService — a block that elapsed closes as done (ADR-040 D4)")
class TimeBlockExpiryServiceTest {

    private static final UUID BLOCK = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 7, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = START.plusHours(2);
    private static final OffsetDateTime NOW = END.plusMinutes(5);

    private TimeBlockExecutableRepository timeBlockRepo;
    private OutboxRepository outboxRepo;
    private TimeBlockExpiryService service;

    @BeforeEach
    void setUp() {
        timeBlockRepo = mock(TimeBlockExecutableRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        service = new TimeBlockExpiryService(timeBlockRepo, outboxRepo);
    }

    @Test
    @DisplayName("an elapsed block closes as DONE and its mirrors are told")
    void an_elapsed_block_closes_as_done() {
        // Given
        when(timeBlockRepo.lockOpenExpired(NOW)).thenReturn(List.of(block("PLANNER")));
        when(timeBlockRepo.settle(eq(BLOCK), eq(TimeBlockExecutable.STATUS_DONE), isNull(), eq(END)))
            .thenReturn(true);

        // When
        int closed = service.expireDueBlocks(NOW);

        // Then: done, not failed — a block is not a broken commitment, it is a container of time that
        // elapsed. And no executed minutes are frozen: that series died with the focus register.
        assertThat(closed).isEqualTo(1);
        verify(timeBlockRepo).settle(BLOCK, TimeBlockExecutable.STATUS_DONE, null, END);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("ExecutableUpdatedEvent");
        assertThat(event.getValue().aggregateId()).isEqualTo(BLOCK.toString());
    }

    @Test
    @DisplayName("a block another run already closed is skipped, and nothing is announced twice")
    void a_lost_race_announces_nothing() {
        // Given: the conditional update reports it was no longer open.
        when(timeBlockRepo.lockOpenExpired(NOW)).thenReturn(List.of(block("PLANNER")));
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(false);

        // When
        int closed = service.expireDueBlocks(NOW);

        // Then
        assertThat(closed).isZero();
        verifyNoInteractions(outboxRepo);
    }

    @Test
    @DisplayName("a legacy focus-origin row closes silently: it was never mirrored anywhere")
    void a_focus_origin_block_is_not_mirrored() {
        // Given: nothing produces these any more, but production still holds the ones it made.
        when(timeBlockRepo.lockOpenExpired(NOW)).thenReturn(List.of(block("FOCUS")));
        when(timeBlockRepo.settle(any(), any(), any(), any())).thenReturn(true);

        // When
        int closed = service.expireDueBlocks(NOW);

        // Then: announcing one would not update an entity — it would create a calendar event for a
        // block the user never asked for.
        assertThat(closed).isEqualTo(1);
        verifyNoInteractions(outboxRepo);
    }

    private static TimeBlockExecutable block(String origin) {
        return new TimeBlockExecutable(BLOCK, USER, null, START, END,
            TimeBlockExecutable.STATUS_IN_PROGRESS, origin, null, null);
    }
}
