package com.hyperbrain.sync.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of a {@code core_cycle} row, covering the attributes propagated to the Notion
 * Cycles database (HU-10, ADR-011: Cycles sync is fully bidirectional).
 *
 * @param id        surrogate key of the cycle
 * @param userId    owning user
 * @param name      cycle name
 * @param type      cycle type ({@code MCI}, {@code ROUTINE}, {@code PHASE})
 * @param status    lifecycle status ({@code ACTIVE}, {@code COMPLETED})
 * @param startDate optional start date
 * @param endDate   optional end date
 */
public record CycleSnapshot(
    UUID id,
    UUID userId,
    String name,
    String type,
    String status,
    LocalDate startDate,
    LocalDate endDate
) {
}
