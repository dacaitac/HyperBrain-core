package com.hyperbrain.sync.domain.service;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Conservative guard against destructive deletes caused by iCloud identifier mutation (#13).
 *
 * <p>An EKEvent's {@code eventIdentifier} can change shortly after the event is created, in which case
 * SentinelAPI observes a spurious {@code DELETED(oldId) + CREATED(newId)} pair. The {@code DELETED}
 * still maps — via {@code sync_mappings} — to the freshly created {@code core_time_block}, so acting on
 * it would destroy a planner block that was never really removed by the user. Because planner blocks
 * live only in Apple (unlike executables, which are also mirrored in Notion), that loss is
 * unrecoverable, so the delete path treats a very recent mapping as suspect and skips the destructive
 * deletion.
 *
 * <p>The window is a wall-clock heuristic, not a proof: it must be validated empirically on the Mac
 * Mini (does the {@code eventIdentifier} of events in the "HyperBrain" calendar actually mutate, and
 * how soon?). A future CREATED that re-maps the same block is the definitive signal; this age check is
 * the simple, safe first line.
 */
public final class ICloudIdMutationGuard {

    private final Duration mutationWindow;

    /**
     * @param mutationWindow the age below which a mapping is considered too fresh to delete
     *                       destructively; never null
     */
    public ICloudIdMutationGuard(Duration mutationWindow) {
        this.mutationWindow = mutationWindow;
    }

    /**
     * Reports whether a mapping last written at {@code mappedAt} is still inside the mutation window as
     * of {@code now}. A null timestamp is treated as outside the window (nothing to protect); a future
     * timestamp (clock skew) is treated as inside it (conservative skip).
     *
     * @param mappedAt the mapping's {@code last_synced_at}, i.e. when it was closed/refreshed
     * @param now      the reference instant
     * @return {@code true} when the mapping is fresh enough to be a suspected id mutation
     */
    public boolean withinMutationWindow(OffsetDateTime mappedAt, OffsetDateTime now) {
        if (mappedAt == null) {
            return false;
        }
        return Duration.between(mappedAt, now).compareTo(mutationWindow) < 0;
    }
}
