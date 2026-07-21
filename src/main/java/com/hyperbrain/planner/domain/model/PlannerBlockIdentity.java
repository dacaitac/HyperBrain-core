package com.hyperbrain.planner.domain.model;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Derives the <b>stable identity</b> of a persisted {@code PLANNER} block from its natural key so a
 * day's regeneration (a full-day run or a replan) reconciles the existing blocks instead of dropping
 * and re-creating them. A block that survives a regeneration keeps its {@code core_time_block.id} and
 * therefore its {@code sync_mapping} / EKEvent — the write-back emits an {@code UPDATE} rather than a
 * fresh {@code CREATE}, which is what stops the Apple calendar from accumulating duplicate events on
 * every replan (#15).
 *
 * <p><b>Natural key.</b> {@code executable_id + agenda_date} (+ a per-executable {@code sequence}).
 * The deterministic floor ({@link com.hyperbrain.planner.domain.service.AgendaGenerator}) places at
 * most one block per executable per day (it skips an executable already {@code placed}), so
 * {@code sequence} is {@code 0} in every real plan; it exists only as a defensive discriminator so a
 * hypothetical future generator that splits an executable into several same-day blocks never derives
 * a colliding id. The block's start-of-day, not its wall-clock start, anchors the key: a surviving
 * block keeps its identity even when the regeneration shifts it to a different hour.
 *
 * <p>The id is a name-based {@code UUID} (type 3) over the key, so it is a pure, reproducible function
 * of the natural key — no persistence round-trip, no randomness.
 */
public final class PlannerBlockIdentity {

    private static final String NAMESPACE = "hyperbrain-planner-block:";

    private PlannerBlockIdentity() {
    }

    /**
     * A generated block paired with the stable {@code core_time_block.id} it must be persisted under.
     *
     * @param blockId the deterministic block identity; never null
     * @param block   the generated block to persist; never null
     */
    public record IdentifiedBlock(UUID blockId, AgendaBlock block) {

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
     * Derives the stable block id from its natural key.
     *
     * @param executableId the executable the block schedules; never null
     * @param day          the agenda day the block belongs to; never null
     * @param sequence     the block's ordinal among same-day blocks of the executable (0 in every
     *                     real plan); never negative
     * @return the deterministic {@code core_time_block.id}; never null
     */
    public static UUID forBlock(UUID executableId, LocalDate day, int sequence) {
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
        if (day == null) {
            throw new IllegalArgumentException("day must not be null");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        String key = NAMESPACE + executableId + ':' + day + ':' + sequence;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Assigns a stable id to every generated block of the day. Blocks are visited in chronological
     * order so the per-executable {@code sequence} is a pure function of the plan, independent of the
     * incoming list order; the single block a real plan holds per executable always lands on
     * {@code sequence 0}, keeping its identity across regenerations regardless of the hour it moves to.
     *
     * @param blocks the day's generated blocks; never null, may be empty
     * @param day    the agenda day; never null
     * @return the blocks paired with their stable ids; never null, same size as {@code blocks}
     */
    public static List<IdentifiedBlock> assign(List<AgendaBlock> blocks, LocalDate day) {
        if (blocks == null) {
            throw new IllegalArgumentException("blocks must not be null");
        }
        if (day == null) {
            throw new IllegalArgumentException("day must not be null");
        }
        List<AgendaBlock> chronological = new ArrayList<>(blocks);
        chronological.sort(Comparator.comparing(AgendaBlock::start).thenComparing(AgendaBlock::end));

        Map<UUID, Integer> nextSequence = new HashMap<>();
        List<IdentifiedBlock> identified = new ArrayList<>(chronological.size());
        for (AgendaBlock block : chronological) {
            int sequence = nextSequence.merge(block.executableId(), 0, (current, ignored) -> current + 1);
            identified.add(new IdentifiedBlock(forBlock(block.executableId(), day, sequence), block));
        }
        return identified;
    }
}
