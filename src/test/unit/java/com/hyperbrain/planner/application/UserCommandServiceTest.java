package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.SleepScoreInput;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.model.UserCommandType;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserCommandService")
class UserCommandServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final long STALENESS_HOURS = 2;
    /** Pinned "now" so the staleness guard is deterministic. */
    private static final Instant NOW = Instant.parse("2026-07-11T03:00:00Z");

    private ProcessedMessageStore processedMessageStore;
    private AgendaGenerationService agendaGenerationService;
    private AgendaJobEmitter agendaJobEmitter;
    private PlannerStateRepository plannerStateRepository;
    private SleepScoreStore sleepScoreStore;
    private UserCommandService service;

    @BeforeEach
    void setUp() {
        processedMessageStore = mock(ProcessedMessageStore.class);
        agendaGenerationService = mock(AgendaGenerationService.class);
        agendaJobEmitter = mock(AgendaJobEmitter.class);
        plannerStateRepository = mock(PlannerStateRepository.class);
        sleepScoreStore = mock(SleepScoreStore.class);
        service = newService(false);
    }

    private UserCommandService newService(boolean asyncMaterializationEnabled) {
        return new UserCommandService(
            processedMessageStore, agendaGenerationService, agendaJobEmitter, plannerStateRepository,
            sleepScoreStore, Clock.fixed(NOW, ZoneOffset.UTC), STALENESS_HOURS,
            asyncMaterializationEnabled);
    }

    @Test
    @DisplayName("sync path (flag off): a live REPLAN_AGENDA delegates the 48 h window to the generator")
    void replan_delegates_to_window_when_sync() {
        // occurredAt within the staleness bound (1 h old vs pinned now)
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null));

        // Then the whole-window replan runs in-process; the async emitter is never touched
        verify(agendaGenerationService).replanAcrossWindow(USER_ID, occurredAt, BOGOTA);
        verifyNoInteractions(agendaJobEmitter, sleepScoreStore);
    }

    @Test
    @DisplayName("async path (flag on): a live REPLAN_AGENDA enqueues a job and never generates in-process")
    void replan_emits_job_when_async() {
        service = newService(true);
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null));

        // Then a replan job is enqueued (start day = July 10 in Bogota) and nothing materializes here
        verify(agendaJobEmitter).emitReplanJob(USER_ID, LocalDate.of(2026, 7, 10), BOGOTA, occurredAt);
        verifyNoInteractions(agendaGenerationService, sleepScoreStore);
    }

    @Test
    @DisplayName("a replan exactly at the staleness bound still fires (guard is strictly older-than)")
    void replan_at_the_bound_still_fires() {
        // Given a replan exactly 2 h old — edge of the staleness window, must still fire
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 1, 0, 0, 0, ZoneOffset.UTC);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null));

        // Then the replan ran — the guard did not block it
        verify(agendaGenerationService).replanAcrossWindow(USER_ID, occurredAt, BOGOTA);
    }

    @Test
    @DisplayName("a stale replan (occurred_at older than the bound) is discarded without replanning")
    void stale_replan_is_discarded() {
        // Given a replan 3 h older than the pinned now (bound = 2 h)
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 0, 0, 0, 0, ZoneOffset.UTC);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null));

        // Then nothing replans — but the command stays marked processed (dedup ran first)
        verifyNoInteractions(
            agendaGenerationService, agendaJobEmitter, plannerStateRepository, sleepScoreStore);
        verify(processedMessageStore).markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA");
    }

    @Test
    @DisplayName("SLEEP_SCORE upserts the day's score with occurred_at as collected_at")
    void sleep_score_upserts_daily_score() {
        // Given
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 10, 8, 30, 0, 0, ZoneOffset.UTC);
        LocalDate date = LocalDate.of(2026, 7, 10);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "SLEEP_SCORE"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        when(sleepScoreStore.upsertDailyScore(USER_ID, date, 85, occurredAt, BOGOTA))
            .thenReturn(true);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.SLEEP_SCORE, occurredAt, new SleepScoreInput(85, date)));

        // Then
        verify(sleepScoreStore).upsertDailyScore(USER_ID, date, 85, occurredAt, BOGOTA);
        verifyNoInteractions(agendaGenerationService);
    }

    @Test
    @DisplayName("SLEEP_SCORE on a device-owned day is discarded by the store and only logged")
    void sleep_score_discarded_when_day_is_device_owned() {
        // Given the store reports the day is owned by a complete device record
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 10, 8, 30, 0, 0, ZoneOffset.UTC);
        LocalDate date = LocalDate.of(2026, 7, 10);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "SLEEP_SCORE"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        when(sleepScoreStore.upsertDailyScore(USER_ID, date, 90, occurredAt, BOGOTA))
            .thenReturn(false);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.SLEEP_SCORE, occurredAt, new SleepScoreInput(90, date)));

        // Then the store was consulted once and nothing else ran
        verify(sleepScoreStore).upsertDailyScore(USER_ID, date, 90, occurredAt, BOGOTA);
        verifyNoInteractions(agendaGenerationService);
    }

    @Test
    @DisplayName("a duplicate command_id is skipped without side effects")
    void duplicate_command_is_skipped() {
        // Given the dedup store has already seen this command
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 30, 0, 0, ZoneOffset.UTC);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(false);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null));

        // Then nothing downstream runs
        verifyNoInteractions(
            agendaGenerationService, agendaJobEmitter, plannerStateRepository, sleepScoreStore);
    }
}
