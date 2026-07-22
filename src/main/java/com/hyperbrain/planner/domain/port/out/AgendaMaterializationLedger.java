package com.hyperbrain.planner.domain.port.out;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Out-port guarding the single-owner materialization against duplicate work (HU-01c H2). SQS
 * delivers a {@code DailyAgendaRequestedEvent} at-least-once, and distinct triggers may request the
 * same day; the ledger makes a materialization idempotent by the <em>input</em> that produced it,
 * not by the message identity.
 *
 * <p>The key is {@code (user_id, agenda_date, input_hash)} where {@code input_hash} is a stable
 * digest of the generator's input state (ranked tasks + rank, remaining effort, walls/AGENDA, WIG,
 * sleep window, energy, and the quantized planning frontier) — see {@code AgendaInputHasher}. Two
 * consequences fall out of this by design:
 * <ul>
 *   <li>a redelivery of the same job reconstructs the same input, hashes to the same key, and is a
 *       no-op — the day's blocks (and their EKEvents) are never duplicated;</li>
 *   <li>a genuine replan whose state moved (a task completed, a later temporal frontier) hashes to a
 *       new key and materializes; a replan from an identical state is deduplicated.</li>
 * </ul>
 */
public interface AgendaMaterializationLedger {

    /**
     * Atomically claims the {@code (userId, agendaDate, inputHash)} slot
     * ({@code INSERT ... ON CONFLICT DO NOTHING}). Must run inside the caller's materialization
     * transaction so the claim commits together with the persisted blocks and the staged outbox
     * event — a rollback releases the claim and lets SQS redeliver.
     *
     * @param userId     the user whose day is being materialized; never null
     * @param agendaDate the calendar day being materialized; never null
     * @param inputHash  the stable digest of the generator input; never blank
     * @return {@code true} when this call claimed the slot (first time this input is seen for the
     *         day); {@code false} when it was already claimed (duplicate — caller must skip)
     */
    boolean claim(UUID userId, LocalDate agendaDate, String inputHash);
}
