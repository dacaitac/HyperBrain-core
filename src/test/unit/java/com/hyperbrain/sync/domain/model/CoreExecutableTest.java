package com.hyperbrain.sync.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant {@link CoreExecutable#isTimeRecord()} states, pinned at the domain level rather than
 * through the propagator.
 *
 * <p>This exists because the propagator can only ever exercise <b>one</b> of the three conditions of
 * the observed-episode discriminant. It asks the predicate from inside
 * {@code if (STATUS_DONE.equals(status) && !executable.isTimeRecord())}, and only after having
 * returned early for a {@code system_generated} row — so at that call site the status is already
 * {@code DONE} and {@code systemGenerated} is already false, and dropping either condition from the
 * discriminant would change no write-back behaviour any test could observe. The predicate is public
 * domain API; each of its conditions is asserted here directly, so a mutation of any one of them
 * fails a test instead of passing unnoticed.
 */
@DisplayName("CoreExecutable: which rows are records of time that already passed")
class CoreExecutableTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 8, 6, 14, 0, 0, 0, ZoneOffset.UTC);

    @Nested
    @DisplayName("the block arm (ADR-039): a TIME_BLOCK is a time record whoever authored it")
    class BlockArm {

        @Test
        @DisplayName("a settled block with no origin at all is still a time record")
        void a_block_without_origin_is_a_time_record() {
            // The arm must not lean on the origin: a block born in Notion or in the calendar carries
            // none, and its EKEvent is the block's time accounting just the same.
            assertThat(executable("TIME_BLOCK", "DONE", null, false).isTimeRecord()).isTrue();
        }

        @Test
        @DisplayName("a block the user claimed by hand (origin USER) is still a time record")
        void a_user_claimed_block_is_a_time_record() {
            // Rearranging a block by hand hands its authorship over (origin PLANNER → USER), and that
            // must not cost it its calendar event when it settles.
            assertThat(executable("TIME_BLOCK", "DONE", "USER", false).isTimeRecord()).isTrue();
        }

        @Test
        @DisplayName("a FOCUS block is a time record even though it is internal accounting")
        void a_focus_block_is_a_time_record() {
            // Whether a FOCUS block reaches Apple at all is decided elsewhere (it raises no outbox
            // event on settlement); the invariant itself does not carve it out.
            assertThat(executable("TIME_BLOCK", "DONE", "FOCUS", false).isTimeRecord()).isTrue();
        }

        @Test
        @DisplayName("an open block is a time record too: the invariant is about the kind of entity, not its state")
        void an_open_block_is_a_time_record() {
            assertThat(executable("TIME_BLOCK", "PLANNED", "PLANNER", false).isTimeRecord()).isTrue();
        }

        @Test
        @DisplayName("a settled PLANNER block satisfies both arms, and the block arm is the one that must hold it")
        void a_settled_planner_block_is_claimed_by_type() {
            // The overlap the Javadoc declares instead of hiding: this row also looks like an observed
            // episode. It is claimed by type, which is what keeps the block arm load-bearing — removing
            // it would silently drop every block that is NOT planner-authored (a USER or a Notion-born
            // one), and those two cases above are what catch that.
            CoreExecutable settledPlannerBlock = executable("TIME_BLOCK", "DONE", "PLANNER", false);
            assertThat(settledPlannerBlock.isTimeRecord()).isTrue();
        }
    }

    @Nested
    @DisplayName("the observed-episode arm: what the system recorded after watching it happen")
    class ObservedEpisodeArm {

        @Test
        @DisplayName("the recorded nap — PLANNER, born DONE, meant for the satellites — is a time record")
        void a_recorded_nap_is_a_time_record() {
            assertThat(executable("ACTIVITY", "DONE", "PLANNER", false).isTimeRecord()).isTrue();
        }

        @Test
        @DisplayName("a LEARNING_SESSION the system records the same way is a time record too")
        void a_recorded_learning_session_is_a_time_record() {
            // The arm is stated over provenance, not over ACTIVITY: anything the recorder writes the
            // same way is the same kind of thing.
            assertThat(executable("LEARNING_SESSION", "DONE", "PLANNER", false).isTimeRecord()).isTrue();
        }

        @Test
        @DisplayName("origin is load-bearing: an ACTIVITY nobody authored is NOT a time record")
        void an_activity_without_origin_is_not_a_time_record() {
            // The boundary the fix deliberately did not cross: an ACTIVITY Daniel creates and finishes
            // later carries no origin, and completing it still removes its calendar event.
            assertThat(executable("ACTIVITY", "DONE", null, false).isTimeRecord()).isFalse();
        }

        @Test
        @DisplayName("origin is load-bearing: a USER-authored DONE activity is NOT a time record")
        void a_user_authored_activity_is_not_a_time_record() {
            assertThat(executable("ACTIVITY", "DONE", "USER", false).isTimeRecord()).isFalse();
        }

        @Test
        @DisplayName("status is load-bearing: a PLANNER activity still open is NOT a time record")
        void an_open_planner_activity_is_not_a_time_record() {
            // Nothing writes this shape today (the recorder inserts already completed), which is
            // exactly why the propagator cannot catch the condition going missing. An episode is an
            // account of time that PASSED; one still running has not earned the exemption, and if a
            // future writer ever stamps PLANNER on an open row it must not inherit it by accident.
            assertThat(executable("ACTIVITY", "PLANNED", "PLANNER", false).isTimeRecord()).isFalse();
            assertThat(executable("ACTIVITY", "TODO", "PLANNER", false).isTimeRecord()).isFalse();
            assertThat(executable("ACTIVITY", "IN_PROGRESS", "PLANNER", false).isTimeRecord()).isFalse();
        }

        @Test
        @DisplayName("a FAILED planner activity is NOT a time record: only DONE is the observed shape")
        void a_failed_planner_activity_is_not_a_time_record() {
            assertThat(executable("ACTIVITY", "FAILED", "PLANNER", false).isTimeRecord()).isFalse();
        }

        @Test
        @DisplayName("system_generated is load-bearing: an internal accounting row is NOT a time record")
        void a_system_generated_planner_activity_is_not_a_time_record() {
            // Also unobservable through the propagator, which returns for system_generated rows before
            // it ever asks. The invariant states it anyway: a retrospective accounting row is not meant
            // for the satellites at all, so it is not the kind of thing whose external entity survives.
            assertThat(executable("ACTIVITY", "DONE", "PLANNER", true).isTimeRecord()).isFalse();
        }

        @Test
        @DisplayName("a DONE TASK the planner never authored is NOT a time record")
        void a_done_task_is_not_a_time_record() {
            // The ordinary case, and the one the whole delete-on-DONE rule exists for: Apple must not
            // accumulate checked-off reminders.
            assertThat(executable("TASK", "DONE", null, false).isTimeRecord()).isFalse();
        }
    }

    private static CoreExecutable executable(String type, String status, String origin,
                                             boolean systemGenerated) {
        return new CoreExecutable(ID, USER_ID, "Siesta", null, type, status,
            START, START.plusHours(1), null, systemGenerated, origin);
    }
}
