package com.hyperbrain.planner.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.application.AgendaMaterializationService;
import com.hyperbrain.planner.domain.model.DailyAgendaRequestedEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The single consumer that materializes the daily agenda (HU-01c H2). Listens on {@code ia-jobs} for
 * {@link DailyAgendaRequestedEvent} and delegates to {@link AgendaMaterializationService}, which owns
 * generation, persistence, idempotency and the empty-day proposal. It holds no business logic — the
 * envelope parsing and the visibility guard are its only concerns, mirroring the other SQS adapters.
 *
 * <p><b>Shared queue.</b> {@code ia-jobs} also carries cognitive jobs (future). This consumer acts
 * only on {@code DailyAgendaRequestedEvent} and acknowledges any other {@code event_type} with a
 * debug log; until the cognitive consumer lands, agenda jobs are the only traffic. A malformed
 * agenda payload is a producer contract violation no redelivery can fix — logged at WARN and acked so
 * it never poisons the DLQ. Failures past parsing (DB down, transport) propagate, so the job is
 * redelivered and eventually redriven to the {@code ia-jobs} DLQ — never silently lost.
 *
 * <p><b>Visibility (heartbeat seam).</b> The listener factory default is 30 s (see
 * {@code SqsConsumerProperties}); the deterministic floor is well within that today, but the LLM tier
 * (H3–H5) will not be. On receipt the consumer extends visibility once to the configured margin,
 * matching the {@code ia-jobs} queue sizing (120 s). When the LLM lands this one-shot extension
 * becomes a periodic {@code changeTo} heartbeat driven while the model runs; the seam and its knob
 * are in place now so that change touches only this method.
 *
 * <p>Gated by {@code app.planner.agenda-job-consumer.enabled} (default <b>off</b>,
 * {@code matchIfMissing = false}): the single-owner path stays dark until Daniel flips the cut-over in
 * prod, and integration tests keep it off unless they exercise the async cycle (competing-listener
 * gotcha, as with {@code app.sync.consumer.enabled}).
 */
@Component
@ConditionalOnProperty(name = "app.planner.agenda-job-consumer.enabled", havingValue = "true")
public class AgendaJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgendaJobConsumer.class);

    private final ObjectMapper objectMapper;
    private final AgendaMaterializationService materializationService;
    private final int visibilityTimeoutSeconds;

    public AgendaJobConsumer(
        ObjectMapper objectMapper,
        AgendaMaterializationService materializationService,
        @Value("${app.planner.agenda-job-consumer.visibility-timeout-seconds:120}")
            int visibilityTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.materializationService = materializationService;
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
    }

    @SqsListener("${spring.cloud.aws.sqs.queues.ia-jobs}")
    public void onMessage(String body, Visibility visibility) {
        JsonNode root = parse(body);
        String eventType = root.path("event_type").asText();
        if (!DailyAgendaRequestedEvent.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring non-agenda ia-job event_type={} (acked)", eventType);
            return;
        }
        DailyAgendaRequestedEvent job = toDomain(root);
        if (job == null) {
            return;
        }
        // Heartbeat seam: give the run headroom beyond the 30 s listener default before work begins.
        visibility.changeTo(visibilityTimeoutSeconds);
        log.info("agenda job received: user={} day={} fromNow={} T={}",
            job.userId(), job.agendaDate(), job.fromNow(), job.referenceInstant());
        materializationService.materialize(job);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            // A body that is not even JSON cannot be routed; drop it rather than loop the DLQ.
            log.warn("Discarding non-JSON ia-job message (acked): {}", ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private DailyAgendaRequestedEvent toDomain(JsonNode root) {
        try {
            return objectMapper.treeToValue(root.path("payload"), DailyAgendaRequestedMessage.class)
                .toDomain();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Discarding invalid agenda job (acked, never retried): {}", ex.getMessage());
            return null;
        }
    }
}
