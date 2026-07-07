package com.hyperbrain.sync.domain.service;

import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.model.NotionCyclePage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionCycleInboundMapper — Notion → domain (HU-14, ADR-011)")
class NotionCycleInboundMapperTest {

    private static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("maps every attribute of a fully populated page")
    void maps_full_page() {
        // Given
        NotionCyclePage page = new NotionCyclePage(
            "cycle000000000000000000000000001",
            OffsetDateTime.of(2026, 7, 7, 15, 0, 0, 0, ZoneOffset.UTC),
            false,
            "Sprint 2", "MCI", "2026-07-01", "2026-07-14", false);

        // When
        CycleSnapshot snapshot = NotionCycleInboundMapper.toSnapshot(page, ID, USER_ID);

        // Then
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(new CycleSnapshot(
            ID, USER_ID, "Sprint 2", "MCI", "ACTIVE",
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14)));
    }

    @Test
    @DisplayName("Inactive checkbox maps to COMPLETED; unchecked or missing maps to ACTIVE")
    void maps_inactive_to_status() {
        assertThat(NotionCycleInboundMapper.toSnapshot(page("Routine", true), ID, USER_ID).status())
            .isEqualTo("COMPLETED");
        assertThat(NotionCycleInboundMapper.toSnapshot(page("Routine", false), ID, USER_ID).status())
            .isEqualTo("ACTIVE");
        assertThat(NotionCycleInboundMapper.toSnapshot(page("Routine", null), ID, USER_ID).status())
            .isEqualTo("ACTIVE");
    }

    @ParameterizedTest(name = "Type \"{0}\" → {1}")
    @CsvSource({"MCI, MCI", "Routine, ROUTINE", "Phase, PHASE", "Whatever, PHASE", ", PHASE"})
    @DisplayName("maps the Type select, degrading unknown options to PHASE")
    void maps_type_select(String notionType, String domainType) {
        assertThat(NotionCycleInboundMapper.mapType(notionType)).isEqualTo(domainType);
    }

    @Test
    @DisplayName("date parsing takes the date part and tolerates malformed values")
    void parses_dates() {
        assertThat(NotionCycleInboundMapper.parseDate("2026-07-01")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(NotionCycleInboundMapper.parseDate("2026-07-01T08:00:00-05:00"))
            .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(NotionCycleInboundMapper.parseDate(null)).isNull();
        assertThat(NotionCycleInboundMapper.parseDate("bad")).isNull();
    }

    private static NotionCyclePage page(String type, Boolean inactive) {
        return new NotionCyclePage("cycle000000000000000000000000002", null, false,
            "Sprint 2", type, null, null, inactive);
    }
}
