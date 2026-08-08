package com.hyperbrain.planner.domain.model;

import java.time.LocalDate;

/**
 * What one sleep day of a {@link DeviceSleepSamples} dump amounts to: either sleep that could be read
 * ({@link ParsedSleepDay}) or a night the reader refused ({@link RefusedSleepDay}). A dump spanning
 * several nights yields one reading per night, so a single bad night is discarded on its own instead
 * of taking the rest of the dump with it.
 *
 * <p>Both cases carry the night's <b>own</b> raw samples, because both have to be archived: the refused
 * one is precisely the row worth keeping (it is the only thing that explains, afterwards, why a night
 * has no score), and filing every night's bytes under its own key is what makes a night re-derivable
 * one at a time when the reading of sleep changes again.
 */
public sealed interface SleepDayReading permits ParsedSleepDay, RefusedSleepDay {

    /** The local day this reading is about — the sleep day, labelled by the day he woke into. */
    LocalDate sleepDay();

    /** The dump's samples that belong to this night, as they arrived. */
    DeviceSleepSamples samples();
}
