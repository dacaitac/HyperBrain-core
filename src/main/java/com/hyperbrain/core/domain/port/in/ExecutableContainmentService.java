package com.hyperbrain.core.domain.port.in;

import com.hyperbrain.core.domain.model.ContainmentOutcome;
import com.hyperbrain.core.domain.model.ContainmentRequest;
import com.hyperbrain.core.domain.model.ReleaseCause;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Published interface of the {@code core} module for <b>containment</b> (ADR-040 D11): the operation
 * through which any module outside {@code core} puts executables inside a {@code TIME_BLOCK}, or takes
 * them out of one, with the containment invariants applied.
 *
 * <p><b>Why it exists.</b> The two rules that govern containment — which types may go inside a block
 * (DR-11) and the hard copy of the container's date and cycle onto its members (DR-10) — used to run
 * only on the ingestion chain. They were therefore properties of the synchronisation channel, not of
 * the model: assigning a container from anywhere else copied no date, and the Planner could have
 * written containment with no rule noticing. Two alternatives were weighed and rejected — writing
 * straight through {@code core}'s persistence port (breaks the module isolation ArchUnit verifies) and
 * re-entering the ingestion chain (would need a fabricated status and origin, and would drag foreign
 * rules into the planning transaction). This is the third way: the rules became pure policies, and
 * this is their second caller.
 *
 * <p><b>Transaction.</b> Every operation participates in the caller's transaction and requires one —
 * the Planner's plan and the containment it writes commit together or not at all, and the outbox rows
 * ride along (Transactional Outbox).
 *
 * <p><b>Noise.</b> An event is emitted only when a row actually changed (ADR-040 D17). Without that,
 * every replan would flood the outbox with rows that say nothing, and in Notion that drowns the
 * last-edited mark — the very evidence that the user's authority exists.
 */
public interface ExecutableContainmentService {

    /**
     * Puts a set of executables inside a block, in one transaction: validates eligibility, assigns the
     * containment columns, settles the hard copy of the container's date and cycle onto the whole
     * contained chain, and emits one update event per row that genuinely changed.
     *
     * <p>Members that fail eligibility are <b>skipped and reported</b>, never persisted and never
     * thrown on: one ineligible member must not abort a whole day's plan.
     *
     * @param blockId the {@code TIME_BLOCK} executable that will hold them; never null
     * @param members the members to contain, with their quota and order inside the block; never null,
     *                may be empty (then the call only re-settles the hard copy)
     * @return what the call actually changed; never null
     * @throws IllegalArgumentException when {@code blockId} is not a {@code TIME_BLOCK} executable, or
     *                                  when a member is itself a {@code TIME_BLOCK} (a block is not a
     *                                  member of a block)
     */
    ContainmentOutcome contain(UUID blockId, List<ContainmentRequest> members);

    /**
     * Releases every member of a block — the first half of ADR-040 D10, "let go first, delete after".
     *
     * <p>Deleting a block used to let the database detach its members on its own
     * ({@code ON DELETE SET NULL}). That mutates rows without passing through the domain and without
     * emitting a single event, so the calendar and Notion keep the hour of a block that no longer
     * exists — silent mirror corruption that never self-corrects because nobody finds out. The
     * automatic detach stays as a safety net; it is never the mechanism.
     *
     * <p>What the released member keeps depends on <b>who</b> released it ({@link ReleaseCause}).
     *
     * @param blockId the block being emptied; never null
     * @param cause   who is releasing, which decides what happens to the copied hour; never null
     * @param zone    the timezone the midnight placeholder is resolved in; never null
     * @return the ids of the members this call actually released; never null, may be empty
     */
    List<UUID> releaseMembers(UUID blockId, ReleaseCause cause, ZoneId zone);
}
