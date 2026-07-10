package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.EnergyThresholds;
import com.hyperbrain.planner.domain.model.EnergyTier;

import java.util.Locale;

/**
 * Pure domain service that maps last night's {@code sleep_score} onto the day's load parameters
 * (ADR-016 — the eje de energía). Stepped, direct thresholds — never a latent "energy" variable:
 * the score selects a {@link EnergyTier}, which fixes F3 (chaos margin) and F6 (high-load quota).
 *
 * <p>The freshness guard is applied upstream: a record older than the freshness bound (or no record
 * at all) arrives here as {@code null}, which resolves to the NEUTRAL default (never modulate today
 * with the night before last).
 *
 * <p>Every result carries the readable {@code Sleep Score → margin → quota} criterion the agenda must
 * surface (legibilidad obligatoria — the load is never trimmed silently).
 */
public class EnergyResolver {

    private final EnergyThresholds thresholds;

    /** Creates a resolver using the sanctioned default thresholds. */
    public EnergyResolver() {
        this(EnergyThresholds.DEFAULT);
    }

    /**
     * Creates a resolver with explicit thresholds (calibration seam).
     *
     * @param thresholds the tier cut points and margin/quota values; never null
     */
    public EnergyResolver(EnergyThresholds thresholds) {
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds must not be null");
        }
        this.thresholds = thresholds;
    }

    /**
     * Resolves the day's load parameters from a fresh {@code sleep_score}, or the NEUTRAL default when
     * no fresh score is available.
     *
     * @param sleepScore last night's fresh {@code sleep_score}, or null when absent/stale
     * @return the resolved F3 margin, F6 quota and readable criterion
     */
    public EnergyProfile resolve(Integer sleepScore) {
        if (sleepScore == null) {
            return profile(EnergyTier.NEUTRAL, thresholds.neutralMargin(), thresholds.neutralQuota(),
                String.format(Locale.ROOT,
                    "No fresh sleep score → neutral energy → chaos margin %.0f%% (F3), "
                        + "high-load quota %d (F6)",
                    thresholds.neutralMargin() * 100, thresholds.neutralQuota()));
        }
        EnergyTier tier = tierOf(sleepScore);
        return switch (tier) {
            case LOW -> profile(tier, thresholds.lowMargin(), thresholds.lowQuota(),
                criterion(sleepScore, thresholds.lowMargin(), thresholds.lowQuota()));
            case NEUTRAL -> profile(tier, thresholds.neutralMargin(), thresholds.neutralQuota(),
                criterion(sleepScore, thresholds.neutralMargin(), thresholds.neutralQuota()));
            case HIGH -> profile(tier, thresholds.highMargin(), thresholds.highQuota(),
                criterion(sleepScore, thresholds.highMargin(), thresholds.highQuota()));
        };
    }

    private EnergyTier tierOf(int sleepScore) {
        if (sleepScore < thresholds.lowCeiling()) {
            return EnergyTier.LOW;
        }
        if (sleepScore >= thresholds.highFloor()) {
            return EnergyTier.HIGH;
        }
        return EnergyTier.NEUTRAL;
    }

    private static EnergyProfile profile(EnergyTier tier, double margin, int quota, String criterion) {
        return new EnergyProfile(tier, margin, quota, criterion);
    }

    private static String criterion(int sleepScore, double margin, int quota) {
        return String.format(Locale.ROOT,
            "Sleep Score %d → chaos margin %.0f%% (F3) → high-load quota %d (F6)",
            sleepScore, margin * 100, quota);
    }
}
