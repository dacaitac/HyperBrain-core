package com.hyperbrain.prioritizer.domain.model;

import com.hyperbrain.prioritizer.domain.model.CycleAlignmentContext.AncestorLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CycleAlignmentContext (graded alignment read model)")
class CycleAlignmentContextTest {

    @Test
    @DisplayName("null ancestor list defensively becomes empty")
    void null_ancestors_becomes_empty() {
        CycleAlignmentContext context = new CycleAlignmentContext(CycleType.PROJECT, null);

        assertThat(context.activeAncestors()).isEmpty();
    }

    @Test
    @DisplayName("ancestor list is defensively copied (immutable)")
    void ancestors_are_defensively_copied() {
        CycleAlignmentContext context = new CycleAlignmentContext(
            CycleType.MCI, List.of(new AncestorLink(CycleType.MCI, 0)));

        assertThatThrownBy(() -> context.activeAncestors().add(new AncestorLink(CycleType.GOAL, 1)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("own type must not be null")
    void null_own_type_rejected() {
        assertThatThrownBy(() -> new CycleAlignmentContext(null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AncestorLink rejects a null type and a negative distance")
    void ancestor_link_validation() {
        assertThatThrownBy(() -> new AncestorLink(null, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AncestorLink(CycleType.MCI, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
