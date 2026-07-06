package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SQS adapter for {@link WriteCommandPublisher}: publishes to {@code apple-commands.fifo} with
 * {@code MessageGroupId} = the caller's group key and {@code MessageDeduplicationId} =
 * {@code command_id} (ContentBasedDeduplication is off, TD-03).
 *
 * <p>Group keys longer than the SQS FIFO limit (128 chars — possible with composite
 * Exchange/Outlook EventKit identifiers) are replaced by their SHA-256 hex, mirroring
 * SentinelAPI's publisher so per-entity ordering stays deterministic on both directions.
 */
@Component
public class SqsWriteCommandPublisher implements WriteCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsWriteCommandPublisher.class);

    private static final int MAX_GROUP_ID_LENGTH = 128;

    private final SqsTemplate sqsTemplate;
    private final WriteCommandWireMapper wireMapper;
    private final String appleCommandsQueue;

    public SqsWriteCommandPublisher(
        SqsTemplate sqsTemplate,
        WriteCommandWireMapper wireMapper,
        @Value("${spring.cloud.aws.sqs.queues.apple-commands}") String appleCommandsQueue
    ) {
        this.sqsTemplate = sqsTemplate;
        this.wireMapper = wireMapper;
        this.appleCommandsQueue = appleCommandsQueue;
    }

    @Override
    public void publish(WriteCommand command, String groupKey) {
        String body = wireMapper.toWireJson(command);
        String groupId = groupId(groupKey);
        sqsTemplate.send(to -> to
            .queue(appleCommandsQueue)
            .messageGroupId(groupId)
            .messageDeduplicationId(command.commandId().toString())
            .payload(body));
        log.debug("Published WriteCommand {} to {} (group {})",
            command.commandId(), appleCommandsQueue, groupId);
    }

    private static String groupId(String key) {
        if (key.length() <= MAX_GROUP_ID_LENGTH) {
            return key;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JVM specification; this never happens.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
