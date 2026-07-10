package com.hyperbrain.prioritizer.domain.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * The calibrable domain constants of the graded alignment factor (Daniel, Comité 2026-07-09): the
 * band weight {@code W(type)} of each cycle horizon and the distance decay {@code δ(d)} applied as an
 * executable's cycle sits farther below the aligning ancestor.
 *
 * <p><b>Formula.</b> An executable's alignment is
 * {@code max}<sub>c</sub> {@code W(c.type) · δ(d)} over every {@code ACTIVE} ancestor cycle {@code c}
 * reachable by walking {@code parent_cycle_id} upward, where {@code d} is the number of hops from the
 * executable's own cycle up to {@code c}. See {@code AlignmentResolver}.
 *
 * <p><b>Bands (default).</b> MCI 1.0 · GOAL 0.5 · OBJECTIVE 0.4 · PROJECT 0.3 · ROUTINE 0.15.
 * {@code PHASE} is not one of the five bands Daniel fixed; it maps to the nearest structural level,
 * {@code PROJECT} (0.3) — reported for confirmation.
 *
 * <p><b>Decay (default).</b> {@code δ(0)=1.0, δ(1)=1.0, δ(2)=0.9, δ(≥3)=0.8}.
 *
 * <p>Like {@link PriorityWeights}, this is a calibration seam: a settings adapter can supply an
 * alternative instance without touching the resolver.
 *
 * @param bandWeights the {@code W(type)} band weight per cycle type; every {@link CycleType} present,
 *                    each value in {@code [0, 1]}
 * @param decayNear   {@code δ} at distance {@code 0} and {@code 1} (adjacent), in {@code [0, 1]}
 * @param decayMid    {@code δ} at distance {@code 2}, in {@code [0, 1]}
 * @param decayFar    {@code δ} at distance {@code 3} or more, in {@code [0, 1]}
 */
public record AlignmentWeights(
    Map<CycleType, Double> bandWeights,
    double decayNear,
    double decayMid,
    double decayFar
) {

    /** The sanctioned defaults (Daniel, Comité 2026-07-09). PHASE mapped to the PROJECT band. */
    public static final AlignmentWeights DEFAULT = new AlignmentWeights(
        defaultBands(), 1.0, 0.9, 0.8);

    public AlignmentWeights {
        if (bandWeights == null) {
            throw new IllegalArgumentException("bandWeights must not be null");
        }
        EnumMap<CycleType, Double> copy = new EnumMap<>(CycleType.class);
        for (CycleType type : CycleType.values()) {
            Double weight = bandWeights.get(type);
            if (weight == null) {
                throw new IllegalArgumentException("bandWeights must map every CycleType; missing " + type);
            }
            requireUnitInterval(weight, "bandWeights[" + type + "]");
            copy.put(type, weight);
        }
        bandWeights = Map.copyOf(copy);
        requireUnitInterval(decayNear, "decayNear");
        requireUnitInterval(decayMid, "decayMid");
        requireUnitInterval(decayFar, "decayFar");
    }

    /**
     * The band weight {@code W(type)} of a cycle horizon.
     *
     * @param type the cycle type; never null
     * @return the band weight in {@code [0, 1]}
     */
    public double bandWeight(CycleType type) {
        return bandWeights.get(type);
    }

    /**
     * The distance decay {@code δ(d)}: {@code δ(0)=δ(1)=decayNear}, {@code δ(2)=decayMid},
     * {@code δ(≥3)=decayFar}.
     *
     * @param distance hops from the executable's cycle up to the ancestor; never negative
     * @return the decay factor in {@code [0, 1]}
     */
    public double decay(int distance) {
        if (distance <= 1) {
            return decayNear;
        }
        if (distance == 2) {
            return decayMid;
        }
        return decayFar;
    }

    private static Map<CycleType, Double> defaultBands() {
        EnumMap<CycleType, Double> bands = new EnumMap<>(CycleType.class);
        bands.put(CycleType.MCI, 1.0);
        bands.put(CycleType.GOAL, 0.5);
        bands.put(CycleType.OBJECTIVE, 0.4);
        bands.put(CycleType.PROJECT, 0.3);
        bands.put(CycleType.PHASE, 0.3);
        bands.put(CycleType.ROUTINE, 0.15);
        return bands;
    }

    private static void requireUnitInterval(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]: " + value);
        }
    }
}
