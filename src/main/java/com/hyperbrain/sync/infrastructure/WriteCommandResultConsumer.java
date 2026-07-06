package com.hyperbrain.sync.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.application.WriteCommandResultService;
import com.hyperbrain.sync.domain.EventProcessingException;
import com.hyperbrain.sync.domain.model.WriteCommandResult;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SQS adapter for the write-back result channel: listens on {@code apple-commands-results.fifo},
 * deserializes and validates the {@code WriteCommandResult}, then delegates to
 * {@link WriteCommandResultService}. On a malformed message it throws so the message is
 * redelivered and eventually redriven to the DLQ.
 *
 * <p>Gated by {@code app.sync.results-consumer.enabled} (default on); integration tests that
 * don't exercise the results channel switch it off so only one listener competes for the queue.
 */
@Component
@ConditionalOnProperty(name = "app.sync.results-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class WriteCommandResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(WriteCommandResultConsumer.class);

    private final ObjectMapper objectMapper;
    private final WriteCommandResultService resultService;

    public WriteCommandResultConsumer(ObjectMapper objectMapper, WriteCommandResultService resultService) {
        this.objectMapper = objectMapper;
        this.resultService = resultService;
    }

    @SqsListener("${spring.cloud.aws.sqs.queues.apple-commands-results}")
    public void onMessage(String body) {
        WriteCommandResult result = deserialize(body);
        validate(result);
        log.info("write command result received: {} {} entityId={}",
            result.commandId(), result.status(), result.entityId());
        resultService.handle(result);
    }

    private WriteCommandResult deserialize(String body) {
        try {
            return objectMapper.readValue(body, WriteCommandResultMessage.class).toDomain();
        } catch (JsonProcessingException ex) {
            throw new EventProcessingException("Malformed WriteCommandResult payload", ex);
        }
    }

    private void validate(WriteCommandResult result) {
        if (result.commandId() == null || result.status() == null || result.operation() == null) {
            throw new EventProcessingException("WriteCommandResult missing required fields: " + result);
        }
    }
}
