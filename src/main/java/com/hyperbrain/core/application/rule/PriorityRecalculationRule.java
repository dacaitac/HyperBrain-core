package com.hyperbrain.core.application.rule;

import com.hyperbrain.prioritizer.application.PrioritizerService;
import com.hyperbrain.prioritizer.domain.model.PriorityScore;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * On-event priority reflection (#66a, on-event tier (b)) — recomputes the Priority Score of the
 * ingested executable synchronously inside the ingestion transaction and reflects it to the
 * satellites through the normal outbound mirror.
 *
 * <p>The edge into the Prioritizer goes exclusively through its published
 * {@link PrioritizerService#rescore(java.util.UUID)}: the rule hands the merged row's id, the
 * Prioritizer recomputes {@code P} (impact, urgency at {@code now()}, effort, alignment) and persists
 * it when it moved, and the rule rewrites the merged snapshot with the returned
 * {@code priority_score} / raw {@code urgency_score}. The rewrite keeps the ingestion checksum and the
 * outbound mirror consistent with the persisted score — the {@code NotionEventPropagator} re-reads the
 * row after commit and reflects it to Notion, so no extra event is emitted.
 *
 * <p>{@code rescore} is itself the guard: it returns empty (a no-op here) when the executable carries
 * no priority signal — not persisted yet (a CREATE scored before its upsert), system-generated
 * accounting rows, or read-only AGENDA blocks — so those snapshots pass through unchanged.
 */
@Component
public class PriorityRecalculationRule implements DomainRule {

    private static final Logger log = LoggerFactory.getLogger(PriorityRecalculationRule.class);

    private final PrioritizerService prioritizerService;

    public PriorityRecalculationRule(PrioritizerService prioritizerService) {
        this.prioritizerService = prioritizerService;
    }

    @Override
    public ExecutableSnapshot apply(ExecutableSnapshot previous, ExecutableSnapshot merged,
                                    ExternalSystem origin) {
        Optional<PriorityScore> score = prioritizerService.rescore(merged.id());
        if (score.isEmpty()) {
            return merged;
        }
        if (log.isDebugEnabled()) {
            log.debug("Reflected recomputed priority {} / urgency {} onto executable {}",
                score.get().score(), score.get().urgency(), merged.id());
        }
        return withScore(merged, score.get());
    }

    private static ExecutableSnapshot withScore(ExecutableSnapshot merged, PriorityScore score) {
        return new ExecutableSnapshot(
            merged.id(), merged.userId(), merged.parentId(), merged.cycleId(),
            merged.name(), merged.description(), merged.type(), merged.status(),
            score.score(), score.urgency(), merged.effortScore(),
            merged.isImportant(), merged.frequency(),
            merged.startTime(), merged.endTime(), merged.sourceCalendar(),
            merged.energyDrain(), merged.mentalLoad(), merged.impact(),
            merged.systemGenerated());
    }
}
