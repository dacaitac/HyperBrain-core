package com.hyperbrain.planner.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyperbrain.planner.domain.model.AgendaBlockPlannedEvent;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.sync.domain.model.CalendarEventPayload;
import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.SyncMappingRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import com.hyperbrain.sync.infrastructure.WriteCommandWireMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-028 reinforcement (core#33): the morning agenda write-back must target exclusively
 * {@code AgendaBlockPropagator}'s own writable calendar ({@code "HyperBrain"}) — never a
 * read-only AGENDA one (ADR-009).
 */
@DisplayName("AgendaBlockPropagator — write-back targets only the \"HyperBrain\" calendar (ADR-028)")
class AgendaBlockPropagatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLOCK_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BLOCK_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EXECUTABLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 7, 25, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = START.plusHours(1);

    private PlannerStateRepository plannerStateRepository;
    private SyncMappingRepository syncMappingRepo;
    private WriteCommandLogRepository commandLogRepo;
    private WriteCommandPublisher commandPublisher;
    private AgendaBlockPropagator propagator;

    @BeforeEach
    void setUp() {
        plannerStateRepository = mock(PlannerStateRepository.class);
        syncMappingRepo = mock(SyncMappingRepository.class);
        commandLogRepo = mock(WriteCommandLogRepository.class);
        commandPublisher = mock(WriteCommandPublisher.class);
        EmptyAgendaNotifier emptyAgendaNotifier = mock(EmptyAgendaNotifier.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WriteCommandWireMapper wireMapper = new WriteCommandWireMapper(objectMapper);
        propagator = new AgendaBlockPropagator(plannerStateRepository, syncMappingRepo, commandLogRepo,
            commandPublisher, wireMapper, emptyAgendaNotifier, objectMapper);
    }

    @Test
    @DisplayName("a new (unmapped) block is delivered as a CALENDAR_EVENT to calendar \"HyperBrain\"")
    void unmapped_block_targets_hyperbrain_calendar() {
        // Given
        when(plannerStateRepository.loadPlannedBlocksForDay(USER_ID, START.toLocalDate(), ZoneId.of("UTC")))
            .thenReturn(List.of(block(BLOCK_ID_1)));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", BLOCK_ID_1)).thenReturn(Optional.empty());

        // When
        propagator.propagate(event());

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(captor.capture(), anyString());
        WriteCommand command = captor.getValue();
        assertThat(command.commandType()).isEqualTo(CommandType.CALENDAR_EVENT);
        assertThat(command.operation()).isEqualTo(Operation.CREATED);
        CalendarEventPayload payload = (CalendarEventPayload) command.payload();
        assertThat(payload.calendarName()).isEqualTo("HyperBrain");
        assertThat(payload.calendarId()).isEmpty();
    }

    @Test
    @DisplayName("an already-mapped block is updated, still targeting calendar \"HyperBrain\"")
    void mapped_block_targets_hyperbrain_calendar() {
        // Given
        when(plannerStateRepository.loadPlannedBlocksForDay(USER_ID, START.toLocalDate(), ZoneId.of("UTC")))
            .thenReturn(List.of(block(BLOCK_ID_1)));
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", BLOCK_ID_1))
            .thenReturn(Optional.of(mapping(BLOCK_ID_1, "EK-1")));

        // When
        propagator.propagate(event());

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(captor.capture(), anyString());
        WriteCommand command = captor.getValue();
        assertThat(command.operation()).isEqualTo(Operation.UPDATED);
        assertThat(command.entityId()).isEqualTo("EK-1");
        CalendarEventPayload payload = (CalendarEventPayload) command.payload();
        assertThat(payload.calendarName()).isEqualTo("HyperBrain");
    }

    @Test
    @DisplayName("every block of a multi-block day is delivered to calendar \"HyperBrain\" and no other")
    void every_block_targets_only_hyperbrain_calendar() {
        // Given
        when(plannerStateRepository.loadPlannedBlocksForDay(USER_ID, START.toLocalDate(), ZoneId.of("UTC")))
            .thenReturn(List.of(block(BLOCK_ID_1), block(BLOCK_ID_2)));
        when(syncMappingRepo.findByExternalSystemAndLocalId(eq("APPLE"), any())).thenReturn(Optional.empty());

        // When
        propagator.propagate(event());

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher, times(2))
            .publish(captor.capture(), anyString());
        assertThat(captor.getAllValues())
            .extracting(c -> ((CalendarEventPayload) c.payload()).calendarName())
            .containsOnly("HyperBrain");
    }

    @Test
    @DisplayName("a removed block's DELETE carries no calendar payload — nothing is written to any calendar")
    void removed_block_carries_no_calendar_payload() {
        // Given — the block row is already gone; only its Apple mapping remains
        when(plannerStateRepository.loadPlannedBlocksForDay(USER_ID, START.toLocalDate(), ZoneId.of("UTC")))
            .thenReturn(List.of());
        when(syncMappingRepo.findByExternalSystemAndLocalId("APPLE", BLOCK_ID_1))
            .thenReturn(Optional.of(mapping(BLOCK_ID_1, "EK-1")));

        // When
        propagator.propagate(eventWithRemoval(BLOCK_ID_1));

        // Then
        ArgumentCaptor<WriteCommand> captor = ArgumentCaptor.forClass(WriteCommand.class);
        verify(commandPublisher).publish(captor.capture(), anyString());
        WriteCommand command = captor.getValue();
        assertThat(command.operation()).isEqualTo(Operation.DELETED);
        assertThat(command.payload()).isNull();
    }

    private static PlannedBlockRecord block(UUID blockId) {
        return new PlannedBlockRecord(blockId, EXECUTABLE_ID, "Deep work", START, END, "WIG pace");
    }

    private static SyncMapping mapping(UUID localId, String externalId) {
        return new SyncMapping(UUID.randomUUID(), USER_ID, localId, "APPLE", externalId,
            "checksum", "SYNCED", OffsetDateTime.now());
    }

    private static OutboxEvent event() {
        String payload = """
            {"user_id":"%s","target_day":"%s","zone_id":"UTC","energy_criterion":"Sleep Score 74 -> full quota","removed_block_ids":[]}
            """.formatted(USER_ID, START.toLocalDate());
        return new OutboxEvent(UUID.randomUUID(), AgendaBlockPlannedEvent.AGGREGATE_TYPE,
            USER_ID.toString(), AgendaBlockPlannedEvent.EVENT_TYPE, payload, "SYSTEM", OffsetDateTime.now());
    }

    private static OutboxEvent eventWithRemoval(UUID removedBlockId) {
        String payload = """
            {"user_id":"%s","target_day":"%s","zone_id":"UTC","energy_criterion":"Sleep Score 74 -> full quota","removed_block_ids":["%s"]}
            """.formatted(USER_ID, START.toLocalDate(), removedBlockId);
        return new OutboxEvent(UUID.randomUUID(), AgendaBlockPlannedEvent.AGGREGATE_TYPE,
            USER_ID.toString(), AgendaBlockPlannedEvent.EVENT_TYPE, payload, "SYSTEM", OffsetDateTime.now());
    }
}
