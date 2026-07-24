package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.planner.domain.model.NormalizationStatus;
import com.hyperbrain.planner.domain.model.RawTelemetryRow;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.planner.domain.port.out.RawTelemetryStore;
import com.hyperbrain.shared.messaging.ProcessedMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Raw-first telemetry ingestion (ADR-016 #59). Runs the fixed ADR-016 order in one transaction,
 * mirroring {@code UserCommandService}:
 * <ol>
 *   <li><b>Dedup</b> the SQS at-least-once redelivery by {@code event_id} via {@code processed_message}.</li>
 *   <li><b>Persist the envelope RAW</b> in {@code context_event} (payload verbatim, PENDING), keyed by
 *       the semantic {@code dedup_key}; a {@code dedup_key} collision means a semantic duplicate and is
 *       acked. The raw persist never fails on payload format — the payload is opaque JSONB.</li>
 *   <li><b>Normalize</b> via the {@link TelemetryNormalizer} and write the resulting status back onto
 *       the raw row: NORMALIZED / SKIPPED (no strategy) / ERROR (tolerant-reader failure). All three
 *       are acked; only a genuine DB fault propagates, rolling the ingest back for redelivery → DLQ.</li>
 * </ol>
 *
 * <p>Because the whole method is one transaction, a normalizer strategy that fails <em>after</em>
 * touching the DB poisons it and the subsequent status write throws — correctly rolling back and
 * redelivering (a real persistence fault), whereas a pure tolerant-reader failure (the common case)
 * leaves the transaction clean so the raw row commits with status ERROR (kept for reprocessing).
 */
@Service
public class TelemetryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionService.class);

    private static final String DEDUP_PREFIX = "telemetry:";
    private static final String EMPTY_PAYLOAD = "{}";

    private final ProcessedMessageStore processedMessageStore;
    private final RawTelemetryStore rawTelemetryStore;
    private final TelemetryNormalizer normalizer;
    private final PlannerStateRepository plannerStateRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    TelemetryIngestionService(
        ProcessedMessageStore processedMessageStore,
        RawTelemetryStore rawTelemetryStore,
        TelemetryNormalizer normalizer,
        PlannerStateRepository plannerStateRepository,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.processedMessageStore = processedMessageStore;
        this.rawTelemetryStore = rawTelemetryStore;
        this.normalizer = normalizer;
        this.plannerStateRepository = plannerStateRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Ingests one telemetry envelope for a user.
     *
     * @param userId   the owning user (single-user MVP default); never null
     * @param envelope the parsed envelope; never null
     */
    @Transactional
    public void ingest(UUID userId, TelemetryEnvelope envelope) {
        String classifier = envelope.provider() + "/" + envelope.eventType();
        if (envelope.eventId() != null
            && !processedMessageStore.markProcessed(DEDUP_PREFIX + envelope.eventId(), classifier)) {
            log.warn("Duplicate telemetry event_id {} ({}) ignored", envelope.eventId(), classifier);
            return;
        }

        String payloadJson = serialize(envelope.payload());
        String dedupKey = TelemetryDedupKey.of(envelope.provider(), envelope.eventType(), payloadJson);
        OffsetDateTime occurredAt = firstNonNull(envelope.occurredAt(), envelope.collectedAt());
        RawTelemetryRow row = new RawTelemetryRow(userId, envelope.provider(), envelope.eventType(),
            envelope.schemaVersion(), payloadJson, occurredAt, dedupKey);

        Optional<UUID> inserted = rawTelemetryStore.insertPending(row);
        if (inserted.isEmpty()) {
            log.info("Duplicate telemetry envelope ({}) skipped by dedup_key", classifier);
            return;
        }
        UUID contextEventId = inserted.get();

        ZoneId zone = plannerStateRepository.loadUserZone(userId);
        OffsetDateTime collectedAt = firstNonNull(envelope.collectedAt(), OffsetDateTime.now(clock));
        TelemetryRecord record = new TelemetryRecord(userId, contextEventId, envelope.provider(),
            envelope.eventType(), envelope.schemaVersion(), occurredAt, collectedAt, envelope.payload(), zone);

        NormalizationStatus status = normalizer.normalize(record);
        rawTelemetryStore.markStatus(contextEventId, status);
        log.info("Telemetry ingested ({}) status={} contextEventId={}", classifier, status, contextEventId);
    }

    /** Serializes the opaque payload to canonical JSON text; an absent payload lands as an empty object. */
    private String serialize(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return EMPTY_PAYLOAD;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Re-serializing an already-parsed node cannot fail; guard defensively.
            throw new IllegalStateException("Unserializable telemetry payload", ex);
        }
    }

    private OffsetDateTime firstNonNull(OffsetDateTime first, OffsetDateTime second) {
        if (first != null) {
            return first;
        }
        return second != null ? second : OffsetDateTime.now(clock);
    }
}
