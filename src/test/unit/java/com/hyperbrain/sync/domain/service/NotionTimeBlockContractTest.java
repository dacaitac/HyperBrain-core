package com.hyperbrain.sync.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.model.TimeBlockMemberSnapshot;
import com.hyperbrain.sync.domain.model.TimeBlockSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests of the ADR-038 anti-echo canonicalization (style of
 * {@code NotionScaleContractTest}): the {@code Tasks} relation must serialize in <b>canonical
 * order</b> (page ids sorted) so the same member set always produces the same property JSON —
 * and therefore the same {@code SHA-256(external_id + operation + propertiesJson)} checksum —
 * no matter the order Notion reports it in. Without this, every webhook echo would miss the
 * CA-4 discard and bounce back as a spurious update (RF-17 loop over the block mirror).
 */
@DisplayName("Time Blocks mirror — canonical relation order & checksum contract (ADR-038)")
class NotionTimeBlockContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID BLOCK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));

    private static final List<String> RELATION_A = List.of(
        "cccc0000000000000000000000000003",
        "aaaa0000000000000000000000000001",
        "bbbb0000000000000000000000000002");
    private static final List<String> RELATION_B = List.of(
        "aaaa0000000000000000000000000001",
        "bbbb0000000000000000000000000002",
        "cccc0000000000000000000000000003");

    @Test
    @DisplayName("the canonical JSON — the checksum input — is invariant under the relation's reported order")
    void canonical_json_invariant_under_relation_order() throws JsonProcessingException {
        // Given the same member set reported in two different orders
        TimeBlockSnapshot block = block();

        // When
        String jsonA = MAPPER.writeValueAsString(NotionTimeBlockMapper.map(block, RELATION_A, null));
        String jsonB = MAPPER.writeValueAsString(NotionTimeBlockMapper.map(block, RELATION_B, null));

        // Then — bit-identical serialization ⇒ identical SHA-256 checksum in both directions
        assertThat(jsonA).isEqualTo(jsonB);
    }

    @Test
    @DisplayName("a genuinely different member set changes the canonical JSON")
    void canonical_json_changes_with_membership() throws JsonProcessingException {
        TimeBlockSnapshot block = block();

        String full = MAPPER.writeValueAsString(NotionTimeBlockMapper.map(block, RELATION_A, null));
        String reduced = MAPPER.writeValueAsString(
            NotionTimeBlockMapper.map(block, RELATION_A.subList(0, 2), null));

        assertThat(full).isNotEqualTo(reduced);
    }

    @Test
    @DisplayName("the Sync Note participates in the canonical JSON (a note write is echo-recognizable)")
    void sync_note_participates_in_canonical_json() throws JsonProcessingException {
        TimeBlockSnapshot block = block();

        String without = MAPPER.writeValueAsString(
            NotionTimeBlockMapper.map(block, RELATION_B, null));
        String with = MAPPER.writeValueAsString(
            NotionTimeBlockMapper.map(block, RELATION_B, "conflict note"));

        assertThat(without).isNotEqualTo(with);
    }

    @Test
    @DisplayName("read-only guard: the Cycles rollup and the Tasks-side synced property are rejected (CA-9)")
    void read_only_properties_are_guarded() {
        assertThat(NotionSchema.READ_ONLY_PROPERTIES).contains("Cycles", "Time Blocks");
        assertThatThrownBy(() -> NotionSchema.assertWritable(Map.of("Cycles", Map.of())))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotionSchema.assertWritable(Map.of("Time Blocks", Map.of())))
            .isInstanceOf(IllegalStateException.class);
    }

    private static TimeBlockSnapshot block() {
        return new TimeBlockSnapshot(BLOCK_ID, USER_ID, START, START.plusMinutes(90), "PLANNED",
            "PLANNER", "Theme", null, 90, null, null,
            List.of(new TimeBlockMemberSnapshot(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), "Task A", 90, 0)));
    }
}
