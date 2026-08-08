package com.hyperbrain.planner.application;

import com.hyperbrain.planner.domain.model.AggregatedSleep;
import com.hyperbrain.planner.domain.model.SleepSession;
import com.hyperbrain.planner.domain.model.SleepStageSample;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.shared.outbox.OutboxEvent;
import com.hyperbrain.shared.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("NapActivityRecorder — the naps of a sleep day become completed activities")
class NapActivityRecorderTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SLEEP_CYCLE_ID = UUID.fromString("e46723d9-5ee5-4d93-99d5-291dce23d205");

    private static final OffsetDateTime NIGHT_START = OffsetDateTime.parse("2026-08-07T23:00:00Z");
    private static final OffsetDateTime NIGHT_END = OffsetDateTime.parse("2026-08-08T05:00:00Z");
    private static final OffsetDateTime NAP_START = OffsetDateTime.parse("2026-08-08T14:00:00Z");
    private static final OffsetDateTime NAP_END = OffsetDateTime.parse("2026-08-08T15:00:00Z");

    private PlannerStateRepository repository;
    private OutboxRepository outboxRepository;
    private NapActivityRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(PlannerStateRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        recorder = new NapActivityRecorder(repository, outboxRepository);
    }

    @Test
    @DisplayName("records the nap as a DONE activity of the sleep cycle, over its real window")
    void records_the_nap_as_a_completed_activity() {
        // Given a sleep day of a night plus an afternoon nap, and a user who has a «Sueño» cycle
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession nap = new SleepSession(NAP_START, NAP_END, 3600);
        when(repository.findCycleIdByName(USER_ID, "Sueño")).thenReturn(Optional.of(SLEEP_CYCLE_ID));
        when(repository.findActivityOverlapping(USER_ID, SLEEP_CYCLE_ID, NAP_START, NAP_END))
            .thenReturn(Optional.empty());

        // When
        recorder.record(USER_ID, sleepOf(night, nap));

        // Then exactly one activity is inserted, under the nap's own hours, and announced to the outbox
        ArgumentCaptor<UUID> activityId = ArgumentCaptor.forClass(UUID.class);
        verify(repository).insertCompletedActivity(activityId.capture(), eq(USER_ID),
            eq(SLEEP_CYCLE_ID), eq("Siesta"), eq(NAP_START), eq(NAP_END));
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("ExecutableCreatedEvent");
        assertThat(event.getValue().aggregateId()).isEqualTo(activityId.getValue().toString());
        // The satellites are reached by the standard propagators, so the change must be SYSTEM-authored
        // or the loop guard would drop it before it ever left the outbox.
        assertThat(event.getValue().sourceSystem()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("a fragment of a broken night is night, not a nap at half past six in the morning")
    void a_night_fragment_is_never_recorded_as_a_nap() {
        // Daniel's split night: 23:00–03:00 and 06:30–08:00. Filing the second stretch as a nap put a
        // «Siesta» event at 06:30 in his calendar and taught the frontier that he woke at 03:00.
        SleepSession firstHalf = new SleepSession(
            OffsetDateTime.parse("2026-08-07T23:00:00Z"),
            OffsetDateTime.parse("2026-08-08T03:00:00Z"), 4 * 3600);
        SleepSession secondHalf = new SleepSession(
            OffsetDateTime.parse("2026-08-08T06:30:00Z"),
            OffsetDateTime.parse("2026-08-08T08:00:00Z"), 90 * 60);

        recorder.record(USER_ID, sleepOf(firstHalf, secondHalf));

        verifyNoInteractions(repository, outboxRepository);
    }

    @Test
    @DisplayName("the night is never recorded — it is the sleep row, not an activity")
    void the_night_is_not_recorded_as_an_activity() {
        // A day of one session is a night on its own: recording it would put a six-hour event across
        // the calendar every morning, on top of the tel_sleep_record row that already holds it.
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);

        recorder.record(USER_ID, sleepOf(night));

        verifyNoInteractions(repository, outboxRepository);
    }

    @Test
    @DisplayName("a session shorter than 15 minutes of sleep is a doze, not a nap, and is not recorded")
    void a_session_below_the_floor_is_not_a_nap() {
        // Daniel's floor. Below it the watch is reporting stillness or the tail of a night it split in
        // two — clutter in the calendar that he would then have to delete by hand.
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession doze = new SleepSession(NAP_START, NAP_START.plusMinutes(20), 14 * 60);

        recorder.record(USER_ID, sleepOf(night, doze));

        verifyNoInteractions(repository, outboxRepository);
    }

    @Test
    @DisplayName("exactly 15 minutes of sleep clears the floor — the bound is inclusive")
    void the_floor_is_inclusive() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        OffsetDateTime end = NAP_START.plusMinutes(15);
        SleepSession nap = new SleepSession(NAP_START, end, 15 * 60);
        when(repository.findCycleIdByName(USER_ID, "Sueño")).thenReturn(Optional.of(SLEEP_CYCLE_ID));
        when(repository.findActivityOverlapping(USER_ID, SLEEP_CYCLE_ID, NAP_START, end))
            .thenReturn(Optional.empty());

        recorder.record(USER_ID, sleepOf(night, nap));

        verify(repository).insertCompletedActivity(any(), eq(USER_ID), eq(SLEEP_CYCLE_ID),
            eq("Siesta"), eq(NAP_START), eq(end));
    }

    @Test
    @DisplayName("two passes of the same dump leave ONE activity: the second re-times the first")
    void the_same_nap_recorded_twice_stays_one_activity() {
        // The whole point. The dump is re-sent on every replan of the day and the watch revises its
        // staging, so the same nap arrives again and again under no identifier at all — a second insert
        // would mean a second activity and a second calendar event for one afternoon.
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession firstPass = new SleepSession(NAP_START, NAP_END, 3600);
        OffsetDateTime revisedEnd = NAP_END.plusMinutes(12);
        SleepSession secondPass = new SleepSession(NAP_START, revisedEnd, 3600 + 720);
        UUID recorded = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(repository.findCycleIdByName(USER_ID, "Sueño")).thenReturn(Optional.of(SLEEP_CYCLE_ID));
        when(repository.findActivityOverlapping(USER_ID, SLEEP_CYCLE_ID, NAP_START, NAP_END))
            .thenReturn(Optional.empty());
        when(repository.findActivityOverlapping(USER_ID, SLEEP_CYCLE_ID, NAP_START, revisedEnd))
            .thenReturn(Optional.of(recorded));
        when(repository.retimeActivityEnd(recorded, revisedEnd)).thenReturn(true);

        // When the same sleep day is recorded twice, the second time with the revised staging
        recorder.record(USER_ID, sleepOf(night, firstPass));
        recorder.record(USER_ID, sleepOf(night, secondPass));

        // Then nothing is inserted the second time — the overlapping row is moved to the revised end
        verify(repository).insertCompletedActivity(any(), any(), any(), any(), any(), any());
        verify(repository).retimeActivityEnd(recorded, revisedEnd);
        verify(outboxRepository).append(argThatIsUpdateOf(recorded));
    }

    @Test
    @DisplayName("a re-observation that moved nothing announces nothing")
    void an_unchanged_re_observation_is_silent() {
        // The satellites re-project a full mirror on every event, so announcing an update that changed
        // nothing is pure churn on Notion and on the calendar.
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession nap = new SleepSession(NAP_START, NAP_END, 3600);
        UUID recorded = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(repository.findCycleIdByName(USER_ID, "Sueño")).thenReturn(Optional.of(SLEEP_CYCLE_ID));
        when(repository.findActivityOverlapping(USER_ID, SLEEP_CYCLE_ID, NAP_START, NAP_END))
            .thenReturn(Optional.of(recorded));
        when(repository.retimeActivityEnd(recorded, NAP_END)).thenReturn(false);

        recorder.record(USER_ID, sleepOf(night, nap));

        verify(repository).retimeActivityEnd(recorded, NAP_END);
        verify(repository, never()).insertCompletedActivity(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("a user with no «Sueño» cycle has his naps skipped — the system does not invent one")
    void a_missing_sleep_cycle_skips_the_naps() {
        // The cycle is the user's own classification of his life. Fabricating one would put a commitment
        // in his Notion that he never created.
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession nap = new SleepSession(NAP_START, NAP_END, 3600);
        when(repository.findCycleIdByName(USER_ID, "Sueño")).thenReturn(Optional.empty());

        recorder.record(USER_ID, sleepOf(night, nap));

        verify(repository).findCycleIdByName(USER_ID, "Sueño");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("every nap of the day is recorded, not only the first")
    void all_the_naps_of_the_day_are_recorded() {
        SleepSession night = new SleepSession(NIGHT_START, NIGHT_END, 6 * 3600);
        SleepSession morningNap = new SleepSession(
            OffsetDateTime.parse("2026-08-08T11:00:00Z"),
            OffsetDateTime.parse("2026-08-08T11:40:00Z"), 40 * 60);
        SleepSession afternoonNap = new SleepSession(NAP_START, NAP_END, 3600);
        when(repository.findCycleIdByName(USER_ID, "Sueño")).thenReturn(Optional.of(SLEEP_CYCLE_ID));
        when(repository.findActivityOverlapping(eq(USER_ID), eq(SLEEP_CYCLE_ID), any(), any()))
            .thenReturn(Optional.empty());

        recorder.record(USER_ID, sleepOf(night, morningNap, afternoonNap));

        verify(repository).insertCompletedActivity(any(), eq(USER_ID), eq(SLEEP_CYCLE_ID),
            eq("Siesta"), eq(morningNap.start()), eq(morningNap.end()));
        verify(repository).insertCompletedActivity(any(), eq(USER_ID), eq(SLEEP_CYCLE_ID),
            eq("Siesta"), eq(afternoonNap.start()), eq(afternoonNap.end()));
    }

    /** An outbox update notification of the given executable, as the recorder is expected to append it. */
    private static OutboxEvent argThatIsUpdateOf(UUID executableId) {
        return org.mockito.ArgumentMatchers.argThat(event ->
            "ExecutableUpdatedEvent".equals(event.eventType())
                && executableId.toString().equals(event.aggregateId()));
    }

    /** A sleep day built the way the parser builds one: the night resolved out of its sessions. */
    private static AggregatedSleep sleepOf(SleepSession... sessions) {
        List<SleepSession> all = List.of(sessions);
        SleepSession night = AggregatedSleep.nightOf(all, ZoneOffset.UTC);
        long asleep = all.stream().mapToLong(SleepSession::asleepSeconds).sum();
        SleepStageSample totals = new SleepStageSample(
            night.start(), night.start().plusSeconds(asleep), 0, asleep, 0, 0, 0, 0);
        return new AggregatedSleep(totals, night, all);
    }
}
