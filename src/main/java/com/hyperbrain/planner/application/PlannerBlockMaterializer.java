package com.hyperbrain.planner.application;

import com.hyperbrain.core.domain.model.ContainmentRequest;
import com.hyperbrain.core.domain.port.in.ExecutableContainmentService;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.PlannerBlockIdentity;
import com.hyperbrain.planner.domain.model.PlannerBlockRow;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/*
 * Design pattern: Application-layer coordinator, one responsibility — turning an accepted plan into
 * persisted blocks.
 * Reason: the day's generation (assemble state → floor → validate) and the day's materialization
 * (identity → withdraw → upsert → contain) are two things that change for different reasons, and the
 * second one is where ADR-040 D10's order-of-operations invariant lives. Keeping it in its own class
 * means that invariant is readable in one place instead of buried inside a 600-line service.
 */

/**
 * Turns an accepted plan into persisted blocks on the deployed model (ADR-039: a block IS a
 * {@code core_executable} of type {@code TIME_BLOCK}), preserving identity so a regeneration converges
 * instead of duplicating calendar events (#15).
 *
 * <p><b>The order of the three steps is the invariant</b>, and it is not an implementation detail:
 * <ol>
 *   <li><b>Withdraw first</b> (ADR-040 D10). Every block the plan no longer wants goes through core's
 *       published withdrawal, which lets each member go with its own event <em>before</em> the block
 *       row is deleted. Doing it first is also what frees those members to be re-contained below
 *       without ever holding two containments at once.</li>
 *   <li><b>Then the block rows.</b> Insert the new windows, re-time the continued ones, and write
 *       nothing at all for a block that did not move — a replan that moved two blocks announces two,
 *       not thirty (ADR-040 D17).</li>
 *   <li><b>Then the membership</b>, again through core's published operation, so the eligibility rule
 *       and the hard copy of date and cycle apply to a plan exactly as they apply to an inbound Notion
 *       edit. That is the whole of D11: containment stopped being a property of the synchronisation
 *       channel and became a property of the model.</li>
 * </ol>
 *
 * <p><b>{@code MANDATORY} propagation.</b> There is no path on which a block commits without the plan
 * that decided it, nor on which an outbox row commits without the write it announces.
 */
@Component
public class PlannerBlockMaterializer {

    private static final Logger log = LoggerFactory.getLogger(PlannerBlockMaterializer.class);

    /** The label a block with no template slot and no theme carries until the LLM names it (D6). */
    public static final String DEFAULT_BLOCK_NAME = "Focus window";

    private final PlannerStateRepository repository;
    private final ExecutableContainmentService containment;

    public PlannerBlockMaterializer(PlannerStateRepository repository,
                                    ExecutableContainmentService containment) {
        this.repository = repository;
        this.containment = containment;
    }

    /**
     * Materializes one day's accepted plan.
     *
     * @param userId    the owning user; never null
     * @param targetDay the calendar day being materialized; never null
     * @param zone      the user's timezone; never null
     * @param accepted  the blocks the plan wants for the day; never null, may be empty
     * @param notBefore the earliest start this run may still touch — a block that already began is
     *                  neither re-timed nor withdrawn, because the past is never rewritten; never null
     * @return the ids of the blocks actually withdrawn, so their calendar counterpart is deleted too;
     *         never null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> materialize(UUID userId, LocalDate targetDay, ZoneId zone,
                                  List<AgendaBlock> accepted, OffsetDateTime notBefore) {
        PlannerBlockIdentity.Reconciliation reconciliation = PlannerBlockIdentity.reconcile(
            accepted, repository.loadRegenerableBlocks(userId, targetDay, zone, notBefore));

        List<UUID> withdrawn = new ArrayList<>();
        for (UUID blockId : reconciliation.removedBlockIds()) {
            if (containment.withdrawBlock(blockId, zone)) {
                withdrawn.add(blockId);
            }
        }

        int moved = 0;
        for (PlannerBlockIdentity.IdentifiedBlock identified : reconciliation.identified()) {
            AgendaBlock block = identified.block();
            if (repository.upsertBlock(new PlannerBlockRow(
                identified.blockId(), userId, blockName(block), block.reason(),
                block.start(), block.end(), block.templateSlotId()))) {
                moved++;
            }
            containment.contain(identified.blockId(), containmentRequests(block));
        }

        if (moved > 0 || !withdrawn.isEmpty()) {
            log.info("Materialized {} of {} block(s) for {} (the rest had not moved) and withdrew {}",
                moved, reconciliation.identified().size(), targetDay, withdrawn.size());
        }
        return withdrawn;
    }

    /**
     * The name a freshly inserted block is born with. A block the plan continues keeps the name it
     * already has — the upsert never rewrites it — so the LLM's naming survives every replan
     * (ADR-040 D8). On the deterministic floor there is no theme yet (D6: the day comes out laid and
     * ordered, but neither grouped nor named), so the block borrows the label of its template slot and
     * the LLM replaces it when it runs.
     */
    private static String blockName(AgendaBlock block) {
        if (block.theme() != null) {
            return block.theme();
        }
        return block.templateSlotId() != null ? block.templateSlotId() : DEFAULT_BLOCK_NAME;
    }

    /** The block's membership as core's containment operation expects it, in presentation order. */
    private static List<ContainmentRequest> containmentRequests(AgendaBlock block) {
        List<UUID> members = block.members();
        List<Integer> minutes = block.memberPlannedMinutes();
        List<ContainmentRequest> requests = new ArrayList<>(members.size());
        for (int ord = 0; ord < members.size(); ord++) {
            requests.add(new ContainmentRequest(members.get(ord), minutes.get(ord), ord));
        }
        return requests;
    }
}
