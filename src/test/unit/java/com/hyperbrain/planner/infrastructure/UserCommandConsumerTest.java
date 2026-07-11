package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.application.UserCommandService;
import com.hyperbrain.planner.domain.model.UserCommand;
import com.hyperbrain.planner.domain.model.UserCommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("UserCommandConsumer — discard-on-invalid semantics")
class UserCommandConsumerTest {

    private static final UUID DEFAULT_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UserCommandService commandService;
    private UserCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        commandService = mock(UserCommandService.class);
        consumer = new UserCommandConsumer(
            new ObjectMapper().findAndRegisterModules(), commandService, DEFAULT_USER);
    }

    @Test
    @DisplayName("a valid command is delegated to the service under the default user")
    void valid_command_is_delegated() {
        String body = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "REPLAN_AGENDA",
              "origin": "USER",
              "occurred_at": "2026-07-10T12:00:00Z",
              "payload": null
            }
            """;

        consumer.onMessage(body);

        ArgumentCaptor<UserCommand> captor = ArgumentCaptor.forClass(UserCommand.class);
        verify(commandService).handle(eq(DEFAULT_USER), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(UserCommandType.REPLAN_AGENDA);
        assertThat(captor.getValue().commandId())
            .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    @DisplayName("malformed JSON is discarded (ack) without reaching the service or throwing")
    void malformed_json_is_discarded() {
        assertThatCode(() -> consumer.onMessage("{not json"))
            .doesNotThrowAnyException();

        verifyNoInteractions(commandService);
    }

    @Test
    @DisplayName("an out-of-range score is discarded (ack) without reaching the service or throwing")
    void out_of_range_score_is_discarded() {
        String body = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "SLEEP_SCORE",
              "origin": "USER",
              "occurred_at": "2026-07-10T12:00:00Z",
              "payload": { "score": 150, "date": "2026-07-10" }
            }
            """;

        assertThatCode(() -> consumer.onMessage(body))
            .doesNotThrowAnyException();

        verifyNoInteractions(commandService);
    }

    @Test
    @DisplayName("an unknown command_type is discarded (ack) without reaching the service or throwing")
    void unknown_command_type_is_discarded() {
        String body = """
            {
              "command_id": "11111111-1111-1111-1111-111111111111",
              "command_type": "MAKE_COFFEE",
              "origin": "USER",
              "occurred_at": "2026-07-10T12:00:00Z",
              "payload": null
            }
            """;

        assertThatCode(() -> consumer.onMessage(body))
            .doesNotThrowAnyException();

        verifyNoInteractions(commandService);
    }
}
