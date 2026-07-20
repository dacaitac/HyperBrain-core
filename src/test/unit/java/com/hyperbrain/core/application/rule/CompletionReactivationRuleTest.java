package com.hyperbrain.core.application.rule;

import com.hyperbrain.shared.messaging.ExternalSystem;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import com.hyperbrain.sync.support.ExecutableSnapshotBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompletionReactivationRule (DR-02)")
class CompletionReactivationRuleTest {

    private final CompletionReactivationRule rule = new CompletionReactivationRule();

    @Test
    @DisplayName("DONE → uncheck → IN_PROGRESS: work already started does not restart from scratch")
    void done_to_todo_becomes_in_progress() {
        // Given
        ExecutableSnapshot previous = snapshot("DONE");
        ExecutableSnapshot merged = snapshot("TODO");

        // When
        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.NOTION);

        // Then
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("preserves all other fields when reactivating to IN_PROGRESS")
    void preserves_other_fields_on_reactivation() {
        // Given
        ExecutableSnapshot previous = snapshot("DONE");
        ExecutableSnapshot merged = snapshot("TODO");

        // When
        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.APPLE);

        // Then
        assertThat(result)
            .usingRecursiveComparison()
            .ignoringFields("status")
            .isEqualTo(merged);
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("CREATE (previous=null): rule does not apply, status stays TODO")
    void create_previous_null_no_op() {
        // Given
        ExecutableSnapshot merged = snapshot("TODO");

        // When
        ExecutableSnapshot result = rule.apply(null, merged, ExternalSystem.NOTION);

        // Then
        assertThat(result).isSameAs(merged);
    }

    @Test
    @DisplayName("previous=IN_PROGRESS → TODO: never passed through DONE, rule does not apply")
    void in_progress_to_todo_no_op() {
        // Given
        ExecutableSnapshot previous = snapshot("IN_PROGRESS");
        ExecutableSnapshot merged = snapshot("TODO");

        // When
        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.APPLE);

        // Then
        assertThat(result).isSameAs(merged);
    }

    @Test
    @DisplayName("previous=DONE → merged=IN_PROGRESS: already in transit, rule does not apply")
    void done_to_in_progress_no_op() {
        // Given
        ExecutableSnapshot previous = snapshot("DONE");
        ExecutableSnapshot merged = snapshot("IN_PROGRESS");

        // When
        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.NOTION);

        // Then
        assertThat(result).isSameAs(merged);
    }

    @Test
    @DisplayName("previous=DONE → merged=DONE: remains DONE, rule does not apply")
    void done_to_done_no_op() {
        // Given
        ExecutableSnapshot previous = snapshot("DONE");
        ExecutableSnapshot merged = snapshot("DONE");

        // When
        ExecutableSnapshot result = rule.apply(previous, merged, ExternalSystem.APPLE);

        // Then
        assertThat(result).isSameAs(merged);
    }

    private static ExecutableSnapshot snapshot(String status) {
        return ExecutableSnapshotBuilder.snapshot().status(status).build();
    }
}
