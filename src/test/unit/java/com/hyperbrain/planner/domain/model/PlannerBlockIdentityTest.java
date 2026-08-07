package com.hyperbrain.planner.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlannerBlockIdentity — surrogate identity reconciled by anchoring (ADR-027 D3, #15)")
class PlannerBlockIdentityTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final UUID ANCHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPANION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEWCOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID UNRELATED = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PERSISTED_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_PERSISTED_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Test
    @DisplayName("a block whose membership changed keeps its persisted id (binding condition, #15)")
    void membership_change_never_regenerates_the_id() {
        // Given a persisted themed block holding two executables
        PlannerBlockIdentity.PersistedBlock persisted =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR, COMPANION), 9, 11);

        // When the replan drops one member and adds another, and even moves the block to a new hour
        AgendaBlock replanned = block(ANCHOR, List.of(NEWCOMER), 15, 17);
        PlannerBlockIdentity.Reconciliation reconciliation =
            PlannerBlockIdentity.reconcile(List.of(replanned), List.of(persisted));

        // Then the block continues under the very same id → the write-back UPDATEs its EKEvent
        assertThat(reconciliation.identified()).singleElement()
            .satisfies(identified -> {
                assertThat(identified.blockId()).isEqualTo(PERSISTED_ID);
                assertThat(identified.continued()).isTrue();
            });
        assertThat(reconciliation.removedBlockIds()).isEmpty();
    }

    @Test
    @DisplayName("a block whose anchor rotated out still continues via its shared members")
    void anchor_rotation_falls_back_to_shared_membership() {
        PlannerBlockIdentity.PersistedBlock persisted =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR, COMPANION), 9, 11);

        // The former anchor left the theme entirely; the companion is promoted to anchor.
        AgendaBlock replanned = block(COMPANION, List.of(NEWCOMER), 9, 11);
        PlannerBlockIdentity.Reconciliation reconciliation =
            PlannerBlockIdentity.reconcile(List.of(replanned), List.of(persisted));

        assertThat(reconciliation.identified()).singleElement()
            .extracting(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .isEqualTo(PERSISTED_ID);
        assertThat(reconciliation.removedBlockIds()).isEmpty();
    }

    @Test
    @DisplayName("a genuinely new theme gets a fresh surrogate id and is flagged as not continued")
    void new_block_gets_a_fresh_surrogate() {
        AgendaBlock fresh = block(NEWCOMER, List.of(), 9, 10);

        PlannerBlockIdentity.Reconciliation reconciliation =
            PlannerBlockIdentity.reconcile(List.of(fresh), List.of(), () -> OTHER_PERSISTED_ID);

        assertThat(reconciliation.identified()).singleElement()
            .satisfies(identified -> {
                assertThat(identified.blockId()).isEqualTo(OTHER_PERSISTED_ID);
                assertThat(identified.continued()).isFalse();
            });
    }

    @Test
    @DisplayName("temporal overlap alone never inherits an id: an unrelated theme is a new block")
    void disjoint_membership_never_inherits_an_id() {
        PlannerBlockIdentity.PersistedBlock persisted =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR), 9, 10);

        // Same slot, entirely different work: inheriting the id would silently repurpose its EKEvent.
        AgendaBlock unrelated = block(UNRELATED, List.of(), 9, 10);
        PlannerBlockIdentity.Reconciliation reconciliation =
            PlannerBlockIdentity.reconcile(List.of(unrelated), List.of(persisted), () -> NEWCOMER);

        assertThat(reconciliation.identified()).singleElement()
            .extracting(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .isEqualTo(NEWCOMER);
        assertThat(reconciliation.removedBlockIds()).containsExactly(PERSISTED_ID);
    }

    @Test
    @DisplayName("a persisted block no desired block continues is reported as removed")
    void dropped_block_is_reported_as_removed() {
        PlannerBlockIdentity.PersistedBlock surviving =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR), 9, 10);
        PlannerBlockIdentity.PersistedBlock dropped =
            persistedBlock(OTHER_PERSISTED_ID, Set.of(COMPANION), 11, 12);

        PlannerBlockIdentity.Reconciliation reconciliation = PlannerBlockIdentity.reconcile(
            List.of(block(ANCHOR, List.of(), 9, 10)), List.of(surviving, dropped));

        assertThat(reconciliation.identified()).singleElement()
            .extracting(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .isEqualTo(PERSISTED_ID);
        assertThat(reconciliation.removedBlockIds()).containsExactly(OTHER_PERSISTED_ID);
    }

    @Test
    @DisplayName("the anchor pass wins over the shared-membership pass, so no two blocks claim one id")
    void a_persisted_block_is_claimed_by_at_most_one_desired_block() {
        PlannerBlockIdentity.PersistedBlock persisted =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR, COMPANION), 9, 11);

        // Two desired blocks touch the persisted membership: one by anchor, one by a shared member.
        AgendaBlock byAnchor = block(ANCHOR, List.of(), 14, 15);
        AgendaBlock bySharedMember = block(NEWCOMER, List.of(COMPANION), 9, 10);
        PlannerBlockIdentity.Reconciliation reconciliation = PlannerBlockIdentity.reconcile(
            List.of(byAnchor, bySharedMember), List.of(persisted), () -> UNRELATED);

        assertThat(reconciliation.identified())
            .extracting(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .containsExactlyInAnyOrder(PERSISTED_ID, UNRELATED)
            .doesNotHaveDuplicates();
        assertThat(idOf(reconciliation, ANCHOR)).isEqualTo(PERSISTED_ID);
    }

    @Test
    @DisplayName("ties on shared membership are broken by the longest temporal overlap")
    void shared_membership_ties_break_on_temporal_overlap() {
        PlannerBlockIdentity.PersistedBlock morning =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR, COMPANION), 9, 10);
        PlannerBlockIdentity.PersistedBlock afternoon =
            persistedBlock(OTHER_PERSISTED_ID, Set.of(UNRELATED, COMPANION), 14, 16);

        // The anchor is new, so both persisted blocks tie on one shared member (COMPANION); the
        // afternoon one overlaps the desired window, so it is the one continued.
        AgendaBlock desired = block(NEWCOMER, List.of(COMPANION), 14, 15);
        PlannerBlockIdentity.Reconciliation reconciliation =
            PlannerBlockIdentity.reconcile(List.of(desired), List.of(morning, afternoon));

        assertThat(reconciliation.identified()).singleElement()
            .extracting(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .isEqualTo(OTHER_PERSISTED_ID);
        assertThat(reconciliation.removedBlockIds()).containsExactly(PERSISTED_ID);
    }

    @Test
    @DisplayName("the outcome is independent of the incoming list order")
    void reconciliation_is_order_independent() {
        PlannerBlockIdentity.PersistedBlock first =
            persistedBlock(PERSISTED_ID, Set.of(ANCHOR), 9, 10);
        PlannerBlockIdentity.PersistedBlock second =
            persistedBlock(OTHER_PERSISTED_ID, Set.of(COMPANION), 11, 12);
        AgendaBlock early = block(ANCHOR, List.of(), 9, 10);
        AgendaBlock late = block(COMPANION, List.of(), 11, 12);

        PlannerBlockIdentity.Reconciliation forward = PlannerBlockIdentity.reconcile(
            List.of(early, late), List.of(first, second), sequentialIds());
        PlannerBlockIdentity.Reconciliation reversed = PlannerBlockIdentity.reconcile(
            List.of(late, early), List.of(second, first), sequentialIds());

        assertThat(forward).usingRecursiveComparison().isEqualTo(reversed);
    }

    @Test
    @DisplayName("the identified blocks come back in chronological order")
    void identified_blocks_are_chronological() {
        AgendaBlock late = block(COMPANION, List.of(), 15, 16);
        AgendaBlock early = block(ANCHOR, List.of(), 9, 10);

        PlannerBlockIdentity.Reconciliation reconciliation =
            PlannerBlockIdentity.reconcile(List.of(late, early), List.of(), sequentialIds());

        assertThat(reconciliation.identified())
            .extracting(identified -> identified.block().start())
            .isSorted();
    }

    @Test
    @DisplayName("reconcile rejects null arguments")
    void reconcile_rejects_null_arguments() {
        assertThatThrownBy(() -> PlannerBlockIdentity.reconcile(null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlannerBlockIdentity.reconcile(List.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlannerBlockIdentity.reconcile(List.of(), List.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PersistedBlock rejects an incomplete descriptor")
    void persisted_block_rejects_invalid_input() {
        OffsetDateTime start = OffsetDateTime.of(2026, 7, 21, 9, 0, 0, 0, UTC);
        assertThatThrownBy(() ->
            new PlannerBlockIdentity.PersistedBlock(null, null, Set.of(ANCHOR), start, start, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new PlannerBlockIdentity.PersistedBlock(PERSISTED_ID, null, Set.of(ANCHOR), null, start, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new PlannerBlockIdentity.PersistedBlock(PERSISTED_ID, null, Set.of(ANCHOR), start, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IdentifiedBlock rejects a null id or block")
    void identified_block_rejects_invalid_input() {
        AgendaBlock any = block(ANCHOR, List.of(), 9, 10);
        assertThatThrownBy(() -> new PlannerBlockIdentity.IdentifiedBlock(null, any, true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlannerBlockIdentity.IdentifiedBlock(PERSISTED_ID, null, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AgendaBlock block(UUID anchor, List<UUID> additionalMembers, int startHour,
                                     int endHour) {
        return new AgendaBlock(anchor, at(startHour), at(endHour), false, false, "reason",
            additionalMembers);
    }

    private static PlannerBlockIdentity.PersistedBlock persistedBlock(UUID blockId, Set<UUID> members,
                                                                     int startHour, int endHour) {
        return new PlannerBlockIdentity.PersistedBlock(
            blockId, null, members, at(startHour), at(endHour), null);
    }

    private static OffsetDateTime at(int hour) {
        return OffsetDateTime.of(2026, 7, 21, hour, 0, 0, 0, UTC);
    }

    /** A deterministic id factory so two runs of the same reconciliation compare equal. */
    private static java.util.function.Supplier<UUID> sequentialIds() {
        AtomicInteger counter = new AtomicInteger();
        return () -> UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000" + counter.incrementAndGet());
    }

    private static UUID idOf(PlannerBlockIdentity.Reconciliation reconciliation, UUID anchor) {
        return reconciliation.identified().stream()
            .filter(identified -> identified.block().executableId().equals(anchor))
            .map(PlannerBlockIdentity.IdentifiedBlock::blockId)
            .findFirst()
            .orElseThrow();
    }
}
