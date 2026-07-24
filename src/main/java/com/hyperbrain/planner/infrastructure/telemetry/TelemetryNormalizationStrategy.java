package com.hyperbrain.planner.infrastructure.telemetry;

/**
 * A normalization strategy for one {@code (provider, event_type)} pair: a tolerant reader that
 * projects a raw telemetry payload into its typed {@code tel_*} table.
 *
 * <p>Design pattern: Strategy — {@link TelemetryNormalizer} selects the implementation by
 * {@code (provider, event_type)}, so adding a provider/type is adding a {@code @Component}, never
 * editing the dispatcher.
 *
 * <p><b>Parse-then-write contract.</b> An implementation must parse and validate the whole payload
 * <em>before</em> issuing any DB write. A tolerant-reader failure then throws before any SQL runs, so
 * the surrounding transaction stays clean and the raw row can be marked ERROR (kept for reprocessing).
 * Only a genuine DB failure on the final write poisons the transaction — which correctly rolls the
 * ingest back for redelivery instead of masking a persistence fault as a normalization ERROR.
 */
interface TelemetryNormalizationStrategy {

    /** The provider this strategy handles (e.g. {@code APPLE_HEALTH}). */
    String provider();

    /** The event type within the provider this strategy handles (e.g. {@code SLEEP_SESSION}). */
    String eventType();

    /**
     * Projects the raw telemetry record into its typed table.
     *
     * @param record the landed raw envelope; never null
     * @throws RuntimeException when the payload cannot be interpreted (tolerant-reader failure) — the
     *                          normalizer captures this and marks the raw row ERROR
     */
    void normalize(TelemetryRecord record);
}
