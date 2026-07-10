package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.ValidatedAgenda;
import com.hyperbrain.planner.domain.model.ValidationContext;
import com.hyperbrain.planner.domain.model.ValidationViolation.Wall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgendaValidator (#6a, the non-negotiable wall guard)")
class AgendaValidatorTest {

    private static final OffsetDateTime WAKE = OffsetDateTime.of(2026, 7, 10, 7, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEDTIME = OffsetDateTime.of(2026, 7, 10, 23, 0, 0, 0, ZoneOffset.UTC);

    private final AgendaValidator validator = new AgendaValidator();

    @Test
    @DisplayName("a clean agenda passes untouched")
    void clean_agenda_passes() {
        AgendaBlock block = block(WAKE, WAKE.plusMinutes(60), false, false);
        ValidatedAgenda result = validator.validate(List.of(block), context(3, Set.of()));

        assertThat(result.isClean()).isTrue();
        assertThat(result.accepted()).containsExactly(block);
    }

    @Test
    @DisplayName("wall: a block starting before wake is rejected (outside the sleep frontier)")
    void rejects_block_before_wake() {
        AgendaBlock block = block(WAKE.minusMinutes(30), WAKE.plusMinutes(30), false, false);

        ValidatedAgenda result = validator.validate(List.of(block), context(3, Set.of()));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.violations()).singleElement()
            .satisfies(v -> assertThat(v.wall()).isEqualTo(Wall.OUTSIDE_SLEEP_FRONTIER));
    }

    @Test
    @DisplayName("wall: a block ending after bedtime is rejected (outside the sleep frontier)")
    void rejects_block_after_bedtime() {
        AgendaBlock block = block(BEDTIME.minusMinutes(30), BEDTIME.plusMinutes(30), false, false);

        ValidatedAgenda result = validator.validate(List.of(block), context(3, Set.of()));

        assertThat(result.violations()).singleElement()
            .satisfies(v -> assertThat(v.wall()).isEqualTo(Wall.OUTSIDE_SLEEP_FRONTIER));
    }

    @Test
    @DisplayName("wall: a block overlapping an occupied interval is rejected")
    void rejects_overlap_with_occupied() {
        OccupiedInterval busy = new OccupiedInterval(UUID.randomUUID(),
            WAKE.plusMinutes(30), WAKE.plusMinutes(120), false);
        AgendaBlock block = block(WAKE.plusMinutes(60), WAKE.plusMinutes(90), false, false);

        ValidatedAgenda result = validator.validate(List.of(block),
            new ValidationContext(WAKE, BEDTIME, List.of(busy), 3, Set.of()));

        assertThat(result.violations()).singleElement()
            .satisfies(v -> assertThat(v.wall()).isEqualTo(Wall.OVERLAPS_OCCUPIED));
    }

    @Test
    @DisplayName("wall: a block overlapping a read-only AGENDA window is rejected (ADR-009)")
    void rejects_overlap_with_read_only_agenda() {
        OccupiedInterval agendaWall = new OccupiedInterval(UUID.randomUUID(),
            WAKE.plusMinutes(30), WAKE.plusMinutes(120), true);
        AgendaBlock block = block(WAKE.plusMinutes(60), WAKE.plusMinutes(90), false, false);

        ValidatedAgenda result = validator.validate(List.of(block),
            new ValidationContext(WAKE, BEDTIME, List.of(agendaWall), 3, Set.of()));

        assertThat(result.violations()).singleElement()
            .satisfies(v -> assertThat(v.wall()).isEqualTo(Wall.OVERLAPS_READ_ONLY_AGENDA));
    }

    @Test
    @DisplayName("wall: two proposed blocks overlapping each other — the later is rejected")
    void rejects_self_overlap() {
        AgendaBlock first = block(WAKE, WAKE.plusMinutes(60), false, false);
        AgendaBlock second = block(WAKE.plusMinutes(30), WAKE.plusMinutes(90), false, false);

        ValidatedAgenda result = validator.validate(List.of(first, second), context(3, Set.of()));

        assertThat(result.accepted()).containsExactly(first);
        assertThat(result.violations()).singleElement()
            .satisfies(v -> {
                assertThat(v.executableId()).isEqualTo(second.executableId());
                assertThat(v.wall()).isEqualTo(Wall.OVERLAPS_OCCUPIED);
            });
    }

    @Test
    @DisplayName("wall: scheduling a read-only AGENDA executable as work is rejected (ADR-009)")
    void rejects_scheduling_agenda_executable() {
        UUID agendaId = UUID.randomUUID();
        AgendaBlock block = new AgendaBlock(agendaId, WAKE, WAKE.plusMinutes(60), false, false, "r");

        ValidatedAgenda result = validator.validate(List.of(block), context(3, Set.of(agendaId)));

        assertThat(result.violations()).singleElement()
            .satisfies(v -> assertThat(v.wall()).isEqualTo(Wall.SCHEDULES_READ_ONLY_AGENDA));
    }

    @Test
    @DisplayName("F6: a high-load block beyond the quota is rejected")
    void rejects_high_load_beyond_quota() {
        AgendaBlock h1 = block(WAKE, WAKE.plusMinutes(60), false, true);
        AgendaBlock h2 = block(WAKE.plusMinutes(60), WAKE.plusMinutes(120), false, true);

        ValidatedAgenda result = validator.validate(List.of(h1, h2), context(1, Set.of()));

        assertThat(result.accepted()).containsExactly(h1);
        assertThat(result.violations()).singleElement()
            .satisfies(v -> assertThat(v.wall()).isEqualTo(Wall.HIGH_LOAD_QUOTA_EXCEEDED));
    }

    @Test
    @DisplayName("F1 intocable: the WIG is accepted even when the F6 quota is already spent")
    void wig_survives_spent_quota() {
        AgendaBlock highLoad = block(WAKE, WAKE.plusMinutes(60), false, true);
        AgendaBlock wig = block(WAKE.plusMinutes(60), WAKE.plusMinutes(120), true, true);

        ValidatedAgenda result = validator.validate(List.of(highLoad, wig), context(1, Set.of()));

        assertThat(result.isClean()).isTrue();
        assertThat(result.accepted()).contains(wig);
    }

    private ValidationContext context(int quota, Set<UUID> agendaIds) {
        return new ValidationContext(WAKE, BEDTIME, List.of(), quota, agendaIds);
    }

    private static AgendaBlock block(
        OffsetDateTime start, OffsetDateTime end, boolean wig, boolean highLoad) {
        return new AgendaBlock(UUID.randomUUID(), start, end, wig, highLoad, "reason");
    }
}
