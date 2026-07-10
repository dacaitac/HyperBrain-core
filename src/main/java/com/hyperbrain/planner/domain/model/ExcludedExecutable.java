package com.hyperbrain.planner.domain.model;

import java.util.UUID;

/**
 * An executable the generator considered but did not place, paired with the reason. Part of the
 * agenda's explicit exclusion list (legibilidad obligatoria — nothing is dropped in silence).
 *
 * @param executableId the excluded executable; never null
 * @param reason       why it was left off the day; never null
 */
public record ExcludedExecutable(UUID executableId, ExclusionReason reason) {

    public ExcludedExecutable {
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
    }
}
