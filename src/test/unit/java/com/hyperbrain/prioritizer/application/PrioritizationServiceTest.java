package com.hyperbrain.prioritizer.application;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext;
import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext.AncestorLink;
import com.hyperbrain.prioritizer.domain.model.CycleType;
import com.hyperbrain.prioritizer.domain.model.ExecutableFactors;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.prioritizer.domain.port.out.PriorityStateRepository;
import com.hyperbrain.prioritizer.domain.service.PriorityScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.Collections.emptySet;

@DisplayName("PrioritizationService (#66a rescore + reprioritizeToday)")
class PrioritizationServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID E1 = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    private static final UUID E2 = UUID.fromString("e0000000-0000-0000-0000-000000000002");
    private static final UUID MCI_CYCLE = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final double EPS = 1e-9;

    private PriorityStateRepository repository;
    // The real calculator is a pure domain service (no infrastructure) — used directly, not mocked.
    private PrioritizationService service;

    @BeforeEach
    void setUp() {
        repository = mock(PriorityStateRepository.class);
        service = new PrioritizationService(repository, new PriorityScoreCalculator());
    }

    @Test
    @DisplayName("rescore: scores one executable's current factors and persists the result via saveScores")
    void rescore_scores_and_persists() {
        // impact 5 -> 1.0*0.4, urgency 3 -> 0.5*0.3, effort 0 -> 1.0*0.1, no alignment
        when(repository.findFactors(E1))
            .thenReturn(Optional.of(new ExecutableFactors(E1, null, 5, 3.0, 0.0)));
        when(repository.saveScores(anyList())).thenReturn(Set.of(E1));

        RescoreResult result = service.rescore(E1);

        assertThat(result.score()).isPresent();
        assertThat(result.score().get().score()).isCloseTo(0.4 + 0.15 + 0.1, within(EPS));
        assertThat(result.score().get().urgency()).isCloseTo(3.0, within(EPS)); // raw, for persistence

        ArgumentCaptor<List<PriorityScore>> saved = captor();
        verify(repository).saveScores(saved.capture());
        assertThat(saved.getValue()).singleElement()
            .satisfies(s -> assertThat(s.executableId()).isEqualTo(E1));
    }

    @Test
    @DisplayName("rescore: reports moved=true when saveScores persisted the row (score crossed the epsilon)")
    void rescore_reports_moved_when_persisted() {
        when(repository.findFactors(E1))
            .thenReturn(Optional.of(new ExecutableFactors(E1, null, 5, 3.0, 0.0)));
        when(repository.saveScores(anyList())).thenReturn(Set.of(E1));

        RescoreResult result = service.rescore(E1);

        assertThat(result.moved()).isTrue();
    }

    @Test
    @DisplayName("rescore: reports moved=false when saveScores found no change within the epsilon")
    void rescore_reports_not_moved_when_unchanged() {
        when(repository.findFactors(E1))
            .thenReturn(Optional.of(new ExecutableFactors(E1, null, 5, 3.0, 0.0)));
        when(repository.saveScores(anyList())).thenReturn(emptySet());

        RescoreResult result = service.rescore(E1);

        assertThat(result.score()).isPresent();
        assertThat(result.moved()).isFalse();
    }

    @Test
    @DisplayName("rescore: applies the graded alignment of the executable's cycle")
    void rescore_applies_alignment() {
        // Only alignment contributes: a fully-aligned MCI at distance 0 -> +0.2
        when(repository.findFactors(E1))
            .thenReturn(Optional.of(new ExecutableFactors(E1, MCI_CYCLE, 1, 0.0, 5.0)));
        when(repository.findAlignmentContext(MCI_CYCLE))
            .thenReturn(Optional.of(new CycleAlignmentContext(
                CycleType.MCI, List.of(new AncestorLink(CycleType.MCI, 0)))));
        when(repository.saveScores(anyList())).thenReturn(Set.of(E1));

        RescoreResult result = service.rescore(E1);

        assertThat(result.score()).isPresent();
        assertThat(result.score().get().score()).isCloseTo(0.2, within(EPS));
    }

    @Test
    @DisplayName("rescore: a row with no priority signal is a no-op (no-signal, never persisted)")
    void rescore_missing_row_is_noop() {
        when(repository.findFactors(E1)).thenReturn(Optional.empty());

        RescoreResult result = service.rescore(E1);

        assertThat(result.score()).isEmpty();
        assertThat(result.moved()).isFalse();
        verify(repository, never()).saveScores(anyList());
    }

    @Test
    @DisplayName("reprioritizeToday: ranks the day and returns the changed ids reported by saveScores")
    void reprioritize_returns_changed_ids() {
        when(repository.findTodaysFactors(USER)).thenReturn(List.of(
            new ExecutableFactors(E1, null, 5, 6.0, 0.0),
            new ExecutableFactors(E2, null, 1, 0.0, 5.0)));
        when(repository.findAlignmentContexts(USER)).thenReturn(Map.of());
        when(repository.saveScores(anyList())).thenReturn(Set.of(E1));

        Set<UUID> changed = service.reprioritizeToday(USER);

        assertThat(changed).containsExactly(E1);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<PriorityScore>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
