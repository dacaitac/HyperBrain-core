package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port used by the Notion write-back (HU-10) to load the full state of the
 * entities it propagates: executables (with their execution profile) and cycles.
 */
public interface SyncSnapshotRepository {

    /**
     * Loads the Notion-facing snapshot of one executable.
     *
     * @param id the {@code core_executable} id
     * @return the snapshot, or empty if the row no longer exists
     */
    Optional<ExecutableSnapshot> findExecutable(UUID id);

    /**
     * Loads the Notion-facing snapshot of one cycle.
     *
     * @param id the {@code core_cycle} id
     * @return the snapshot, or empty if the row no longer exists
     */
    Optional<CycleSnapshot> findCycle(UUID id);
}
