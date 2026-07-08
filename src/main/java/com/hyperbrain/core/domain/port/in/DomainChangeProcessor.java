package com.hyperbrain.core.domain.port.in;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

/**
 * Published interface of the {@code core} module (ADR-012 D2): applies derived business
 * rules to a merged executable state <b>before</b> it is persisted and propagated. The sync
 * handlers invoke it inside the ingestion transaction, so the Transactional Outbox always
 * carries the final processed state — never raw satellite data.
 *
 * <p>Since ADR-013 the previous persisted state travels alongside the merged one: the focus
 * and progress rules (DR-05..DR-08) react to <i>transitions</i> (→{@code IN_PROGRESS},
 * →{@code DONE}), which the merged state alone cannot reveal. DR-02 (#44) shares the same
 * need and plugs into this signature unchanged.
 *
 * <p>Implementations must be cheap and synchronous (score computation, validation, streak
 * bookkeeping): they run on the ingestion path of every inbound event. Heavy or deferred
 * logic (Planner agenda regeneration, cognitive) belongs to asynchronous consumers of
 * {@code core-events}, whose changes re-enter the pipeline as {@code source_system=SYSTEM}.
 *
 * <p>{@link ExecutableSnapshot} is owned by the sync module today; it migrates to this
 * module when the task domain materializes (HU-01, first real implementor via the
 * Prioritizer).
 */
public interface DomainChangeProcessor {

    /**
     * Applies derived business rules to one merged executable state.
     *
     * @param previous the persisted state before this ingestion; null on CREATE
     * @param merged   the source-aware merged state about to be persisted (ADR-012 D1)
     * @param origin   the external system that produced the inbound change
     * @return the final state to persist and propagate; never null
     */
    ExecutableSnapshot process(ExecutableSnapshot previous, ExecutableSnapshot merged,
                               ExternalSystem origin);
}
