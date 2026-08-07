package com.hyperbrain.core.application;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.model.ContainmentOutcome;
import com.hyperbrain.core.domain.model.ContainmentPolicy;
import com.hyperbrain.core.domain.model.ContainmentRequest;
import com.hyperbrain.core.domain.model.ReleaseCause;
import com.hyperbrain.core.domain.port.in.ExecutableContainmentService;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/*
 * Design pattern: Application Service implementing a published in-port, delegating its criteria to
 * pure policies (ContainmentPolicy) shared with the ingestion chain.
 * Reason: ADR-040 D11 — one implementation of each containment rule, two callers. This class holds
 * the orchestration (order of writes, transaction, outbox fan-out) and no criterion of its own.
 */

/**
 * The {@code core}-side implementation of {@link ExecutableContainmentService}: the operation the
 * Planner invokes when it plans, so containment written from outside the synchronisation channel
 * carries the very same invariants an inbound Notion edit does.
 *
 * <p><b>Order of writes in {@link #contain}</b>, and it is deliberate:
 * <ol>
 *   <li>read the offered members and refuse the ineligible ones (DR-11), reporting instead of
 *       throwing — one bad member must never abort a whole day;</li>
 *   <li>assign the containment columns of the eligible ones, each write idempotent by value;</li>
 *   <li>settle the hard copy (DR-10) with a <b>single</b> pass over the container's transitive
 *       closure, rather than one pass per member — the copy is a property of the container, so it is
 *       computed once for the whole block;</li>
 *   <li>emit exactly one event per row that genuinely moved, containment and copy de-duplicated.</li>
 * </ol>
 *
 * <p><b>{@code MANDATORY} propagation</b> is the invariant that keeps this honest: there is no path
 * on which containment commits without the plan that decided it, and none on which the outbox row
 * commits without the write it announces.
 */
@Service
public class CoreExecutableContainmentService implements ExecutableContainmentService {

    private static final Logger log = LoggerFactory.getLogger(CoreExecutableContainmentService.class);

    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;

    public CoreExecutableContainmentService(ExecutableStateRepository stateRepo,
                                            OutboxRepository outboxRepo) {
        this.stateRepo = stateRepo;
        this.outboxRepo = outboxRepo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ContainmentOutcome contain(UUID blockId, List<ContainmentRequest> members) {
        if (blockId == null || members == null) {
            throw new IllegalArgumentException("blockId and members must not be null");
        }
        ContainerSchedule container = requireBlock(blockId);
        Map<UUID, ExecutableSnapshot> offered = byId(stateRepo.findAllById(
            members.stream().map(ContainmentRequest::memberId).toList()));

        List<UUID> contained = new ArrayList<>();
        List<UUID> rejected = new ArrayList<>();
        for (ContainmentRequest request : members) {
            ExecutableSnapshot member = offered.get(request.memberId());
            if (member == null) {
                rejected.add(request.memberId());
                log.warn("Executable {} offered to block {} does not exist; containment skipped",
                    request.memberId(), blockId);
                continue;
            }
            if (ContainmentPolicy.TIME_BLOCK.equals(member.type())) {
                throw new IllegalArgumentException(
                    "a TIME_BLOCK is never a member of a block: " + member.id());
            }
            if (!ContainmentPolicy.containable(member.type())) {
                rejected.add(member.id());
                log.info("Executable {} of type {} cannot be contained (it owns its own window); "
                    + "left outside block {}", member.id(), member.type(), blockId);
                continue;
            }
            if (stateRepo.assignContainer(
                member.id(), blockId, request.plannedMinutes(), request.ord())) {
                contained.add(member.id());
            }
        }

        List<UUID> recopied = stateRepo.copyScheduleToContained(
            blockId, container.startTime(), container.endTime(), container.cycleId());

        Set<UUID> toAnnounce = new LinkedHashSet<>(contained);
        toAnnounce.addAll(recopied);
        toAnnounce.forEach(id -> outboxRepo.append(ExecutableOutboxEvents.updated(id)));

        ContainmentOutcome outcome = new ContainmentOutcome(contained, recopied, rejected);
        if (!outcome.isNoOp()) {
            log.info("Block {} contained {} member(s) and re-copied its schedule onto {} row(s); "
                + "{} rejected", blockId, contained.size(), recopied.size(), rejected.size());
        }
        return outcome;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> releaseMembers(UUID blockId, ReleaseCause cause, ZoneId zone) {
        if (blockId == null || cause == null || zone == null) {
            throw new IllegalArgumentException("blockId, cause and zone must not be null");
        }
        List<UUID> released = new ArrayList<>();
        for (ExecutableSnapshot member : stateRepo.findContainedBy(blockId)) {
            if (!stateRepo.clearContainer(member.id())) {
                continue;
            }
            boolean hourReset = cause.resetsHour() && returnHourToPlaceholder(member, zone);
            outboxRepo.append(ExecutableOutboxEvents.updated(member.id()));
            released.add(member.id());
            log.debug("Executable {} released from block {} ({}; hour reset: {})",
                member.id(), blockId, cause, hourReset);
        }
        if (!released.isEmpty()) {
            log.info("Block {} released {} member(s) before withdrawal ({})",
                blockId, released.size(), cause);
        }
        return released;
    }

    /**
     * Returns a released member's date to the midnight placeholder of the day it already stood on
     * (ADR-040 D10, planner withdrawal): the day survives, the hour dies. A member with no date is
     * left alone — there is nothing to demote.
     *
     * <p>The cycle is deliberately <b>not</b> cleared. There is no record of which cycle was the
     * container's copy and which was the member's own, and a null cycle carries no signal: clearing it
     * would destroy alignment data on every daily plan, which is the same reason the hard copy never
     * propagates a null cycle downwards.
     */
    private boolean returnHourToPlaceholder(ExecutableSnapshot member, ZoneId zone) {
        if (member.startTime() == null) {
            return false;
        }
        OffsetDateTime midnight = member.startTime()
            .atZoneSameInstant(zone).toLocalDate()
            .atStartOfDay(zone).toOffsetDateTime();
        return stateRepo.reschedule(member.id(), midnight, null);
    }

    /**
     * Resolves the container's own schedule and asserts it really is a block. Passing a non-block as a
     * container would let the caller hard-copy an arbitrary row's window onto a set of tasks, which is
     * a corruption no rule downstream could detect.
     */
    private ContainerSchedule requireBlock(UUID blockId) {
        Optional<ExecutableSnapshot> block = stateRepo.findAllById(List.of(blockId)).stream().findFirst();
        if (block.isEmpty() || !ContainmentPolicy.TIME_BLOCK.equals(block.get().type())) {
            throw new IllegalArgumentException("container " + blockId + " is not a TIME_BLOCK executable");
        }
        ExecutableSnapshot row = block.get();
        return new ContainerSchedule(row.id(), row.name(), row.startTime(), row.endTime(), row.cycleId());
    }

    private static Map<UUID, ExecutableSnapshot> byId(List<ExecutableSnapshot> snapshots) {
        Map<UUID, ExecutableSnapshot> index = new LinkedHashMap<>();
        snapshots.forEach(snapshot -> index.put(snapshot.id(), snapshot));
        return index;
    }
}
