package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SQS adapter for the raw-first telemetry channel (ADR-016 #59): listens on the Standard
 * {@code telemetry-events} queue, parses the {@code TelemetryEnvelope} and delegates to
 * {@link TelemetryIngestionService}. Thin, like the sync/user-command consumers: dedup, raw persist
 * and normalization live in the service.
 *
 * <p><b>DLQ discipline (ADR-016).</b> The DLQ is reserved for real transport/persistence faults. A
 * body that is <b>not valid JSON</b> cannot be landed raw, so it throws {@link TelemetryProcessingException}
 * and is redelivered → DLQ. Everything that parses as JSON is landed raw and acked: an unknown
 * {@code (provider, event_type)} becomes SKIPPED, and a payload the normalizer cannot interpret becomes
 * ERROR — neither poisons the DLQ. A downstream DB fault during normalization propagates and is
 * redelivered (transient) → DLQ (persistent).
 *
 * <p>Gated by {@code app.telemetry.consumer.enabled}, default <b>off</b> ({@code matchIfMissing = false}
 * plus an off default in {@code application.yml}): the queue is not provisioned until the Infra rollout,
 * and a missing queue fails the listener container at startup. Integration tests keep it off for the
 * same competing-listener reason as the other consumers, enabling it per-test.
 *
 * <p>Single-user MVP: every envelope is owned by the default user (the collector does not carry a user id).
 */
@Component
@ConditionalOnProperty(name = "app.telemetry.consumer.enabled", havingValue = "true")
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final ObjectMapper objectMapper;
    private final TelemetryIngestionService ingestionService;
    private final UUID defaultUserId;

    public TelemetryConsumer(
        ObjectMapper objectMapper,
        TelemetryIngestionService ingestionService,
        @Value("${app.sync.default-user-id}") UUID defaultUserId
    ) {
        this.objectMapper = objectMapper;
        this.ingestionService = ingestionService;
        this.defaultUserId = defaultUserId;
    }

    @SqsListener("${spring.cloud.aws.sqs.queues.telemetry-events}")
    public void onMessage(String body) {
        JsonNode root = parse(body);
        TelemetryEnvelope envelope = TelemetryEnvelope.fromTree(root);
        log.info("telemetry event received: provider={} eventType={} eventId={}",
            envelope.provider(), envelope.eventType(), envelope.eventId());
        ingestionService.ingest(defaultUserId, envelope);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || root.isMissingNode() || !root.isObject()) {
                throw new TelemetryProcessingException("Malformed telemetry envelope: not a JSON object");
            }
            return root;
        } catch (JsonProcessingException ex) {
            throw new TelemetryProcessingException("Malformed telemetry envelope: not valid JSON", ex);
        }
    }
}
