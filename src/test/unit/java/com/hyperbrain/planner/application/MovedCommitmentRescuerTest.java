package com.hyperbrain.planner.application;

import com.hyperbrain.core.domain.port.in.ExecutableSchedulingService;
import com.hyperbrain.planner.domain.model.MovableCommitment;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MovedCommitmentRescuer — an interruption costs the hour, never the day (ADR-040 D9)")
class MovedCommitmentRescuerTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 7);
    private static final UUID SESSION = UUID.fromString("55555555-0000-0000-0000-00000000000e");

    private PlannerStateRepository repository;
    private ExecutableSchedulingService scheduling;
    private MovedCommitmentRescuer rescuer;

    @BeforeEach
    void setUp() {
        repository = mock(PlannerStateRepository.class);
        scheduling = mock(ExecutableSchedulingService.class);
        rescuer = new MovedCommitmentRescuer(repository, scheduling);
    }

    @Test
    @DisplayName("a study session an interruption ran over is moved to the first free stretch of its day")
    void an_overtaken_commitment_is_moved_forward() {
        // Given: a 09:00-10:00 session, and a replan at 11:00 — it never happened.
        givenCommitments(new MovableCommitment(SESSION, at(9), at(10)));
        when(scheduling.retimeWithinDay(eq(SESSION), any(), any(), eq(UTC))).thenReturn(true);

        // When
        List<UUID> moved = rescuer.rescue(USER, DAY, UTC, at(11), at(22), List.of());

        // Then: same day, new hour, same duration.
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(scheduling).retimeWithinDay(eq(SESSION), start.capture(), end.capture(), eq(UTC));
        assertThat(start.getValue()).isEqualTo(at(11));
        assertThat(end.getValue()).isEqualTo(at(12));
        assertThat(moved).containsExactly(SESSION);
    }

    @Test
    @DisplayName("it lands after the walls, never on top of a block the plan just created")
    void it_lands_clear_of_every_wall() {
        // Given: the replan opens at 11:00 but the plan has already filled 11:00-13:00.
        givenCommitments(new MovableCommitment(SESSION, at(9), at(10)));
        when(scheduling.retimeWithinDay(eq(SESSION), any(), any(), eq(UTC))).thenReturn(true);
        List<OccupiedInterval> walls =
            List.of(new OccupiedInterval(UUID.randomUUID(), at(11), at(13), false));

        // When
        rescuer.rescue(USER, DAY, UTC, at(11), at(22), walls);

        // Then
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(scheduling).retimeWithinDay(eq(SESSION), start.capture(), any(), eq(UTC));
        assertThat(start.getValue()).isEqualTo(at(13));
    }

    @Test
    @DisplayName("a commitment still ahead is never touched: its hour is the user's")
    void a_commitment_still_ahead_is_left_alone() {
        // Given: a session at 15:00 and a replan at 11:00 — nothing has run it over.
        givenCommitments(new MovableCommitment(SESSION, at(15), at(16)));

        // When
        List<UUID> moved = rescuer.rescue(USER, DAY, UTC, at(11), at(22), List.of());

        // Then: recovering authority over the hour is not a licence to rearrange the user's calendar.
        assertThat(moved).isEmpty();
        verifyNoInteractions(scheduling);
    }

    @Test
    @DisplayName("with no room left on its day it stays where it is — moving it to tomorrow is not ours")
    void with_no_room_left_it_stays_put() {
        // Given: the rest of the day is entirely spoken for.
        givenCommitments(new MovableCommitment(SESSION, at(9), at(10)));
        List<OccupiedInterval> walls =
            List.of(new OccupiedInterval(UUID.randomUUID(), at(11), at(22), false));

        // When
        List<UUID> moved = rescuer.rescue(USER, DAY, UTC, at(11), at(22), walls);

        // Then
        assertThat(moved).isEmpty();
        verify(scheduling, never()).retimeWithinDay(any(), any(), any(), any());
    }

    @Test
    @DisplayName("two overtaken commitments do not land on top of each other")
    void two_rescued_commitments_do_not_collide() {
        // Given
        UUID second = UUID.fromString("66666666-0000-0000-0000-00000000000f");
        givenCommitments(
            new MovableCommitment(SESSION, at(8), at(9)),
            new MovableCommitment(second, at(9), at(10)));
        when(scheduling.retimeWithinDay(any(), any(), any(), eq(UTC))).thenReturn(true);

        // When
        rescuer.rescue(USER, DAY, UTC, at(11), at(22), List.of());

        // Then: the first one becomes a wall for the second.
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(scheduling, org.mockito.Mockito.times(2))
            .retimeWithinDay(any(), start.capture(), any(), eq(UTC));
        assertThat(start.getAllValues()).containsExactly(at(11), at(12));
    }

    @Test
    @DisplayName("its own window is among the walls now, and it never blocks its own rescue")
    void a_commitment_does_not_wall_itself_out_of_its_rescue() {
        // Given: commitments occupy the day, so the one being rescued arrives here as a wall of its own.
        // What keeps that from stopping it is the trigger itself — only a commitment already behind the
        // run's lower bound is moved, and the search starts at that bound — so the stale wall is out of
        // reach by construction. This pins that reasoning: widen the trigger to a commitment still ahead
        // and the rescue would start colliding with the very thing it is moving.
        givenCommitments(new MovableCommitment(SESSION, at(9), at(10)));
        when(scheduling.retimeWithinDay(eq(SESSION), any(), any(), eq(UTC))).thenReturn(true);
        List<OccupiedInterval> walls = List.of(new OccupiedInterval(SESSION, at(9), at(10), false));

        // When
        List<UUID> moved = rescuer.rescue(USER, DAY, UTC, at(11), at(22), walls);

        // Then: the first free stretch, not a slot pushed aside by its own shadow.
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(scheduling).retimeWithinDay(eq(SESSION), start.capture(), any(), eq(UTC));
        assertThat(start.getValue()).isEqualTo(at(11));
        assertThat(moved).containsExactly(SESSION);
    }

    private void givenCommitments(MovableCommitment... commitments) {
        when(repository.loadMovableCommitments(eq(USER), any(), any()))
            .thenReturn(List.of(commitments));
    }

    private static OffsetDateTime at(int hour) {
        return DAY.atTime(hour, 0).atZone(UTC).toOffsetDateTime();
    }
}
