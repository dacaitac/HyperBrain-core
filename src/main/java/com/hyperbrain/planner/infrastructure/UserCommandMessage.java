package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyperbrain.planner.domain.model.SleepScoreInput;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.model.UserCommandType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape of a {@code user-commands.fifo} message (AsyncAPI {@code UserCommand}): snake_case
 * JSON published by SentinelAPI with {@code MessageDeduplicationId = command_id}. {@code payload}
 * is present only for {@code SLEEP_SCORE}.
 *
 * <p>{@link #toDomain()} performs the contract validation and throws
 * {@link IllegalArgumentException} on any violation (unknown type, missing fields, score out of
 * {@code [0, 100]}); the listener translates that into a WARN + ack discard so an invalid command
 * never poisons the DLQ.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record UserCommandMessage(
    @JsonProperty("command_id") UUID commandId,
    @JsonProperty("command_type") String commandType,
    @JsonProperty("origin") String origin,
    @JsonProperty("occurred_at") OffsetDateTime occurredAt,
    @JsonProperty("payload") SleepScorePayload payload
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SleepScorePayload(
        @JsonProperty("score") Integer score,
        @JsonProperty("date") LocalDate date
    ) {
    }

    /**
     * Maps the wire message to the validated domain command.
     *
     * @return the domain command
     * @throws IllegalArgumentException when the message violates the contract
     */
    UserCommand toDomain() {
        UserCommandType type = parseType();
        SleepScoreInput sleepScore = null;
        if (type == UserCommandType.SLEEP_SCORE) {
            if (payload == null || payload.score() == null) {
                throw new IllegalArgumentException("SLEEP_SCORE requires payload.score");
            }
            sleepScore = new SleepScoreInput(payload.score(), payload.date());
        }
        return new UserCommand(commandId, type, occurredAt, sleepScore);
    }

    private UserCommandType parseType() {
        if (commandType == null) {
            throw new IllegalArgumentException("command_type is required");
        }
        try {
            return UserCommandType.valueOf(commandType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown command_type: " + commandType);
        }
    }
}
