package com.hyperbrain.sync.domain.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port through which the Apple write-back projects the hour the Planner scheduled an executable
 * onto its EKReminder due date (core#50, Part C), without the {@code sync} module reaching into
 * {@code planner}. The reminder's due date becomes an <em>indirect projection</em> of the executable's
 * next {@code core_time_block} start — {@code core_executable.start_time} is never written (ADR-026 D3
 * stays intact), and no new event or contract is added: {@code AppleEventPropagator} simply re-reads
 * this hour when it builds the reminder.
 *
 * <p><b>Cross-module seam (for Daniel's review).</b> The reminder is written by
 * {@code AppleEventPropagator} in {@code sync}, but the hour it should show lives on
 * {@code core_time_block}, owned by {@code planner}. ArchUnit forbids {@code sync → planner}, so — exactly
 * like {@link PlannerBlockDeletionPort} — {@code sync} declares the capability it needs and
 * {@code planner.infrastructure} provides the JDBC adapter, keeping the compile edge
 * {@code planner → sync} and the ownership of {@code core_time_block} semantics inside {@code planner}.
 */
public interface ScheduledDueTimeProvider {

    /**
     * The instant the Planner currently has this executable scheduled to start — the start of its next
     * {@code PLANNED}/{@code PLANNER} {@code core_time_block} (as anchor or themed companion, ADR-027 D1).
     *
     * <p>Empty when the executable holds no live planner block (never scheduled, or its block was
     * de-scheduled): the caller then leaves the reminder's due date unset, so a de-scheduled task's
     * reminder returns to a placeholder rather than keeping a stale authored hour.
     *
     * @param executableId the {@code core_executable} whose scheduled hour to resolve; never null
     * @return the earliest planned block start for the executable, or empty when it has none
     */
    Optional<OffsetDateTime> scheduledStart(UUID executableId);
}
