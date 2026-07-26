package com.hyperbrain.planner.domain.model;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reconciles the <b>identity</b> of a day's {@code PLANNER} blocks across a regeneration (a full-day
 * run or a replan): every desired block is paired either with the id of the persisted block it
 * continues — so the block keeps its {@code core_time_block.id} and therefore its
 * {@code sync_mapping} / EKEvent, and the write-back emits an {@code UPDATE} instead of a duplicate
 * {@code CREATE} (#15) — or with a freshly minted surrogate id when it is genuinely new. Persisted
 * blocks that no desired block continues are reported as removed so their Apple counterpart is
 * deleted.
 *
 * <p><b>Design pattern: identity map / surrogate key with anchored reconciliation (ADR-027 D3,
 * Option 3).</b> The former strategy derived a name-based {@code UUID} from the block's single
 * {@code executableId}. With membership N (ADR-027 D1/D2) there is no scalar left to derive from, and
 * deriving from the <em>member set</em> is forbidden: a task entering or leaving the theme would
 * regenerate the id → {@code CREATE} instead of {@code UPDATE} → the duplicated-EKEvent bug of #15
 * would return. Identity is therefore a <b>persisted surrogate</b>, never a function of the
 * membership; continuity is re-established by <b>anchoring</b>:
 *
 * <ol>
 *   <li><b>Anchor pass.</b> A desired block continues the persisted block that already holds its
 *       <em>anchor</em> member ({@link AgendaBlock#executableId()}). The D5 invariant (≤ 1 live block
 *       per executable per day, enforced by a unique index on {@code core_time_block_member}) makes
 *       that persisted block unique, so the match is unambiguous.</li>
 *   <li><b>Shared-membership pass.</b> A desired block whose anchor is new continues the persisted
 *       block it shares the most members with — this is what keeps the id stable when the anchor
 *       itself rotates out of the theme. Ties are broken by the longest temporal overlap, then by the
 *       earliest persisted start, then by block id, so the outcome is a deterministic, total order.</li>
 * </ol>
 *
 * <p>A desired block sharing no member with any unclaimed persisted block is a genuinely new theme and
 * gets a fresh id. Temporal overlap is only ever a <b>tie-breaker</b>, never an anchor on its own: a
 * block whose whole membership was replaced is a different piece of work, and inheriting the id of an
 * unrelated block would silently repurpose its EKEvent.
 *
 * <p>The reconciliation is a pure function of {@code (desired, persisted)} plus the id factory; the
 * only impurity is the surrogate minted for a genuinely new block. Purity was traded for stability by
 * explicit decision of the Committee (ADR-027 §«Identidad: pureza vs estabilidad»).
 */
public final class PlannerBlockIdentity {

    private PlannerBlockIdentity() {
    }

    /**
     * A {@code PLANNER} block already persisted for the day — the reconciliation universe.
     *
     * @param blockId the persisted {@code core_time_block.id}; never null
     * @param members the executables the block currently holds (anchor included); never null nor empty
     * @param start   the persisted block start; never null
     * @param end     the persisted block end; never null
     */
    public record PersistedBlock(UUID blockId, Set<UUID> members, OffsetDateTime start,
                                 OffsetDateTime end) {

        public PersistedBlock {
            if (blockId == null) {
                throw new IllegalArgumentException("blockId must not be null");
            }
            if (members == null || members.isEmpty()) {
                throw new IllegalArgumentException("members must not be empty");
            }
            members = Set.copyOf(members);
            if (start == null || end == null) {
                throw new IllegalArgumentException("start and end must not be null");
            }
        }
    }

    /**
     * A generated block paired with the {@code core_time_block.id} it must be persisted under.
     *
     * @param blockId   the block identity: the id of the block it continues, or a fresh surrogate;
     *                  never null
     * @param block     the generated block to persist; never null
     * @param continued true when the id was inherited from a persisted block (→ {@code UPDATE}),
     *                  false when it is a freshly minted surrogate (→ {@code CREATE})
     */
    public record IdentifiedBlock(UUID blockId, AgendaBlock block, boolean continued) {

        public IdentifiedBlock {
            if (blockId == null) {
                throw new IllegalArgumentException("blockId must not be null");
            }
            if (block == null) {
                throw new IllegalArgumentException("block must not be null");
            }
        }
    }

    /**
     * The outcome of a day's identity reconciliation.
     *
     * @param identified      the desired blocks with the id to persist them under, in chronological
     *                        order; never null
     * @param removedBlockIds the persisted blocks no desired block continues, in ascending persisted
     *                        order; never null
     */
    public record Reconciliation(List<IdentifiedBlock> identified, List<UUID> removedBlockIds) {

        public Reconciliation {
            identified = List.copyOf(identified);
            removedBlockIds = List.copyOf(removedBlockIds);
        }
    }

    /**
     * Reconciles the day's identities, minting {@link UUID#randomUUID()} surrogates for genuinely new
     * blocks.
     *
     * @param desired   the blocks the new plan wants for the day; never null, may be empty
     * @param persisted the day's already-persisted regenerable blocks; never null, may be empty
     * @return the identified blocks plus the ids that dropped out of the plan; never null
     */
    public static Reconciliation reconcile(List<AgendaBlock> desired,
                                           Collection<PersistedBlock> persisted) {
        return reconcile(desired, persisted, UUID::randomUUID);
    }

    /**
     * Reconciles the day's identities against the persisted blocks, preserving the id of every block
     * the plan continues (see the class javadoc for the anchoring rules).
     *
     * @param desired   the blocks the new plan wants for the day; never null, may be empty
     * @param persisted the day's already-persisted regenerable blocks; never null, may be empty
     * @param idFactory mints the surrogate id of a genuinely new block; never null
     * @return the identified blocks plus the ids that dropped out of the plan; never null
     */
    public static Reconciliation reconcile(List<AgendaBlock> desired,
                                           Collection<PersistedBlock> persisted,
                                           Supplier<UUID> idFactory) {
        if (desired == null) {
            throw new IllegalArgumentException("desired must not be null");
        }
        if (persisted == null) {
            throw new IllegalArgumentException("persisted must not be null");
        }
        if (idFactory == null) {
            throw new IllegalArgumentException("idFactory must not be null");
        }

        List<AgendaBlock> chronological = new ArrayList<>(desired);
        chronological.sort(Comparator.comparing(AgendaBlock::start)
            .thenComparing(AgendaBlock::end)
            .thenComparing(block -> block.executableId().toString()));

        List<PersistedBlock> candidates = new ArrayList<>(persisted);
        candidates.sort(Comparator.comparing(PersistedBlock::start)
            .thenComparing(block -> block.blockId().toString()));

        UUID[] assigned = new UUID[chronological.size()];
        Set<UUID> claimed = new HashSet<>();

        matchByAnchor(chronological, candidates, assigned, claimed);
        matchBySharedMembership(chronological, candidates, assigned, claimed);

        List<IdentifiedBlock> identified = new ArrayList<>(chronological.size());
        for (int index = 0; index < chronological.size(); index++) {
            UUID inherited = assigned[index];
            identified.add(inherited != null
                ? new IdentifiedBlock(inherited, chronological.get(index), true)
                : new IdentifiedBlock(idFactory.get(), chronological.get(index), false));
        }
        List<UUID> removed = candidates.stream()
            .map(PersistedBlock::blockId)
            .filter(blockId -> !claimed.contains(blockId))
            .toList();
        return new Reconciliation(identified, removed);
    }

    /**
     * Pass 1: a desired block continues the persisted block that already holds its anchor member. The
     * D5 unique index makes that block unique, so the first unclaimed hit is the only hit.
     */
    private static void matchByAnchor(List<AgendaBlock> desired, List<PersistedBlock> candidates,
                                      UUID[] assigned, Set<UUID> claimed) {
        for (int index = 0; index < desired.size(); index++) {
            UUID anchor = desired.get(index).executableId();
            for (PersistedBlock candidate : candidates) {
                if (!claimed.contains(candidate.blockId()) && candidate.members().contains(anchor)) {
                    assigned[index] = candidate.blockId();
                    claimed.add(candidate.blockId());
                    break;
                }
            }
        }
    }

    /**
     * Pass 2: a desired block whose anchor is new continues the unclaimed persisted block it shares the
     * most members with — the rule that keeps the id stable when the anchor itself rotates out of the
     * theme. Ties fall back to the longest temporal overlap, then to the candidates' deterministic
     * order.
     */
    private static void matchBySharedMembership(List<AgendaBlock> desired,
                                                List<PersistedBlock> candidates,
                                                UUID[] assigned, Set<UUID> claimed) {
        for (int index = 0; index < desired.size(); index++) {
            if (assigned[index] != null) {
                continue;
            }
            AgendaBlock block = desired.get(index);
            List<UUID> members = block.members();
            PersistedBlock best = null;
            long bestShared = 0;
            long bestOverlap = -1;
            for (PersistedBlock candidate : candidates) {
                if (claimed.contains(candidate.blockId())) {
                    continue;
                }
                long shared = members.stream().filter(candidate.members()::contains).count();
                if (shared == 0) {
                    continue;
                }
                long overlap = overlapMinutes(block, candidate);
                if (shared > bestShared || (shared == bestShared && overlap > bestOverlap)) {
                    best = candidate;
                    bestShared = shared;
                    bestOverlap = overlap;
                }
            }
            if (best != null) {
                assigned[index] = best.blockId();
                claimed.add(best.blockId());
            }
        }
    }

    /** @return the minutes the desired and persisted intervals overlap; 0 when they are disjoint */
    private static long overlapMinutes(AgendaBlock desired, PersistedBlock persisted) {
        OffsetDateTime from = desired.start().isAfter(persisted.start()) ? desired.start() : persisted.start();
        OffsetDateTime to = desired.end().isBefore(persisted.end()) ? desired.end() : persisted.end();
        return to.isAfter(from) ? Duration.between(from, to).toMinutes() : 0L;
    }
}
