package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A persisted {@code PLANNED} block re-read for the agenda write-back (HU-01b delivery slice). Unlike
 * {@link AgendaBlock} (a pre-persistence proposal) this carries the block's own {@code id} — the
 * identity under which the block is mapped to its Apple entity, kept distinct from the executable's
 * own mapping so a block and the task it schedules never collide in {@code sync_mappings}.
 *
 * @param blockId       the {@code core_time_block.id}; the write-back local id; never null
 * @param executableId  the executable this block schedules; never null
 * @param executableName the executable's display name, used as the reminder title; never blank
 * @param start         the block start instant; never null
 * @param end           the block end instant; never null, after {@code start}
 * @param reason        the readable placement reason persisted with the block; may be null
 */
public record PlannedBlockRecord(
    UUID blockId,
    UUID executableId,
    String executableName,
    OffsetDateTime start,
    OffsetDateTime end,
    String reason
) {

    public PlannedBlockRecord {
        if (blockId == null) {
            throw new IllegalArgumentException("blockId must not be null");
        }
        if (executableId == null) {
            throw new IllegalArgumentException("executableId must not be null");
        }
        if (executableName == null || executableName.isBlank()) {
            throw new IllegalArgumentException("executableName must not be blank");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: " + start + " .. " + end);
        }
    }
}
