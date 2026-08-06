package com.hyperbrain.sync.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload of {@code TimeBlockChangedEvent} (ADR-038): a fine-grained, additive notification that
 * a {@code core_time_block} changed through a non-planner origin (a Notion or Apple inbound edit,
 * or a SYSTEM re-assertion). Deliberately thin — no members embedded: propagators re-read the
 * current block state at drain time, mirroring the {@code AgendaBlockPlannedEvent} contract.
 *
 * <p>{@code reflection}/{@code syncNote} are optional internal markers following the ADR-020
 * payload-marker pattern ({@code CANONICAL_STATE}): a SYSTEM re-assertion staged after a rejected
 * inbound edit carries the visible {@code Sync Note} text the Notion mirror must show. Omitted
 * from the JSON when null.
 *
 * @param blockId      the changed {@code core_time_block.id}
 * @param userId       the owning user
 * @param operation    {@code CREATED}, {@code UPDATED} or {@code DELETED}
 * @param sourceSystem origin of the change ({@code SYSTEM}, {@code NOTION}, {@code APPLE});
 *                     drives the outbox anti-echo (RF-17)
 * @param occurredAt   when the change was recorded
 * @param reflection   optional ADR-020 marker ({@code CANONICAL_STATE}) for a re-assertion
 * @param syncNote     optional visible note the re-assertion writes to the page's Sync Note
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeBlockChangedEvent(
    UUID blockId,
    UUID userId,
    String operation,
    String sourceSystem,
    OffsetDateTime occurredAt,
    String reflection,
    String syncNote
) {

    /** Outbox {@code aggregate_type} shared with {@code TimeBlockSettledEvent}. */
    public static final String AGGREGATE_TYPE = "CORE_TIME_BLOCK";

    /** Outbox {@code event_type} (past participle). */
    public static final String EVENT_TYPE = "TimeBlockChangedEvent";

    /** ADR-020 marker of a canonical-state re-assertion staged after a rejected inbound edit. */
    public static final String CANONICAL_STATE_REFLECTION = "CANONICAL_STATE";

    /** Convenience factory for a plain (non-reflection) change notification. */
    public static TimeBlockChangedEvent of(UUID blockId, UUID userId, Operation operation,
                                           String sourceSystem, OffsetDateTime occurredAt) {
        return new TimeBlockChangedEvent(
            blockId, userId, operation.name(), sourceSystem, occurredAt, null, null);
    }

    /** Convenience factory for a SYSTEM canonical re-assertion carrying a visible Sync Note. */
    public static TimeBlockChangedEvent reassertion(UUID blockId, UUID userId,
                                                    OffsetDateTime occurredAt, String syncNote) {
        return new TimeBlockChangedEvent(blockId, userId, Operation.UPDATED.name(), "SYSTEM",
            occurredAt, CANONICAL_STATE_REFLECTION, syncNote);
    }
}
