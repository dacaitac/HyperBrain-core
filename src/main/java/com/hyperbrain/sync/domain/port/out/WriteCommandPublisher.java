package com.hyperbrain.sync.domain.port.out;

import com.hyperbrain.sync.domain.model.WriteCommand;

/**
 * Outbound port: publishes {@link WriteCommand}s to the Apple write channel
 * ({@code apple-commands.fifo}). The adapter owns transport concerns — wire serialization,
 * FIFO deduplication by {@code commandId} and group-id length limits.
 */
public interface WriteCommandPublisher {

    /**
     * Publishes a command, preserving per-entity ordering via the given group key.
     *
     * @param command  the command to publish
     * @param groupKey ordering key (EventKit id for UPDATED/DELETED, local executable id
     *                 for CREATED — CA-6); the adapter hashes keys that exceed transport limits
     */
    void publish(WriteCommand command, String groupKey);
}
