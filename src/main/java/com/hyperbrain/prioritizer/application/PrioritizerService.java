package com.hyperbrain.prioritizer.application;

import java.util.Set;
import java.util.UUID;

/**
 * Published application interface of the {@code prioritizer} module (#66a). It is the <b>only</b>
 * seam through which other modules (notably {@code core}'s domain-rule chain) drive priority
 * recomputation: the {@code core → prioritizer} edge goes exclusively through these two methods, so
 * the modules stay isolated (no ArchUnit yet — this boundary is enforced by convention and review).
 *
 * <p>Both methods run inside the caller's transaction and persist through the same score columns
 * ({@code priority_score}, {@code urgency_score}, {@code priority_computed_at}), writing only the
 * rows whose score actually moved.
 */
public interface PrioritizerService {

    /**
     * Recomputes the full Priority Score (v2) of a single executable — impact, urgency evaluated at
     * {@code now()}, inverted effort and graded alignment — and persists it when it changed.
     *
     * <p>Reads the executable's current factors straight from the persisted row, so the caller must
     * run it <b>after</b> the merged state has been upserted, or it scores a stale snapshot (#66a,
     * ADR-020). Used by the on-event reflection during Notion ingestion: the returned
     * {@link RescoreResult#moved()} tells the caller whether to stage a SYSTEM-owned outbound
     * reflection so the mirror (Notion {@code Priority Score} / {@code Urgence}) catches the fresh,
     * SYSTEM-authored value the inbound NOTION event would otherwise strand (RF-17 loop protection).
     *
     * <p>Returns {@link RescoreResult#noSignal()} when the executable carries no priority signal (not
     * yet persisted, system-generated, or a read-only AGENDA row), in which case there is nothing to
     * reflect. {@code moved} is the Prioritizer's sole definition of a score change (epsilon-guarded
     * diff); callers must not re-derive their own.
     *
     * @param executableId the executable to rescore
     * @return the recomputed score and whether persisting it moved the stored value; a no-signal
     *         outcome when the executable has no priority to compute
     */
    RescoreResult rescore(UUID executableId);

    /**
     * Reprioritizes the user's whole day: scores and ranks every pending executable and persists the
     * results, returning the ids whose score changed so the caller can propagate only those.
     *
     * @param userId the user whose day to reprioritize
     * @return the ids of the executables whose persisted score changed; never null, may be empty
     */
    Set<UUID> reprioritizeToday(UUID userId);
}
