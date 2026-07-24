package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.SleepScoreInput;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.model.UserCommandType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape of a {@code user-commands.fifo} message (AsyncAPI {@code UserCommand}): snake_case
 * JSON published by SentinelAPI with {@code MessageDeduplicationId = command_id}. {@code payload}
 * is present only for {@code SLEEP_SCORE}; {@code sleep} is an optional, top-level HealthKit dump for
 * {@code REPLAN_AGENDA} — a capture timestamp plus the raw, un-aggregated stage samples an iOS
 * Shortcut reads from Health (the {@code SleepSampleSessionParser} distils them into one night).
 *
 * <p>{@link #toDomain()} performs the contract validation and throws
 * {@link IllegalArgumentException} on any violation (unknown type, missing fields, score out of
 * {@code [0, 100]}); the listener translates that into a WARN + ack discard so an invalid command
 * never poisons the DLQ. The {@code sleep} dump is read tolerantly here (its content is validated
 * downstream, with the user's timezone) so a malformed dump never blocks the replan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record UserCommandMessage(
    @JsonProperty("command_id") UUID commandId,
    @JsonProperty("command_type") String commandType,
    @JsonProperty("origin") String origin,
    @JsonProperty("occurred_at") OffsetDateTime occurredAt,
    @JsonProperty("payload") SleepScorePayload payload,
    @JsonProperty("sleep") SleepSessionPayload sleep
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SleepScorePayload(
        @JsonProperty("score") Integer score,
        @JsonProperty("date") LocalDate date
    ) {
    }

    /**
     * Optional HealthKit dump carried alongside a {@code REPLAN_AGENDA}: the Shortcut's capture
     * {@code date} and the raw stage {@code sample}s (each a stage label with local start/end strings).
     * All fields are read verbatim; interpretation happens in the domain parser.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SleepSessionPayload(
        @JsonProperty("date") String date,
        @JsonProperty("sample") List<SampleEntry> sample
    ) {

        /** One raw stage sample. {@code duration} is deliberately unmapped — seconds are re-derived. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record SampleEntry(
            @JsonProperty("stage") String stage,
            @JsonProperty("startDate") String startDate,
            @JsonProperty("endDate") String endDate
        ) {
        }

        /** Maps the wire dump to the domain value object, tolerating an absent sample list. */
        DeviceSleepSamples toDump() {
            List<DeviceSleepSamples.Sample> samples = sample == null ? List.of()
                : sample.stream()
                    .map(entry -> new DeviceSleepSamples.Sample(
                        entry.stage(), entry.startDate(), entry.endDate()))
                    .toList();
            return new DeviceSleepSamples(date, samples);
        }
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
        DeviceSleepSamples sleepSession = null;
        if (type == UserCommandType.REPLAN_AGENDA && sleep != null) {
            sleepSession = sleep.toDump();
        }
        return new UserCommand(commandId, type, occurredAt, sleepScore, sleepSession);
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
