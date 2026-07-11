package com.hyperbrain.planner.domain.port.out;

import com.hyperbrain.planner.domain.model.MorningTriggerState;

import java.util.UUID;

/**
 * Out-port for the persisted state of the morning agenda dispatch (HU-01b delivery slice): the
 * previous trigger minute-of-day (hysteresis anchor) and the last-fired day (once-per-day guard).
 * The MVP adapter stores it in {@code sys_user.settings.planner_state.morning_trigger} (JSONB), so no
 * schema change is required; a dedicated column can replace it later without touching this contract.
 */
public interface MorningTriggerStore {

    /**
     * Loads the user's persisted morning-trigger state.
     *
     * @param userId the owning user; never null
     * @return the stored state, or {@link MorningTriggerState#EMPTY} when the user has none
     */
    MorningTriggerState load(UUID userId);

    /**
     * Persists the user's morning-trigger state, replacing any prior value.
     *
     * @param userId the owning user; never null
     * @param state  the state to store; never null
     */
    void save(UUID userId, MorningTriggerState state);
}
