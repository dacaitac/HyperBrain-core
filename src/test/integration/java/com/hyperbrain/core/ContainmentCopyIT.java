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
        Optional<ContainerSchedule> schedule = stateRepo.findContainerSchedule(task, null);
        assertThat(schedule).isPresent();
        assertThat(schedule.get().containerId()).isEqualTo(block);
        assertThat(schedule.get().startTime()).isEqualTo(BLOCK_START);
        assertThat(schedule.get().cycleId()).isEqualTo(cycleId);
        // Re-assigning identical values is a no-op (idempotent by value).
        assertThat(stateRepo.assignContainer(task, block, 30, 0)).isFalse();
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

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private ExecutableSnapshot blockSnapshot(UUID id, OffsetDateTime start, OffsetDateTime end) {
        return new ExecutableSnapshot(id, userId, null, cycleId, "Morning block", null,
            "TIME_BLOCK", "PLANNED", null, null, null, false, null, start, end, null,
            null, null, null, false);
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
