package com.hyperbrain.cognitive.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The intensity dials of the composed committee prompt (ADR-029 D5), bound from
 * {@code app.cognitive.committee.*}. These graduate the <b>tone and aggressiveness</b> of the LLM's
 * arrangement suggestions — they never touch the deterministic gate ({@code ProposalWallGuard} /
 * {@code AgendaValidator}) nor the WIG's required pace: those stay hard walls regardless of the dials.
 *
 * <p>MVP scope (Daniel, 2026-07-26): these are simple system properties, not a per-user
 * configuration table — the same pattern as {@code HumanizationProperties}. Migrating this (and other
 * user-specific values, e.g. the Notion database ids) to a per-user configuration schema is a
 * separate, future concern for the eventual multi-user configuration management task — explicitly out
 * of scope here.
 *
 * @param intensity      the day's productivity-intensity dial, 1 (gentle day) to 100 (full-throttle
 *                       day); never outside that range
 * @param specialContext the day's special temporal context (vacation, sprint, recovery, travel, or
 *                       none); never null
 */
@ConfigurationProperties(prefix = "app.cognitive.committee")
public record CommitteePromptProperties(int intensity, SpecialContext specialContext) {

    public CommitteePromptProperties {
        if (intensity < 1 || intensity > 100) {
            throw new IllegalArgumentException("intensity must be between 1 and 100: " + intensity);
        }
        specialContext = specialContext == null ? SpecialContext.NONE : specialContext;
    }

    /** A special temporal context that modulates the committee prompt's tone (ADR-029 D5). */
    public enum SpecialContext {
        /** No special context: the intensity dial alone shapes the tone. */
        NONE,
        /** The user is on vacation: favor rest and light, optional structure. */
        VACATION,
        /** The user is in a work sprint: favor focus and tighter sequencing, within the hard walls. */
        SPRINT,
        /** The user is recovering (illness, burnout, injury): favor gentleness and recovery. */
        RECOVERY,
        /** The user is traveling: favor flexibility around an unpredictable, non-routine day. */
        TRAVEL
    }
}
