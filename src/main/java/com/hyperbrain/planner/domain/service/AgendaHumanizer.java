package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.Agenda;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.ExcludedExecutable;
import com.hyperbrain.planner.domain.model.ExclusionReason;
import com.hyperbrain.planner.domain.model.HumanizationContext;
import com.hyperbrain.planner.domain.model.HumanizationSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The post-placement half of the humanized floor (H1, HU-01c): given a raw {@link Agenda} the
 * {@code AgendaGenerator} produced, it applies the two transforms that operate on the finished block
 * set rather than on placement —
 *
 * <ol>
 *   <li><b>Anti-fragmentation (rule 3).</b> Blocks shorter than {@code minBlockMinutes} are slivers
 *       that fragment the day; they are dropped and reported as {@link ExclusionReason#BELOW_MIN_BLOCK}
 *       rather than kept. The WIG is never a sliver-drop candidate.</li>
 *   <li><b>Occupancy cap (rule 6).</b> The day is never packed past {@code occupancyMaxFraction} of the
 *       planning window: while the busy minutes exceed the cap, the lowest-priority non-WIG block is
 *       trimmed and reported as {@link ExclusionReason#OVER_OCCUPANCY_CAP}, leaving deliberate slack.
 *       The lower band edge is aspirational — the floor never invents work to reach it.</li>
 * </ol>
 *
 * <p>Buffers, meal anchors and context batching are handled at placement time (in the generator and
 * its surrounding walls), so they are not repeated here — this service is a pure, idempotent transform
 * on the block list. The WIG blocks (F1) are intocable throughout.
 *
 * <p>Design pattern: single-algorithm domain service — a pure function of {@code (agenda, context,
 * settings)}; no state, no clock.
 */
public class AgendaHumanizer {

    /**
     * Applies anti-fragmentation and the occupancy cap to a raw agenda.
     *
     * @param raw      the generator's raw agenda; never null
     * @param context  the window bounds and per-executable priorities; never null
     * @param settings the humanization calibration; never null
     * @return a new agenda with slivers and over-cap blocks removed and reported in {@code excluded}
     */
    public Agenda humanize(Agenda raw, HumanizationContext context, HumanizationSettings settings) {
        if (raw == null) {
            throw new IllegalArgumentException("raw must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }

        List<AgendaBlock> kept = new ArrayList<>();
        List<ExcludedExecutable> excluded = new ArrayList<>(raw.excluded());

        dropSlivers(raw.blocks(), settings.minBlockMinutes(), kept, excluded);
        trimToOccupancyCap(kept, context, settings.occupancyMaxFraction(), excluded);

        kept.sort(Comparator.comparing(AgendaBlock::start));
        return new Agenda(kept, excluded, raw.paused(), raw.energyCriterion(), raw.degraded());
    }

    /**
     * Partitions the raw blocks into kept blocks and dropped slivers: a non-WIG block shorter than
     * {@code minBlockMinutes} is removed and reported as {@link ExclusionReason#BELOW_MIN_BLOCK}.
     */
    private static void dropSlivers(List<AgendaBlock> blocks, int minBlockMinutes,
                                    List<AgendaBlock> kept, List<ExcludedExecutable> excluded) {
        for (AgendaBlock block : blocks) {
            if (!block.wig() && block.durationMinutes() < minBlockMinutes) {
                excluded.add(new ExcludedExecutable(block.executableId(), ExclusionReason.BELOW_MIN_BLOCK));
            } else {
                kept.add(block);
            }
        }
    }

    /**
     * Trims the lowest-priority non-WIG blocks until the day's busy minutes fall within the occupancy
     * cap. Candidates are ordered by ascending priority, then latest start, then executable id, so the
     * trim is deterministic and sheds the least valuable, latest work first.
     */
    private static void trimToOccupancyCap(List<AgendaBlock> kept, HumanizationContext context,
                                           double occupancyMaxFraction, List<ExcludedExecutable> excluded) {
        long capMinutes = (long) Math.floor(context.windowMinutes() * occupancyMaxFraction);
        long busy = kept.stream().mapToLong(AgendaBlock::durationMinutes).sum();
        if (busy <= capMinutes) {
            return;
        }

        List<AgendaBlock> trimOrder = kept.stream()
            .filter(block -> !block.wig())
            .sorted(Comparator
                .comparingDouble((AgendaBlock block) -> context.priorityOf(block.executableId()))
                .thenComparing(Comparator.comparing(AgendaBlock::start).reversed())
                .thenComparing(block -> block.executableId().toString()))
            .toList();

        for (AgendaBlock candidate : trimOrder) {
            if (busy <= capMinutes) {
                break;
            }
            kept.remove(candidate);
            excluded.add(new ExcludedExecutable(candidate.executableId(), ExclusionReason.OVER_OCCUPANCY_CAP));
            busy -= candidate.durationMinutes();
        }
    }
}
