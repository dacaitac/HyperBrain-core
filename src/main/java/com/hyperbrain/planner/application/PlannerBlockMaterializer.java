package com.hyperbrain.planner.application;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.core.domain.model.ContainmentRequest;
import com.hyperbrain.core.domain.port.in.ExecutableContainmentService;
import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.PlannerBlockIdentity;
import com.hyperbrain.planner.domain.model.PlannerBlockRow;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.shared.outbox.OutboxRepository;
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
import java.util.Map;
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

    /** Heading of the member list a block carries in its note, so the calendar event says what it holds. */
    public static final String MEMBERS_NOTE_HEADING = "In this block:";

    /**
     * The fixed line every block's note carries (ADR-040 D18, mitigation 1). Containment reuses Notion's
     * Parent/Sub-task relation, and that relation reads as "this is PART OF that" — a block is not the
     * parent of a task, it is where the task happens. Deleting one there looks like it should take its
     * contents with it. It does not, and the regression test that proves it is what lets this line be
     * written without lying.
     */
    public static final String DELETION_NOTE =
        "Deleting this block does not delete its tasks — they are simply released from it.";

    private final PlannerStateRepository repository;
    private final ExecutableContainmentService containment;
    private final OutboxRepository outboxRepository;

    public PlannerBlockMaterializer(PlannerStateRepository repository,
                                    ExecutableContainmentService containment,
                                    OutboxRepository outboxRepository) {
        this.repository = repository;
        this.containment = containment;
        this.outboxRepository = outboxRepository;
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
                                  List<AgendaBlock> accepted, OffsetDateTime notBefore,
                                  String energyCriterion) {
        PlannerBlockIdentity.Reconciliation reconciliation = PlannerBlockIdentity.reconcile(
            accepted, repository.loadRegenerableBlocks(userId, targetDay, zone, notBefore));
        Map<UUID, String> memberNames = repository.loadExecutableTitles(
            accepted.stream().flatMap(block -> block.members().stream()).toList());

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
                identified.blockId(), userId, blockName(block),
                blockNote(block, memberNames, energyCriterion),
                block.start(), block.end(), block.templateSlotId()))) {
                // A block is an executable, so it reaches Apple and Notion through the standard
                // propagators — the type routing already sends it to the calendar. Announcing it only
                // when it genuinely moved is what keeps a replan that changed two blocks from
                // announcing thirty (ADR-040 D17).
                outboxRepository.append(identified.continued()
                    ? ExecutableOutboxEvents.updated(identified.blockId())
                    : ExecutableOutboxEvents.created(identified.blockId()));
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

    /**
     * The block's note: what it holds, why it is there, how the day's load was sized, and the standing
     * warning about deleting it.
     *
     * <p>This body used to be built inside the private delivery channel, and it is the reason that
     * channel could not simply be deleted: without it the calendar event loses its content and a block
     * stops being readable. It now lives on the block's own {@code description}, which is what the
     * standard propagators mirror — so one text serves the calendar and Notion alike instead of one
     * channel each.
     *
     * <p>The member list lives <b>here, on the container</b>, never on the members' own titles: EventKit
     * exposes no subtasks, so the grouping cannot nest, and writing the theme onto an executable would
     * pollute the mirror and churn the merge of its canonical title.
     */
    private static String blockNote(AgendaBlock block, Map<UUID, String> memberNames,
                                    String energyCriterion) {
        StringBuilder body = new StringBuilder();
        List<UUID> members = block.members();
        if (members.size() > 1) {
            body.append(MEMBERS_NOTE_HEADING);
            for (UUID member : members) {
                body.append("\n• ").append(memberNames.getOrDefault(member, "(untitled)"));
            }
        }
        appendParagraph(body, block.reason());
        appendParagraph(body, energyCriterion);
        appendParagraph(body, DELETION_NOTE);
        return body.length() == 0 ? null : body.toString();
    }

    /** Appends a blank-line-separated paragraph, skipping blanks so the note never opens with padding. */
    private static void appendParagraph(StringBuilder body, String paragraph) {
        if (paragraph == null || paragraph.isBlank()) {
            return;
        }
        if (body.length() > 0) {
            body.append("\n\n");
        }
        body.append(paragraph.trim());
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
