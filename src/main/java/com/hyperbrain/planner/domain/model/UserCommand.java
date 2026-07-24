package com.hyperbrain.planner.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A validated user command consumed from {@code user-commands.fifo} (HU-01b slice 2). The
 * {@code commandId} is the idempotency key: SQS delivers at-least-once, so the application layer
 * deduplicates on it before acting. {@code occurredAt} is the instant the user issued the command
 * and doubles as the replan reference instant (replan «from now» = from when the button was
 * pressed), which also keeps the consumers deterministic under test.
 *
 * <p>{@code sleepSession} is the optional HealthKit sleep dump carried by a {@code REPLAN_AGENDA}
 * command (provisional bridge: the free Apple account cannot grant the app a HealthKit entitlement,
 * but an iOS Shortcut can read Health and forward the raw stage samples). When present, the planner
 * distils and records it as a device sleep record (device precedence) before replanning, so the
 * agenda uses real sleep instead of the default. Absent = the replan behaves exactly as before.
 *
 * @param commandId    stable command identity (the wire {@code command_id}); never null
 * @param type         the command verb; never null
 * @param occurredAt   when the user issued the command; never null
 * @param sleepScore   the {@code SLEEP_SCORE} payload; required for that type, null otherwise
 * @param sleepSession the optional raw sleep dump for a {@code REPLAN_AGENDA}; null otherwise
 */
public record UserCommand(
    UUID commandId,
    UserCommandType type,
    OffsetDateTime occurredAt,
    SleepScoreInput sleepScore,
    DeviceSleepSamples sleepSession
) {

    public UserCommand {
        if (commandId == null) {
            throw new IllegalArgumentException("command_id is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("command_type is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurred_at is required");
        }
        if (type == UserCommandType.SLEEP_SCORE && sleepScore == null) {
            throw new IllegalArgumentException("SLEEP_SCORE requires a payload with score and date");
        }
        if (sleepSession != null && type != UserCommandType.REPLAN_AGENDA) {
            throw new IllegalArgumentException("sleep enrichment is only valid on REPLAN_AGENDA");
        }
    }
}
