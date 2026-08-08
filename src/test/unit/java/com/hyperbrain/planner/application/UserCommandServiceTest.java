package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.DeviceSleepRecord;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.ParsedSleepDay;
import com.hyperbrain.planner.domain.model.RefusedSleepDay;
import com.hyperbrain.planner.domain.model.SleepScoreInput;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.model.UserCommandType;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.port.out.SleepDumpArchive;
import com.hyperbrain.planner.domain.port.out.SleepRecordAssembler;
import com.hyperbrain.planner.domain.port.out.SleepScoreStore;
import com.hyperbrain.planner.domain.service.SleepSampleSessionParser;
import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("UserCommandService")
class UserCommandServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final long STALENESS_HOURS = 2;
    /** Pinned "now" so the staleness guard is deterministic. */
    private static final Instant NOW = Instant.parse("2026-07-11T03:00:00Z");
    private static final OffsetDateTime COLLECTED_AT =
        OffsetDateTime.of(2026, 7, 11, 6, 30, 0, 0, ZoneOffset.UTC);
    /** The raw dump's archived row — what the typed record points back at. */
    private static final UUID RAW_DUMP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final LocalDate SLEEP_DAY = LocalDate.of(2026, 7, 11);

    private ProcessedMessageStore processedMessageStore;
    private AgendaGenerationService agendaGenerationService;
    private AgendaJobEmitter agendaJobEmitter;
    private PlannerStateRepository plannerStateRepository;
    private SleepScoreStore sleepScoreStore;
    private SleepRecordAssembler sleepRecordAssembler;
    private SleepSampleSessionParser sleepSampleSessionParser;
    private SleepDumpArchive sleepDumpArchive;
    private NapActivityRecorder napActivityRecorder;
    private UserCommandService service;

    @BeforeEach
    void setUp() {
        processedMessageStore = mock(ProcessedMessageStore.class);
        agendaGenerationService = mock(AgendaGenerationService.class);
        agendaJobEmitter = mock(AgendaJobEmitter.class);
        plannerStateRepository = mock(PlannerStateRepository.class);
        sleepScoreStore = mock(SleepScoreStore.class);
        sleepRecordAssembler = mock(SleepRecordAssembler.class);
        sleepSampleSessionParser = mock(SleepSampleSessionParser.class);
        sleepDumpArchive = mock(SleepDumpArchive.class);
        napActivityRecorder = mock(NapActivityRecorder.class);
        service = newService(false);
    }

    private UserCommandService newService(boolean asyncMaterializationEnabled) {
        return new UserCommandService(
            processedMessageStore, agendaGenerationService, agendaJobEmitter, plannerStateRepository,
            sleepScoreStore, sleepRecordAssembler, sleepSampleSessionParser, sleepDumpArchive,
            napActivityRecorder,
            Clock.fixed(NOW, ZoneOffset.UTC), STALENESS_HOURS, asyncMaterializationEnabled);
    }

    /**
     * Stubs the anchor's day and the raw-first archive of the single night these dumps hold: the
     * night's own samples are filed under its own key before anything reads them.
     */
    private void givenTheDumpIsArchived(DeviceSleepSamples dump, OffsetDateTime occurredAt) {
        when(sleepSampleSessionParser.sleepDayOf(dump, BOGOTA, occurredAt)).thenReturn(SLEEP_DAY);
        when(sleepDumpArchive.archive(USER_ID, dump, SLEEP_DAY, occurredAt)).thenReturn(RAW_DUMP_ID);
    }

    /** The single-night reading the parser hands back for {@link #sleepDump()}. */
    private static ParsedSleepDay theNight(DeviceSleepSamples dump, AggregatedSleep sleep,
                                           OffsetDateTime collectedAt) {
        return new ParsedSleepDay(SLEEP_DAY, dump, sleep, collectedAt);
    }

    private static DeviceSleepSamples sleepDump() {
        return new DeviceSleepSamples("11/07/2026 at 6:35 AM", List.of(
            new DeviceSleepSamples.Sample("Core", "10/07/2026 at 10:00 PM", "11/07/2026 at 6:30 AM")));
    }

    /** What the parser hands back: the sleep day's sessions already summed into one aggregate. */
    private static AggregatedSleep parsedSleep() {
        return AggregatedSleep.ofSingleSession(new SleepStageSample(
            OffsetDateTime.of(2026, 7, 10, 22, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 7, 11, 6, 30, 0, 0, ZoneOffset.UTC),
            0, 17280, 5184, 6336, 0, 600));
    }

    private static DeviceSleepRecord assembledRecord(AggregatedSleep sleep, OffsetDateTime collectedAt) {
        return new DeviceSleepRecord(
            sleep.night().start(), sleep.night().end(), 480, 100,
            "{\"low_confidence\":false}", collectedAt, null);
    }

    // ── one night of a multi-night dump, everything about it derived from its own sleep day ──────

    /** That night's own slice of the dump — what the archive must file under that night's key. */
    private static DeviceSleepSamples samplesOf(LocalDate sleepDay) {
        return new DeviceSleepSamples("11/07/2026 at 6:35 AM", List.of(new DeviceSleepSamples.Sample(
            "Core", sleepDay.minusDays(1) + " at 10:00 PM", sleepDay + " at 6:30 AM")));
    }

    /** That night's sleep: 22:00 → 06:30 of its own sleep day, so no two nights compare equal. */
    private static AggregatedSleep sleepOf(LocalDate sleepDay) {
        return AggregatedSleep.ofSingleSession(new SleepStageSample(
            sleepDay.minusDays(1).atTime(22, 0).atOffset(ZoneOffset.UTC),
            sleepDay.atTime(6, 30).atOffset(ZoneOffset.UTC),
            0, 17280, 5184, 6336, 0, 600));
    }

    private static ParsedSleepDay night(LocalDate sleepDay) {
        return new ParsedSleepDay(sleepDay, samplesOf(sleepDay), sleepOf(sleepDay), COLLECTED_AT);
    }

    private static DeviceSleepRecord recordOf(LocalDate sleepDay) {
        return assembledRecord(sleepOf(sleepDay), COLLECTED_AT);
    }

    /** The archived row that night's bytes landed on, stable per day so the marks can be verified. */
    private static UUID archiveIdOf(LocalDate sleepDay) {
        return UUID.nameUUIDFromBytes(sleepDay.toString().getBytes(StandardCharsets.UTF_8));
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
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, null));

        // Then the whole-window replan runs in-process; the async emitter and sleep path are untouched
        verify(agendaGenerationService).replanAcrossWindow(USER_ID, occurredAt, BOGOTA);
        verifyNoInteractions(agendaJobEmitter, sleepScoreStore, sleepRecordAssembler,
            sleepSampleSessionParser, sleepDumpArchive);
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
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, null));

        // Then a replan job is enqueued (start day = July 10 in Bogota) and nothing materializes here
        verify(agendaJobEmitter).emitReplanJob(USER_ID, LocalDate.of(2026, 7, 10), BOGOTA, occurredAt);
        verifyNoInteractions(agendaGenerationService, sleepScoreStore, sleepRecordAssembler,
            sleepSampleSessionParser);
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
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, null));

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
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, null));

        // Then nothing replans, no state is loaded — but the command stays marked processed
        verifyNoInteractions(agendaGenerationService, agendaJobEmitter, plannerStateRepository,
            sleepScoreStore, sleepRecordAssembler, sleepSampleSessionParser, sleepDumpArchive);
        verify(processedMessageStore).markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA");
    }

    @Test
    @DisplayName("a live REPLAN_AGENDA carrying a sleep dump parses it, records the sleep, then replans")
    void replan_with_sleep_records_device_record_then_replans() {
        // Given a live replan enriched with a raw HealthKit dump
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        AggregatedSleep sleep = parsedSleep();
        DeviceSleepRecord record = assembledRecord(sleep, COLLECTED_AT);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        givenTheDumpIsArchived(dump, occurredAt);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt))
            .thenReturn(List.of(theNight(dump, sleep, COLLECTED_AT)));
        when(sleepRecordAssembler.assemble(sleep, COLLECTED_AT, RAW_DUMP_ID)).thenReturn(record);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then the dump is parsed, the device record (no raw origin) written, the day's naps recorded
        // as activities, and only then the replan runs
        InOrder inOrder = inOrder(sleepSampleSessionParser, sleepRecordAssembler, sleepScoreStore,
            napActivityRecorder, agendaGenerationService);
        inOrder.verify(sleepSampleSessionParser).readSleepDays(dump, BOGOTA, occurredAt);
        inOrder.verify(sleepRecordAssembler).assemble(sleep, COLLECTED_AT, RAW_DUMP_ID);
        inOrder.verify(sleepScoreStore).upsertDeviceSleepRecord(USER_ID, record, BOGOTA);
        inOrder.verify(napActivityRecorder).record(USER_ID, sleep);
        inOrder.verify(agendaGenerationService).replanAcrossWindow(USER_ID, occurredAt, BOGOTA);
        verifyNoInteractions(agendaJobEmitter);
    }

    @Test
    @DisplayName("the dump is anchored on the command's occurred_at, which bounds the sleep period")
    void sleep_parse_is_anchored_on_occurred_at() {
        // The anchor is not a convenience: it is what decides WHICH sessions belong to the day being
        // recorded (and stands in as collected_at when the dump carries no capture date). A service
        // that anchored on its own clock would score a stale dump against the wrong period.
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        AggregatedSleep sleep = parsedSleep();
        DeviceSleepRecord record = assembledRecord(sleep, occurredAt);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        givenTheDumpIsArchived(dump, occurredAt);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt))
            .thenReturn(List.of(theNight(dump, sleep, occurredAt)));
        when(sleepRecordAssembler.assemble(sleep, occurredAt, RAW_DUMP_ID)).thenReturn(record);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then the parse was anchored there, and the instant it resolved is the collection instant
        verify(sleepSampleSessionParser).readSleepDays(dump, BOGOTA, occurredAt);
        verify(sleepRecordAssembler).assemble(sleep, occurredAt, RAW_DUMP_ID);
        verify(sleepScoreStore).upsertDeviceSleepRecord(USER_ID, record, BOGOTA);
    }

    @Test
    @DisplayName("async path: sleep commits before the replan job is emitted so the consumer sees it")
    void replan_with_sleep_records_before_emitting_job_when_async() {
        service = newService(true);
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        AggregatedSleep sleep = parsedSleep();
        DeviceSleepRecord record = assembledRecord(sleep, COLLECTED_AT);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        givenTheDumpIsArchived(dump, occurredAt);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt))
            .thenReturn(List.of(theNight(dump, sleep, COLLECTED_AT)));
        when(sleepRecordAssembler.assemble(sleep, COLLECTED_AT, RAW_DUMP_ID)).thenReturn(record);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then the sleep write happens strictly before the job is enqueued
        InOrder inOrder = inOrder(sleepScoreStore, agendaJobEmitter);
        inOrder.verify(sleepScoreStore).upsertDeviceSleepRecord(USER_ID, record, BOGOTA);
        inOrder.verify(agendaJobEmitter)
            .emitReplanJob(USER_ID, LocalDate.of(2026, 7, 10), BOGOTA, occurredAt);
        verifyNoInteractions(agendaGenerationService);
    }

    @Test
    @DisplayName("a stale replan still records its sleep, but does not replan (the night is real)")
    void stale_replan_with_sleep_records_sleep_without_replanning() {
        // Given a replan 3 h older than the pinned now (bound = 2 h) that still carries a dump
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 0, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        AggregatedSleep sleep = parsedSleep();
        DeviceSleepRecord record = assembledRecord(sleep, COLLECTED_AT);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        givenTheDumpIsArchived(dump, occurredAt);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt))
            .thenReturn(List.of(theNight(dump, sleep, COLLECTED_AT)));
        when(sleepRecordAssembler.assemble(sleep, COLLECTED_AT, RAW_DUMP_ID)).thenReturn(record);

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then the sleep is persisted but neither replan path runs
        verify(sleepScoreStore).upsertDeviceSleepRecord(USER_ID, record, BOGOTA);
        verifyNoInteractions(agendaGenerationService, agendaJobEmitter);
    }

    @Test
    @DisplayName("an unusable sleep dump is skipped without failing the replan")
    void unusable_sleep_is_skipped_and_replan_still_runs() {
        // Given the parser refuses the night (no scorable sleep)
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        givenTheDumpIsArchived(dump, occurredAt);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt)).thenReturn(
            List.of(new RefusedSleepDay(SLEEP_DAY, dump, "no parseable sleep samples in the dump")));

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then no device record is written, no nap is invented from it, yet the replan still runs
        verify(agendaGenerationService).replanAcrossWindow(USER_ID, occurredAt, BOGOTA);
        verifyNoInteractions(sleepScoreStore, sleepRecordAssembler, napActivityRecorder);
        // And the refused dump is NOT thrown away — it is archived and flagged, which is the only
        // reason a night with no score can be explained after the fact.
        verify(sleepDumpArchive).archive(USER_ID, dump, SLEEP_DAY, occurredAt);
        verify(sleepDumpArchive).markUnusable(RAW_DUMP_ID);
        verify(sleepDumpArchive, never()).markInterpreted(RAW_DUMP_ID);
    }

    @Test
    @DisplayName("a dump holding several nights writes each one on its own — archive, score and mark")
    void every_night_of_the_dump_is_archived_and_recorded_on_its_own() {
        // Given a backfill: two nights of history plus the night the run is standing on
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        when(sleepSampleSessionParser.sleepDayOf(dump, BOGOTA, occurredAt)).thenReturn(SLEEP_DAY);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt)).thenReturn(List.of(
            night(SLEEP_DAY.minusDays(2)), night(SLEEP_DAY.minusDays(1)), night(SLEEP_DAY)));
        for (LocalDate day : List.of(SLEEP_DAY.minusDays(2), SLEEP_DAY.minusDays(1), SLEEP_DAY)) {
            when(sleepDumpArchive.archive(USER_ID, samplesOf(day), day, occurredAt))
                .thenReturn(archiveIdOf(day));
            when(sleepRecordAssembler.assemble(sleepOf(day), COLLECTED_AT, archiveIdOf(day)))
                .thenReturn(recordOf(day));
        }

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then each night is filed under its OWN key with its OWN samples — never the whole dump under
        // one night's key, which is what made the fortnight unrecoverable in the first place.
        for (LocalDate day : List.of(SLEEP_DAY.minusDays(2), SLEEP_DAY.minusDays(1), SLEEP_DAY)) {
            verify(sleepDumpArchive).archive(USER_ID, samplesOf(day), day, occurredAt);
            verify(sleepScoreStore).upsertDeviceSleepRecord(USER_ID, recordOf(day), BOGOTA);
            verify(sleepDumpArchive).markInterpreted(archiveIdOf(day));
        }
        // And ONLY the anchor's night turns its naps into activities: a backfill of three months would
        // otherwise fill the past with «Siesta» executables, each with its calendar event and its
        // Notion page. History leaves a score row and its raw archive, nothing else.
        verify(napActivityRecorder).record(USER_ID, sleepOf(SLEEP_DAY));
        verifyNoMoreInteractions(napActivityRecorder);
    }

    @Test
    @DisplayName("one implausible night is archived, flagged and skipped — the others are still written")
    void a_refused_night_does_not_cost_the_rest_of_the_dump() {
        // The failure this replaces: a refusal was an exception, and the exception aborted the whole
        // dump. On a fortnight that meant fifteen nights lost to one bad reading.
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 0, 0, 0, ZoneOffset.UTC);
        DeviceSleepSamples dump = sleepDump();
        LocalDate refusedDay = SLEEP_DAY.minusDays(1);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(true);
        when(plannerStateRepository.loadUserZone(USER_ID)).thenReturn(BOGOTA);
        when(sleepSampleSessionParser.sleepDayOf(dump, BOGOTA, occurredAt)).thenReturn(SLEEP_DAY);
        when(sleepSampleSessionParser.readSleepDays(dump, BOGOTA, occurredAt)).thenReturn(List.of(
            new RefusedSleepDay(refusedDay, samplesOf(refusedDay), "implausible sleep dump: corrupt"),
            night(SLEEP_DAY)));
        when(sleepDumpArchive.archive(USER_ID, samplesOf(refusedDay), refusedDay, occurredAt))
            .thenReturn(archiveIdOf(refusedDay));
        when(sleepDumpArchive.archive(USER_ID, samplesOf(SLEEP_DAY), SLEEP_DAY, occurredAt))
            .thenReturn(archiveIdOf(SLEEP_DAY));
        when(sleepRecordAssembler.assemble(sleepOf(SLEEP_DAY), COLLECTED_AT, archiveIdOf(SLEEP_DAY)))
            .thenReturn(recordOf(SLEEP_DAY));

        // When
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, dump));

        // Then the refused night is still filed — flagged, never scored — and the good night is written
        verify(sleepDumpArchive).archive(USER_ID, samplesOf(refusedDay), refusedDay, occurredAt);
        verify(sleepDumpArchive).markUnusable(archiveIdOf(refusedDay));
        verify(sleepRecordAssembler, never())
            .assemble(sleepOf(refusedDay), COLLECTED_AT, archiveIdOf(refusedDay));
        verify(sleepScoreStore).upsertDeviceSleepRecord(USER_ID, recordOf(SLEEP_DAY), BOGOTA);
        verify(sleepDumpArchive).markInterpreted(archiveIdOf(SLEEP_DAY));
        verify(agendaGenerationService).replanAcrossWindow(USER_ID, occurredAt, BOGOTA);
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
            COMMAND_ID, UserCommandType.SLEEP_SCORE, occurredAt, new SleepScoreInput(85, date), null));

        // Then
        verify(sleepScoreStore).upsertDailyScore(USER_ID, date, 85, occurredAt, BOGOTA);
        verifyNoInteractions(agendaGenerationService, sleepRecordAssembler, sleepSampleSessionParser,
            sleepDumpArchive);
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
            COMMAND_ID, UserCommandType.SLEEP_SCORE, occurredAt, new SleepScoreInput(90, date), null));

        // Then the store was consulted once and nothing else ran
        verify(sleepScoreStore).upsertDailyScore(USER_ID, date, 90, occurredAt, BOGOTA);
        verifyNoInteractions(agendaGenerationService, sleepRecordAssembler, sleepSampleSessionParser,
            sleepDumpArchive);
    }

    @Test
    @DisplayName("a duplicate command_id is skipped without side effects")
    void duplicate_command_is_skipped() {
        // Given the dedup store has already seen this command
        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 7, 11, 2, 30, 0, 0, ZoneOffset.UTC);
        when(processedMessageStore.markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA"))
            .thenReturn(false);

        // When a redelivery carrying a sleep dump arrives after the command was already processed
        service.handle(USER_ID, new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, occurredAt, null, sleepDump()));

        // Then nothing downstream runs — the sleep is not re-parsed nor re-written on redelivery
        verifyNoInteractions(agendaGenerationService, agendaJobEmitter, plannerStateRepository,
            sleepScoreStore, sleepRecordAssembler, sleepSampleSessionParser, sleepDumpArchive);
        verify(processedMessageStore).markProcessed("user-command:" + COMMAND_ID, "REPLAN_AGENDA");
        verifyNoMoreInteractions(processedMessageStore);
    }
}
