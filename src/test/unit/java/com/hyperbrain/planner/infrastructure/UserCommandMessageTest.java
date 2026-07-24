package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.SleepScoreInput;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.model.UserCommandType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UserCommandMessage — wire mapping and contract validation")
class UserCommandMessageTest {

    private static final UUID COMMAND_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime OCCURRED_AT =
        OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("deserializes the exact wire contract for SLEEP_SCORE (snake_case)")
    void deserializes_sleep_score_wire_contract() throws Exception {
        // Given the contract JSON exactly as SentinelAPI publishes it
        String json = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "SLEEP_SCORE",
              "origin": "USER",
              "occurred_at": "2026-07-10T12:00:00Z",
              "payload": { "score": 85, "date": "2026-07-10" }
            }
            """;

        // When
        UserCommand command = objectMapper.readValue(json, UserCommandMessage.class).toDomain();

        // Then
        assertThat(command).usingRecursiveComparison().isEqualTo(new UserCommand(
            COMMAND_ID, UserCommandType.SLEEP_SCORE, OCCURRED_AT, new SleepScoreInput(85, DATE), null));
    }

    @Test
    @DisplayName("deserializes the exact wire contract for REPLAN_AGENDA (no payload, no sleep)")
    void deserializes_replan_wire_contract() throws Exception {
        String json = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "2026-07-10T12:00:00Z",
              "payload": null
            }
            """;

        UserCommand command = objectMapper.readValue(json, UserCommandMessage.class).toDomain();

        assertThat(command).usingRecursiveComparison().isEqualTo(new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, OCCURRED_AT, null, null));
    }

    @Test
    @DisplayName("maps a REPLAN_AGENDA's top-level sleep dump verbatim (capture date + raw stage samples)")
    void deserializes_replan_with_sleep_dump() throws Exception {
        // Given the Lambda's exact shape: sleep is a sibling {date, sample:[...]} with local strings,
        // and each sample's redundant "duration" is ignored (seconds are re-derived downstream).
        String json = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "REPLAN_AGENDA",
              "occurred_at": "2026-07-10T12:00:00Z",
              "sleep": {
                "date": "10/07/2026 at 7:12 AM",
                "sample": [
                  {"stage":"Core","startDate":"09/07/2026 at 11:00 PM","endDate":"10/07/2026 at 12:45 AM","duration":"22:53"},
                  {"stage":"Deep","startDate":"10/07/2026 at 12:45 AM","endDate":"10/07/2026 at 1:10 AM","duration":"5:28"}
                ]
              }
            }
            """;

        UserCommand command = objectMapper.readValue(json, UserCommandMessage.class).toDomain();

        DeviceSleepSamples expected = new DeviceSleepSamples("10/07/2026 at 7:12 AM", List.of(
            new DeviceSleepSamples.Sample("Core", "09/07/2026 at 11:00 PM", "10/07/2026 at 12:45 AM"),
            new DeviceSleepSamples.Sample("Deep", "10/07/2026 at 12:45 AM", "10/07/2026 at 1:10 AM")));
        assertThat(command).usingRecursiveComparison().isEqualTo(new UserCommand(
            COMMAND_ID, UserCommandType.REPLAN_AGENDA, OCCURRED_AT, null, expected));
    }

    @Test
    @DisplayName("maps the real Shortcut fixture into the domain dump (75 samples, U+202F capture date)")
    void deserializes_real_shortcut_fixture() throws Exception {
        // Embed the captured Shortcut payload verbatim as the command's sleep object.
        String sleepJson = readFixture("/fixtures/shortcut_sleep_sample.json");
        String json = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "REPLAN_AGENDA",
              "occurred_at": "2026-07-10T12:00:00Z",
              "sleep": %s
            }
            """.formatted(sleepJson);

        UserCommand command = objectMapper.readValue(json, UserCommandMessage.class).toDomain();

        DeviceSleepSamples dump = command.sleepSession();
        assertThat(dump.samples()).hasSize(75);
        assertThat(dump.capturedAt()).startsWith("23/07/2026").contains("10:12");
        DeviceSleepSamples.Sample first = dump.samples().getFirst();
        assertThat(first.stage()).isEqualTo("Core");
        assertThat(first.start()).startsWith("22/07/2026");
    }

    @Test
    @DisplayName("a SLEEP_SCORE that also carries a sleep dump ignores it (score path only)")
    void sleep_score_ignores_top_level_sleep() throws Exception {
        String json = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "SLEEP_SCORE",
              "occurred_at": "2026-07-10T12:00:00Z",
              "payload": { "score": 70, "date": "2026-07-10" },
              "sleep": { "date": "10/07/2026 at 7:12 AM", "sample": [] }
            }
            """;

        UserCommand command = objectMapper.readValue(json, UserCommandMessage.class).toDomain();

        assertThat(command.type()).isEqualTo(UserCommandType.SLEEP_SCORE);
        assertThat(command.sleepScore()).isEqualTo(new SleepScoreInput(70, DATE));
        assertThat(command.sleepSession()).isNull();
    }

    @Test
    @DisplayName("a REPLAN_AGENDA whose sleep dump has no sample list maps to an empty dump")
    void replan_with_empty_sleep_sample_list() throws Exception {
        String json = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "REPLAN_AGENDA",
              "occurred_at": "2026-07-10T12:00:00Z",
              "sleep": { "date": "10/07/2026 at 7:12 AM" }
            }
            """;

        UserCommand command = objectMapper.readValue(json, UserCommandMessage.class).toDomain();

        assertThat(command.sleepSession()).isNotNull();
        assertThat(command.sleepSession().samples()).isEmpty();
        assertThat(command.sleepSession().capturedAt()).isEqualTo("10/07/2026 at 7:12 AM");
    }

    @Test
    @DisplayName("rejects an unknown command_type")
    void rejects_unknown_command_type() {
        UserCommandMessage message =
            new UserCommandMessage(COMMAND_ID, "MAKE_COFFEE", "USER", OCCURRED_AT, null, null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MAKE_COFFEE");
    }

    @Test
    @DisplayName("rejects a missing command_type")
    void rejects_missing_command_type() {
        UserCommandMessage message =
            new UserCommandMessage(COMMAND_ID, null, "USER", OCCURRED_AT, null, null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command_type");
    }

    @Test
    @DisplayName("rejects a missing command_id")
    void rejects_missing_command_id() {
        UserCommandMessage message =
            new UserCommandMessage(null, "REPLAN_AGENDA", "USER", OCCURRED_AT, null, null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command_id");
    }

    @Test
    @DisplayName("rejects a missing occurred_at")
    void rejects_missing_occurred_at() {
        UserCommandMessage message =
            new UserCommandMessage(COMMAND_ID, "REPLAN_AGENDA", "USER", null, null, null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("occurred_at");
    }

    @Test
    @DisplayName("rejects SLEEP_SCORE without a payload")
    void rejects_sleep_score_without_payload() {
        UserCommandMessage message =
            new UserCommandMessage(COMMAND_ID, "SLEEP_SCORE", "USER", OCCURRED_AT, null, null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload.score");
    }

    @Test
    @DisplayName("rejects SLEEP_SCORE without a date")
    void rejects_sleep_score_without_date() {
        UserCommandMessage message = new UserCommandMessage(
            COMMAND_ID, "SLEEP_SCORE", "USER", OCCURRED_AT,
            new UserCommandMessage.SleepScorePayload(85, null), null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("date");
    }

    @ParameterizedTest(name = "score {0} is out of [0, 100]")
    @ValueSource(ints = {-1, 101, 150})
    @DisplayName("rejects a score outside [0, 100]")
    void rejects_out_of_range_score(int score) {
        UserCommandMessage message = new UserCommandMessage(
            COMMAND_ID, "SLEEP_SCORE", "USER", OCCURRED_AT,
            new UserCommandMessage.SleepScorePayload(score, DATE), null);

        assertThatThrownBy(message::toDomain)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sleep score");
    }

    @ParameterizedTest(name = "score {0} is accepted")
    @ValueSource(ints = {0, 100})
    @DisplayName("accepts the [0, 100] boundary scores")
    void accepts_boundary_scores(int score) {
        UserCommandMessage message = new UserCommandMessage(
            COMMAND_ID, "SLEEP_SCORE", "USER", OCCURRED_AT,
            new UserCommandMessage.SleepScorePayload(score, DATE), null);

        UserCommand command = message.toDomain();

        assertThat(command.sleepScore().score()).isEqualTo(score);
    }

    private String readFixture(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s on the test classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
