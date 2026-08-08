package com.hyperbrain.planner.infrastructure.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperbrain.planner.domain.model.DeviceSleepSamples;
import com.hyperbrain.planner.domain.model.NormalizationStatus;
import com.hyperbrain.planner.domain.model.RawTelemetryRow;
import com.hyperbrain.planner.domain.port.out.RawTelemetryStore;
import com.hyperbrain.planner.domain.port.out.SleepDumpArchive;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Jackson-backed {@link SleepDumpArchive} over the raw-first landing zone ({@code context_event}). The
 * JSON serialization is why this lives in infrastructure while the port stays in the domain, mirroring
 * the sleep-record assembler.
 *
 * <p><b>The payload is written in the Shortcut's own shape</b> — {@code date} plus a {@code sample}
 * array of {@code stage}/{@code startDate}/{@code endDate}, with the local time strings verbatim,
 * narrow no-break spaces and all. Nothing is parsed, converted to a zone or turned into seconds here;
 * re-deriving a night later means reading this back through the parser exactly as the command did.
 * The only field the wire carried and this does not is the provider's {@code duration} string, which
 * the message boundary never mapped because seconds are re-derived from the two instants — it is
 * redundant with them by construction.
 *
 * <p><b>The event type is not the canonical one</b>, and that is load-bearing. The telemetry pipeline's
 * {@code APPLE_HEALTH/SLEEP_SESSION} payload is one already-aggregated session
 * ({@code start_time}/{@code end_time} plus per-stage seconds); this is a list of raw stage intervals.
 * Filing both under the same type would let a future re-normalizer hand one shape to the strategy
 * written for the other.
 */
@Component
class JacksonSleepDumpArchive implements SleepDumpArchive {

    /** Same provider vocabulary as the canonical pipeline: this is Apple Health data either way. */
    private static final String PROVIDER = SleepSessionNormalizationStrategy.PROVIDER;

    /** The raw stage dump, as distinct from the canonical aggregated {@code SLEEP_SESSION}. */
    private static final String EVENT_TYPE = SleepDumpArchive.EVENT_TYPE;

    /** Format version of the payload written below; bumped if the archived shape ever changes. */
    private static final String SCHEMA_VERSION = "v1";

    private final RawTelemetryStore rawTelemetryStore;
    private final ObjectMapper objectMapper;

    JacksonSleepDumpArchive(RawTelemetryStore rawTelemetryStore, ObjectMapper objectMapper) {
        this.rawTelemetryStore = rawTelemetryStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID archive(UUID userId, DeviceSleepSamples dump, LocalDate sleepDay,
                        OffsetDateTime occurredAt) {
        return rawTelemetryStore.upsertLatest(new RawTelemetryRow(
            userId, PROVIDER, EVENT_TYPE, SCHEMA_VERSION,
            payloadJson(dump), occurredAt, dedupKey(userId, sleepDay)));
    }

    @Override
    public void markInterpreted(UUID archivedDumpId) {
        rawTelemetryStore.markStatus(archivedDumpId, NormalizationStatus.NORMALIZED);
    }

    @Override
    public void markUnusable(UUID archivedDumpId) {
        rawTelemetryStore.markStatus(archivedDumpId, NormalizationStatus.ERROR);
    }

    /**
     * The archive key: one row per user and night. Unlike the canonical pipeline's content hash — which
     * answers "have I seen this exact payload" — this is a natural key, because the point is precisely
     * that the payload for a given night keeps changing and only the newest is worth keeping.
     */
    private static String dedupKey(UUID userId, LocalDate sleepDay) {
        return PROVIDER + ':' + EVENT_TYPE + ':' + userId + ':' + sleepDay;
    }

    /** The dump in the Shortcut's own shape, so it can be read back exactly as it was read forward. */
    private String payloadJson(DeviceSleepSamples dump) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("date", dump.capturedAt());
        ArrayNode samples = root.putArray("sample");
        for (DeviceSleepSamples.Sample sample : dump.samples()) {
            ObjectNode node = samples.addObject();
            node.put("stage", sample.stage());
            node.put("startDate", sample.start());
            node.put("endDate", sample.end());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            // The node is built from strings only; serialization cannot fail.
            throw new IllegalStateException("Unserializable sleep dump node", ex);
        }
    }
}
