package com.hyperbrain.planner;

import com.hyperbrain.planner.domain.model.AgendaBlock;
import com.hyperbrain.planner.domain.model.PlannedBlockMember;
import com.hyperbrain.planner.domain.model.PlannedBlockRecord;
import com.hyperbrain.planner.domain.port.out.PlannerStateRepository;
import com.hyperbrain.sync.domain.port.out.ScheduledDueTimeProvider;
import com.hyperbrain.support.DataFixture;
import com.hyperbrain.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the grouped-block persistence contract of the reconciliation against a real PostgreSQL
 * (ADR-027 D2/D3/D5): the block's identity is a persisted surrogate that survives a membership change,
 * the bridge table mirrors the theme's membership, the trigger-owned {@code block_date} /
 * {@code block_status} columns are never written by the core, and the partial unique index enforces
 * "≤ 1 live block per executable per day" without breaking the same-day replan.
 */
@IntegrationTest
@DisplayName("Planner block membership — bridge table, derived columns and D5 (ADR-027)")
class PlannerBlockMembershipIT {

    private static final UUID USER = DataFixture.SYSTEM_USER_ID;
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlannerStateRepository repository;
    @Autowired private ScheduledDueTimeProvider scheduledDueTimeProvider;

    @BeforeEach
    void cleanState() throws Exception {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("UPDATE core_executable SET imputed_time_block_id = NULL");
        jdbcTemplate.update("DELETE FROM core_time_block");
        jdbcTemplate.update("DELETE FROM core_executable");
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            DataFixture.insertSystemUser(conn);
        }
        jdbcTemplate.update("UPDATE sys_user SET timezone = 'UTC' WHERE id = ?", USER);
    }

    @Test
    @DisplayName("a themed block projects one bridge row per member, ordered and splitting its duration")
    void themed_block_projects_its_membership() {
        UUID anchor = insertTask("Anchor");
        UUID companion = insertTask("Companion");

        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(companion), 9, 11)));

        List<Map<String, Object>> members = memberRows();
        assertThat(members).hasSize(2);
        assertThat(members.get(0)).containsEntry("executable_id", anchor)
            .containsEntry("ord", 0).containsEntry("planned_minutes", 60);
        assertThat(members.get(1)).containsEntry("executable_id", companion)
            .containsEntry("ord", 1).containsEntry("planned_minutes", 60);
    }

    @Test
    @DisplayName("block_date and block_status are derived by trigger: the core never writes them")
    void derived_columns_are_owned_by_the_trigger() {
        UUID anchor = insertTask("Anchor");

        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(), 9, 10)));

        // Derived from the parent block (date_start in the user's timezone + status), not written here.
        assertThat(memberRows()).singleElement()
            .satisfies(row -> {
                assertThat(row.get("block_date")).hasToString(DAY.toString());
                assertThat(row).containsEntry("block_status", "PLANNED");
            });

        // And a status change on the parent propagates down, keeping the D5 index truthful.
        jdbcTemplate.update("UPDATE core_time_block SET status = 'EXPIRED'");
        assertThat(memberRows()).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("block_status", "EXPIRED"));
    }

    @Test
    @DisplayName("a membership change keeps the block id and replaces the bridge rows (#15)")
    void membership_change_preserves_the_block_id() {
        UUID anchor = insertTask("Anchor");
        UUID leaving = insertTask("Leaving");
        UUID joining = insertTask("Joining");

        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(leaving), 9, 11)));
        UUID blockId = onlyBlockId();

        List<UUID> removed = repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(joining), 14, 16)));

        assertThat(onlyBlockId()).isEqualTo(blockId);
        assertThat(removed).isEmpty();
        assertThat(memberRows()).extracting(row -> row.get("executable_id"))
            .containsExactly(anchor, joining);
    }

    @Test
    @DisplayName("an executable moving to another block of the same day does not trip the D5 index")
    void executable_moves_between_blocks_of_the_same_day() {
        UUID first = insertTask("First");
        UUID second = insertTask("Second");
        UUID moving = insertTask("Moving");

        repository.reconcilePlannedBlocks(USER, DAY, ZONE, List.of(
            themedBlock(first, List.of(moving), 9, 11),
            themedBlock(second, List.of(), 14, 16)));

        // The replan moves the shared member from the first theme to the second one.
        repository.reconcilePlannedBlocks(USER, DAY, ZONE, List.of(
            themedBlock(first, List.of(), 9, 11),
            themedBlock(second, List.of(moving), 14, 16)));

        UUID secondBlockId = blockIdOfAnchor(second);
        assertThat(memberRows()).filteredOn(row -> moving.equals(row.get("executable_id")))
            .singleElement()
            .satisfies(row -> assertThat(row).containsEntry("block_id", secondBlockId));
    }

    @Test
    @DisplayName("D5: a second live membership for the same executable and day is rejected")
    void second_live_membership_is_rejected() {
        UUID anchor = insertTask("Anchor");
        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(), 9, 10)));

        UUID otherBlock = insertRawBlock(anchor, 15, 16, "PLANNED", "USER");

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO core_time_block_member (block_id, executable_id, planned_minutes, ord)
            VALUES (?, ?, 60, 0)
            """, otherBlock, anchor))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("D5: an EXPIRED membership is outside the index, so the same-day replan can re-place it")
    void expired_membership_does_not_block_a_replan() {
        UUID anchor = insertTask("Anchor");
        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(), 9, 10)));
        // The block expired unattended; its executable must remain re-placeable today (HU-02).
        jdbcTemplate.update("UPDATE core_time_block SET status = 'EXPIRED'");

        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(), 15, 16)));

        assertThat(memberRows()).filteredOn(row -> "PLANNED".equals(row.get("block_status")))
            .singleElement()
            .satisfies(row -> assertThat(row).containsEntry("executable_id", anchor));
    }

    @Test
    @DisplayName("an executable already held by a live FOCUS block keeps that membership; the day still plans")
    void externally_held_executable_is_skipped_instead_of_aborting_the_day() {
        UUID anchor = insertTask("Anchor");
        UUID focused = insertTask("Focused");
        UUID focusBlock = insertRawBlock(focused, 15, 16, "ACTIVE", "FOCUS");
        jdbcTemplate.update("""
            INSERT INTO core_time_block_member (block_id, executable_id, planned_minutes, ord)
            VALUES (?, ?, 60, 0)
            """, focusBlock, focused);

        // The planner wants the focused executable in today's theme: the bridge row is skipped, but
        // the block itself is still planned and delivered.
        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(focused), 9, 11)));

        UUID plannerBlock = blockIdOfAnchor(anchor);
        assertThat(memberRows()).filteredOn(row -> plannerBlock.equals(row.get("block_id")))
            .extracting(row -> row.get("executable_id"))
            .containsExactly(anchor);
        assertThat(memberRows()).filteredOn(row -> focusBlock.equals(row.get("block_id")))
            .hasSize(1);
    }

    @Test
    @DisplayName("the write-back read returns the container with its theme and ordered membership")
    void write_back_read_returns_theme_and_membership() {
        UUID anchor = insertTask("Write the report");
        UUID companion = insertTask("Review PRs");
        repository.reconcilePlannedBlocks(USER, DAY, ZONE, List.of(
            new AgendaBlock(anchor, at(9), at(11), false, false, "Ranked by priority",
                List.of(companion), "Deep work morning")));

        List<PlannedBlockRecord> blocks = repository.loadPlannedBlocksForDay(USER, DAY, ZONE);

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.theme()).isEqualTo("Deep work morning");
            assertThat(block.title()).isEqualTo("Deep work morning");
            assertThat(block.grouped()).isTrue();
            assertThat(block.members()).extracting(PlannedBlockMember::name)
                .containsExactly("Write the report", "Review PRs");
            assertThat(block.members()).extracting(PlannedBlockMember::ord).containsExactly(0, 1);
            // The split of the container's duration is coherent with its window (ADR-027 D2).
            assertThat(block.members().stream().mapToInt(PlannedBlockMember::plannedMinutes).sum())
                .isEqualTo(120);
        });
    }

    @Test
    @DisplayName("a block with no bridge row still reads back through its anchor (legacy/skipped rows)")
    void block_without_bridge_rows_falls_back_to_the_anchor() {
        UUID anchor = insertTask("Legacy block");
        // A block persisted before the ADR-027 migration: no core_time_block_member row at all.
        insertRawBlock(anchor, 9, 10, "PLANNED", "PLANNER");

        List<PlannedBlockRecord> blocks = repository.loadPlannedBlocksForDay(USER, DAY, ZONE);

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.members()).extracting(PlannedBlockMember::executableId)
                .containsExactly(anchor);
            assertThat(block.title()).isEqualTo("Legacy block");
            assertThat(block.grouped()).isFalse();
        });
    }

    @Test
    @DisplayName("the theme is persisted on the container and cleared when a replan drops it")
    void theme_is_persisted_and_cleared() {
        UUID anchor = insertTask("Anchor");
        repository.reconcilePlannedBlocks(USER, DAY, ZONE, List.of(
            new AgendaBlock(anchor, at(9), at(10), false, false, "why", List.of(), "Deep work")));
        assertThat(persistedTheme()).isEqualTo("Deep work");

        // A deterministic regeneration carries no theme: the column follows the current plan.
        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(new AgendaBlock(anchor, at(9), at(10), false, false, "why")));

        assertThat(persistedTheme()).isNull();
    }

    @Test
    @DisplayName("a block dropped from the plan takes its bridge rows with it (cascade)")
    void dropped_block_removes_its_membership() {
        UUID anchor = insertTask("Anchor");
        UUID dropped = insertTask("Dropped");
        repository.reconcilePlannedBlocks(USER, DAY, ZONE, List.of(
            themedBlock(anchor, List.of(), 9, 10),
            themedBlock(dropped, List.of(), 11, 12)));
        UUID droppedBlockId = blockIdOfAnchor(dropped);

        List<UUID> removed = repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(), 9, 10)));

        assertThat(removed).containsExactly(droppedBlockId);
        assertThat(memberRows()).extracting(row -> row.get("executable_id")).containsExactly(anchor);
    }

    @Test
    @DisplayName("core#50: the scheduled-due provider returns the block start for anchor AND companion")
    void provider_returns_block_start_for_every_member() {
        UUID anchor = insertTask("Anchor");
        UUID companion = insertTask("Companion");
        repository.reconcilePlannedBlocks(USER, DAY, ZONE,
            List.of(themedBlock(anchor, List.of(companion), 15, 16)));

        assertThat(scheduledDueTimeProvider.scheduledStart(anchor))
            .hasValueSatisfying(t -> assertThat(t.toInstant()).isEqualTo(at(15).toInstant()));
        assertThat(scheduledDueTimeProvider.scheduledStart(companion))
            .hasValueSatisfying(t -> assertThat(t.toInstant()).isEqualTo(at(15).toInstant()));
    }

    @Test
    @DisplayName("core#50: the provider is empty for an executable with no live planner block (de-scheduled)")
    void provider_empty_for_unscheduled_executable() {
        UUID lonely = insertTask("Unscheduled");

        assertThat(scheduledDueTimeProvider.scheduledStart(lonely)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgendaBlock themedBlock(UUID anchor, List<UUID> additionalMembers, int startHour,
                                           int endHour) {
        return new AgendaBlock(anchor, at(startHour), at(endHour), false, false, "Ranked by priority",
            additionalMembers);
    }

    private static OffsetDateTime at(int hour) {
        return OffsetDateTime.of(2026, 7, 10, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    private UUID insertTask(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_executable (id, user_id, name, type, status)
            VALUES (?, ?, ?, 'TASK', 'TODO')
            """, id, USER, name);
        return id;
    }

    private UUID insertRawBlock(UUID executableId, int startHour, int endHour, String status,
                                String origin) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO core_time_block (id, executable_id, date_start, date_end, status, origin)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, executableId, at(startHour), at(endHour), status, origin);
        return id;
    }

    private List<Map<String, Object>> memberRows() {
        return jdbcTemplate.queryForList(
            "SELECT block_id, executable_id, planned_minutes, ord, block_date, block_status "
                + "FROM core_time_block_member ORDER BY block_id, ord");
    }

    private UUID onlyBlockId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM core_time_block WHERE origin = 'PLANNER'", UUID.class);
    }

    private String persistedTheme() {
        return jdbcTemplate.queryForObject(
            "SELECT theme FROM core_time_block WHERE origin = 'PLANNER'", String.class);
    }

    private UUID blockIdOfAnchor(UUID executableId) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM core_time_block WHERE origin = 'PLANNER' AND executable_id = ?",
            UUID.class, executableId);
    }
}
