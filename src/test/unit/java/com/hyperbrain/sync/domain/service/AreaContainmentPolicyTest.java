package com.hyperbrain.sync.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AreaContainmentPolicy — core_cycle_area containment rule (ADR-036, DR-NN)")
class AreaContainmentPolicyTest {

    private static final UUID CYCLE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AREA_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AREA_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    @DisplayName("keeps every candidate that resolves to an AREA cycle")
    void keeps_area_targets() {
        Map<UUID, String> candidates = new LinkedHashMap<>();
        candidates.put(AREA_A, "AREA");
        candidates.put(AREA_B, "AREA");

        assertThat(AreaContainmentPolicy.validate(CYCLE, candidates))
            .containsExactly(AREA_A, AREA_B);
    }

    @Test
    @DisplayName("drops a candidate whose target is not an AREA (e.g. a PROJECT)")
    void drops_non_area_target() {
        Map<UUID, String> candidates = new LinkedHashMap<>();
        candidates.put(AREA_A, "AREA");
        candidates.put(AREA_B, "PROJECT");

        assertThat(AreaContainmentPolicy.validate(CYCLE, candidates)).containsExactly(AREA_A);
    }

    @Test
    @DisplayName("drops a self-reference (cycle_id = area_id)")
    void drops_self_reference() {
        Map<UUID, String> candidates = new LinkedHashMap<>();
        candidates.put(CYCLE, "AREA");
        candidates.put(AREA_A, "AREA");

        assertThat(AreaContainmentPolicy.validate(CYCLE, candidates)).containsExactly(AREA_A);
    }

    @Test
    @DisplayName("drops a candidate whose cycle does not exist locally (null type)")
    void drops_unknown_target() {
        Map<UUID, String> candidates = new LinkedHashMap<>();
        candidates.put(AREA_A, null);

        assertThat(AreaContainmentPolicy.validate(CYCLE, candidates)).isEmpty();
    }

    @Test
    @DisplayName("an empty candidate set yields an empty membership set")
    void empty_candidates_yield_empty() {
        assertThat(AreaContainmentPolicy.validate(CYCLE, Map.of())).isEqualTo(Set.of());
    }
}
