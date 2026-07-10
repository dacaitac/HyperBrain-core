package com.hyperbrain.core.application.rule;

import com.hyperbrain.prioritizer.application.PrioritizerService;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PriorityRecalculationRule (#66a on-event reflection)")
class PriorityRecalculationRuleTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private PrioritizerService prioritizerService;
    private PriorityRecalculationRule rule;

    @BeforeEach
    void setUp() {
        prioritizerService = mock(PrioritizerService.class);
        rule = new PriorityRecalculationRule(prioritizerService);
    }

    @Test
    @DisplayName("rescores the merged executable via the published interface and rewrites its score onto the snapshot")
    void rewrites_score_onto_snapshot() {
        // Given a merged row whose persisted scores are stale (or absent)
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .id(ID).priorityScore(0.10).urgencyScore(1.0).effortScore(2.0).build();
        when(prioritizerService.rescore(ID))
            .thenReturn(Optional.of(new PriorityScore(ID, 0.82, 5.5, 0.6)));

        // When
        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        // Then the fresh priority and raw urgency travel on the snapshot; everything else is untouched
        assertThat(result).usingRecursiveComparison()
            .isEqualTo(new ExecutableSnapshot(
                ID, merged.userId(), merged.parentId(), merged.cycleId(),
                merged.name(), merged.description(), merged.type(), merged.status(),
                0.82, 5.5, 2.0, merged.isImportant(), merged.frequency(),
                merged.startTime(), merged.endTime(), merged.sourceCalendar(),
                merged.energyDrain(), merged.mentalLoad(), merged.impact(),
                merged.systemGenerated()));
        verify(prioritizerService).rescore(ID);
        verifyNoMoreInteractions(prioritizerService);
    }

    @Test
    @DisplayName("passes the snapshot through unchanged when the executable carries no priority signal")
    void passes_through_when_no_priority() {
        ExecutableSnapshot merged = ExecutableSnapshotBuilder.snapshot()
            .id(ID).priorityScore(0.3).urgencyScore(2.0).build();
        when(prioritizerService.rescore(ID)).thenReturn(Optional.empty());

        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        assertThat(result).isSameAs(merged);
        verify(prioritizerService).rescore(ID);
        verifyNoMoreInteractions(prioritizerService);
    }
}
