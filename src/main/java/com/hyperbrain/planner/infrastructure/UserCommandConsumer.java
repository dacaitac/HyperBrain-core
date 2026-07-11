package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.application.UserCommandService;
import com.hyperbrain.planner.domain.model.UserCommand;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SQS adapter for the user-command channel (HU-01b slice 2): listens on
 * {@code user-commands.fifo}, deserializes and validates the {@code UserCommand}, then delegates
 * to {@link UserCommandService}. It holds no business logic — dedup by {@code command_id} and the
 * routing live in the application layer, mirroring the sync module's consumers.
 *
 * <p><b>Invalid commands are discarded, not retried.</b> A malformed body, an unknown
 * {@code command_type} or a score outside {@code [0, 100]} is a producer contract violation that
 * no redelivery can fix: it is logged at WARN and acknowledged so it never poisons the DLQ.
 * Failures past validation (DB down, etc.) propagate, so the message is redelivered and
 * eventually redriven to the DLQ.
 *
 * <p>Gated by {@code app.user-commands.consumer.enabled}. Unlike the sync consumers the gate
 * defaults to <b>off</b> ({@code matchIfMissing = false} plus an off default in
 * {@code application.yml}): a listener container fails at startup when its queue is missing, and
 * {@code user-commands.fifo} stays unprovisioned until the Infra rollout. Integration tests keep
 * it off for the same competing-listener reason as {@code app.sync.consumer.enabled}.
 */
@Component
@ConditionalOnProperty(name = "app.user-commands.consumer.enabled", havingValue = "true")
public class UserCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserCommandConsumer.class);

    private final ObjectMapper objectMapper;
    private final UserCommandService commandService;
    private final UUID defaultUserId;

    public UserCommandConsumer(
        ObjectMapper objectMapper,
        UserCommandService commandService,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.objectMapper = objectMapper;
        this.commandService = commandService;
        this.defaultUserId = defaultUserId;
    }

    @SqsListener("${spring.cloud.aws.sqs.queues.user-commands}")
    public void onMessage(String body) {
        UserCommand command;
        try {
            command = objectMapper.readValue(body, UserCommandMessage.class).toDomain();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Discarding invalid user command (acked, never retried): {}", ex.getMessage());
            return;
        }
        log.info("user command received: {} commandId={} occurredAt={}",
            command.type(), command.commandId(), command.occurredAt());
        commandService.handle(defaultUserId, command);
    }
}
