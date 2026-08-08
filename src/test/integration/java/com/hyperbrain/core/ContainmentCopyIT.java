package com.hyperbrain.core;

import com.hyperbrain.core.domain.model.ContainerSchedule;
import com.hyperbrain.core.domain.port.in.DomainChangeProcessor;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the ADR-039 hard-copy rule against a real PostgreSQL: containment
 * assignment, the transitive schedule copy (subtask → task → block), value idempotence and
 * the detach-preserves-values contract.
 */
@IntegrationTest
@DisplayName("Containment hard copy of date + cycle (ADR-039)")
class ContainmentCopyIT {

    private static final OffsetDateTime BLOCK_START =
        OffsetDateTime.of(2026, 8, 6, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BLOCK_END = BLOCK_START.plusHours(1);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ExecutableStateRepository stateRepo;
    @Autowired private DomainChangeProcessor processor;

    private UUID userId;
    private UUID cycleId;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM core_execution_profile");
        jdbcTemplate.update("UPDATE core_executable SET container_block_id = NULL, "
            + "imputed_block_id = NULL, imputed_time_block_id = NULL, parent_id = NULL");
        jdbcTemplate.update("DELETE FROM core_executable");
        jdbcTemplate.update("DELETE FROM core_cycle");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            userId = DataFixture.insertSystemUser(conn);
        }
        cycleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO core_cycle (id, user_id, name, type, status) VALUES (?, ?, 'C', 'PROJECT', 'ACTIVE')",
            cycleId, userId);
    }

    @Test
    @DisplayName("assigning a member to a container resolves the container's schedule as its authority")
    void assign_container_exposes_schedule() {
        UUID block = insertBlock();
        UUID task = insertTask("Task", null, null, null);

        boolean changed = stateRepo.assignContainer(task, block, 30, 0);

        assertThat(changed).isTrue();
        Optional<ContainerSchedule> schedule = stateRepo.findContainerSchedule(task, null, null);
        assertThat(schedule).isPresent();
        assertThat(schedule.get().containerId()).isEqualTo(block);
        assertThat(schedule.get().startTime()).isEqualTo(BLOCK_START);
        assertThat(schedule.get().cycleId()).isEqualTo(cycleId);
        // Re-assigning identical values is a no-op (idempotent by value).
        assertThat(stateRepo.assignContainer(task, block, 30, 0)).isFalse();
    }

    @Test
    @DisplayName("core#63 pull symmetry: findContainerSchedule falls back to the PERSISTED parent when the merge omits the relation")
    void find_schedule_falls_back_to_persisted_parent() {
        // A dated parent and a subtask under it (parent_id persisted).
        UUID parent = insertDatedParent("Parent", BLOCK_START, BLOCK_END, cycleId);
        UUID subtask = insertTask("Subtask", parent, null, null);

        // The merge carries no in-flight container nor parent (partial edit / Apple ingestion).
        Optional<ContainerSchedule> schedule = stateRepo.findContainerSchedule(subtask, null, null);

        assertThat(schedule).isPresent();
        assertThat(schedule.get().containerId()).isEqualTo(parent);
        assertThat(schedule.get().startTime()).isEqualTo(BLOCK_START);
        assertThat(schedule.get().cycleId()).isEqualTo(cycleId);
    }

    @Test
    @DisplayName("core#63 pull: editing a subtask without re-sending Parent Task re-takes the parent's date + cycle")
    void editing_subtask_without_parent_relation_re_takes_parent_schedule() {
        UUID parent = insertDatedParent("Parent", BLOCK_START, BLOCK_END, cycleId);
        UUID subtask = insertTask("Subtask", parent, null, null);

        // The partial edit's merged snapshot omits the parent relation and carries a stale date/cycle.
        ExecutableSnapshot previous = subtaskSnapshot(subtask, parent, null, null);
        ExecutableSnapshot merged =
            subtaskSnapshot(subtask, null, BLOCK_START.plusDays(3), null);

        ExecutableSnapshot result = processor.process(previous, merged, ExternalSystem.NOTION);

        // The hard-copy re-takes the parent's schedule despite the missing relation (DR-01 clears end).
        assertThat(result.startTime()).isEqualTo(BLOCK_START);
        assertThat(result.endTime()).isNull();
        assertThat(result.cycleId()).isEqualTo(cycleId);
    }

    @Test
    @DisplayName("copyScheduleToContained hard-copies date + cycle transitively (subtask → task → block), idempotent by value")
    void copy_is_transitive_and_idempotent() {
        UUID block = insertBlock();
        UUID task = insertTask("Task", null, null, null);
        stateRepo.assignContainer(task, block, 60, 0);
        UUID subtask = insertTask("Subtask", task, null, null); // reminder-type child of the task

        List<UUID> changed = stateRepo.copyScheduleToContained(block, BLOCK_START, BLOCK_END, cycleId);

        assertThat(changed).containsExactlyInAnyOrder(task, subtask);
        // The task is a reminder type (TASK): DR-01 clears end_time, keeps start_time + cycle.
        assertRow(task, BLOCK_START, null, cycleId);
        assertRow(subtask, BLOCK_START, null, cycleId);

        // A second identical copy touches nothing (echo convergence).
        assertThat(stateRepo.copyScheduleToContained(block, BLOCK_START, BLOCK_END, cycleId)).isEmpty();
    }

    @Test
    @DisplayName("an event-type contained child copies the full window")
    void event_child_copies_full_window() {
        UUID block = insertBlock();
        UUID activity = insertTask("Meeting", null, null, "ACTIVITY");
        stateRepo.assignContainer(activity, block, 60, 0);

        stateRepo.copyScheduleToContained(block, BLOCK_START, BLOCK_END, cycleId);

        assertRow(activity, BLOCK_START, BLOCK_END, cycleId);
    }

    @Test
    @DisplayName("detach clears the containment but the copied schedule values persist")
    void detach_preserves_copied_values() {
        UUID block = insertBlock();
        UUID task = insertTask("Task", null, null, null);
        stateRepo.assignContainer(task, block, 60, 0);
        stateRepo.copyScheduleToContained(block, BLOCK_START, BLOCK_END, cycleId);

        boolean detached = stateRepo.clearContainer(task);

        assertThat(detached).isTrue();
        assertThat((UUID) jdbcTemplate.queryForObject(
            "SELECT container_block_id FROM core_executable WHERE id = ?", UUID.class, task)).isNull();
        // The hard-copied date + cycle survive the detach (documented ADR-039 behaviour).
        assertRow(task, BLOCK_START, null, cycleId);
    }

    @Test
    @DisplayName("a container retime propagates the copy through the full DR chain, one outbox event per changed child")
    void retime_through_chain_propagates_and_stages_events() {
        UUID block = insertBlock();
        UUID task = insertTask("Task", null, null, null);
        stateRepo.assignContainer(task, block, 60, 0);
        // Seed the child with the original window so the retime is a genuine move.
        stateRepo.copyScheduleToContained(block, BLOCK_START, BLOCK_END, cycleId);
        jdbcTemplate.update("DELETE FROM outbox_events");

        ExecutableSnapshot previous = blockSnapshot(block, BLOCK_START, BLOCK_END);
        ExecutableSnapshot retimed = blockSnapshot(block, BLOCK_START.plusHours(4), BLOCK_END.plusHours(4));
        processor.process(previous, retimed, ExternalSystem.SYSTEM);

        assertRow(task, BLOCK_START.plusHours(4), null, cycleId);
        Integer events = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'ExecutableUpdatedEvent'",
            Integer.class, task.toString());
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("a task he drags into a planner block hands that block over to him for the rest of its day")
    void a_hand_placement_hands_the_planner_block_over() {
        UUID block = insertBlock();
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, block), ExternalSystem.NOTION);

        // The block stops being the planner's draft, so the run no longer rebuilds its membership and
        // the task stays where he put it. Nothing is dated: the anchor dies with the block's own day.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, block)).isEqualTo("USER");
    }

    @Test
    @DisplayName("the echo of the planner's own containment leaves the block the planner's")
    void an_echo_leaves_the_block_with_the_planner() {
        UUID block = insertBlock();
        UUID task = insertTask("Planned work", null, null, null);
        stateRepo.assignContainer(task, block, 60, 0);

        // Our own containment is mirrored to Notion and read straight back: same container, no change.
        processor.process(taskSnapshot(task, block), taskSnapshot(task, block), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, block))
            .isEqualTo("PLANNER");
    }

    @Test
    @DisplayName("a block whose hour has already gone by still hands over: the guard is authority, not the clock")
    void a_block_whose_window_already_passed_still_hands_over() {
        // He arranges the window he is in, so the hand-over must not ask what time it is. The regenerable
        // set is narrower than this guard on purpose — it also demands the block be still ahead — and
        // reading that extra condition into the hand-over would let the next replan take apart the
        // membership he just arranged, which is the exact defect this closes.
        //
        // The hour is taken relative to now, not from the fixture's fixed instant: "already gone by" is
        // a statement about the clock the guard would read, so the only way the case keeps its meaning
        // on every run is for the window to be behind whatever «now» is. The assertion itself stays
        // deterministic — the statement has no clock in it, which is precisely what is being pinned.
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        UUID block = insertBlock();
        jdbcTemplate.update(
            "UPDATE core_executable SET start_time = ?, end_time = ? WHERE id = ?",
            past, past.plusHours(1), block);
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, block), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, block)).isEqualTo("USER");
    }

    @Test
    @DisplayName("a block already under way is not handed over — it was never the planner's to give")
    void a_started_block_is_left_alone() {
        UUID block = insertBlock();
        jdbcTemplate.update("UPDATE core_executable SET status = 'IN_PROGRESS' WHERE id = ?", block);
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, block), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, block))
            .isEqualTo("PLANNER");
    }

    @Test
    @DisplayName("seeing the same edit twice changes nothing the second time: the block is already his")
    void a_second_observation_of_the_same_edit_writes_nothing() {
        // SQS delivers at-least-once and Notion re-sends a page on any edit, so the same hand placement
        // is observed more than once. The guard lives inside the predicate: once the block is his it is
        // no longer PLANNER, so the second observation matches no row.
        UUID block = insertBlock();
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, block), ExternalSystem.NOTION);
        processor.process(taskSnapshot(task, null), taskSnapshot(task, block), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, block)).isEqualTo("USER");
    }

    @Test
    @DisplayName("a FOCUS row is never handed over: it accounts for work, it arranges nothing")
    void a_focus_block_is_never_handed_over() {
        UUID focus = insertFocusBlock();
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, focus), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, focus))
            .isEqualTo("FOCUS");
    }

    @Test
    @DisplayName("a block he made himself needs no hand-over — it was never the planner's to begin with")
    void a_hand_made_block_is_left_as_it_was_born() {
        // A block Daniel creates in Notion arrives through the ordinary ingestion of an executable, so
        // it carries no origin at all. It is already outside the regenerable set, and the hand-over
        // deliberately does not normalise it: nothing about it is the planner's to change.
        UUID handMade = insertHandMadeBlock();
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, handMade), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, handMade)).isNull();
    }

    @Test
    @DisplayName("a containment pointing at something that is not a block flips no row's origin")
    void a_containment_on_a_non_block_hands_nothing_over() {
        // The Notion relation is overloaded (ADR-039) and a stale or half-resolved target could name a
        // row that is not a window at all. The type guard is what keeps a hand-over from rewriting the
        // authority of an ordinary task.
        UUID notABlock = insertTask("Tarea que no es un bloque", null, null, null);
        UUID task = insertTask("Facturas", null, null, null);

        processor.process(taskSnapshot(task, null), taskSnapshot(task, notABlock), ExternalSystem.NOTION);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT origin FROM core_executable WHERE id = ?", String.class, notABlock)).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertFocusBlock() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, origin, start_time, end_time, system_generated)
            VALUES (?, ?, 'Focus', 'TIME_BLOCK', 'PLANNED', 'FOCUS', ?, ?, false)
            """, id, userId, BLOCK_START, BLOCK_END);
        return id;
    }

    /** A block as Daniel's own is born: from Notion, with no origin and the initial status. */
    private UUID insertHandMadeBlock() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, name, type, status, start_time, end_time, system_generated)
            VALUES (?, ?, 'Oficio', 'TIME_BLOCK', 'TODO', ?, ?, false)
            """, id, userId, BLOCK_START, BLOCK_END);
        return id;
    }

    private UUID insertBlock() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, cycle_id, name, type, status, origin, start_time, end_time, system_generated)
            VALUES (?, ?, ?, 'Morning block', 'TIME_BLOCK', 'PLANNED', 'PLANNER', ?, ?, false)
            """, id, userId, cycleId, BLOCK_START, BLOCK_END);
        return id;
    }

    private UUID insertTask(String name, UUID parentId, UUID containerId, String type) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, parent_id, container_block_id, name, type, status, system_generated)
            VALUES (?, ?, ?, ?, ?, ?, 'TODO', false)
            """, id, userId, parentId, containerId, name, type != null ? type : "TASK");
        return id;
    }

    private UUID insertDatedParent(String name, OffsetDateTime start, OffsetDateTime end, UUID cycle) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable
                (id, user_id, cycle_id, name, type, status, start_time, end_time, system_generated)
            VALUES (?, ?, ?, ?, 'TASK', 'TODO', ?, ?, false)
            """, id, userId, cycle, name, start, end);
        return id;
    }

    private ExecutableSnapshot subtaskSnapshot(UUID id, UUID parentId, OffsetDateTime start,
                                               UUID cycle) {
        return new ExecutableSnapshot(id, userId, parentId, cycle, "Subtask", null,
            "TASK", "TODO", null, null, null, false, null, start, null, null,
            null, null, null, false, null);
    }

    /** A reminder-backed task as it arrives from Notion, contained or not. */
    private ExecutableSnapshot taskSnapshot(UUID id, UUID containerBlockId) {
        return new ExecutableSnapshot(id, userId, null, null, "Facturas", null,
            "TASK", "TODO", null, null, null, false, null, null, null, null,
            null, null, null, false, containerBlockId);
    }

    private ExecutableSnapshot blockSnapshot(UUID id, OffsetDateTime start, OffsetDateTime end) {
        return new ExecutableSnapshot(id, userId, null, cycleId, "Morning block", null,
            "TIME_BLOCK", "PLANNED", null, null, null, false, null, start, end, null,
            null, null, null, false, null);
    }

    private void assertRow(UUID id, OffsetDateTime start, OffsetDateTime end, UUID cycle) {
        OffsetDateTime actualStart = jdbcTemplate.queryForObject(
            "SELECT start_time FROM core_executable WHERE id = ?", OffsetDateTime.class, id);
        OffsetDateTime actualEnd = jdbcTemplate.queryForObject(
            "SELECT end_time FROM core_executable WHERE id = ?", OffsetDateTime.class, id);
        UUID actualCycle = jdbcTemplate.queryForObject(
            "SELECT cycle_id FROM core_executable WHERE id = ?", UUID.class, id);
        assertThat(actualStart.toInstant()).isEqualTo(start.toInstant());
        if (end == null) {
            assertThat(actualEnd).isNull();
        } else {
            assertThat(actualEnd.toInstant()).isEqualTo(end.toInstant());
        }
        assertThat(actualCycle).isEqualTo(cycle);
    }
}
