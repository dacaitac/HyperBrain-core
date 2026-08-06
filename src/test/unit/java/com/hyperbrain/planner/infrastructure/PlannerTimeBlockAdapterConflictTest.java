package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.sync.domain.model.TimeBlockEditOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D5 conflict handling of {@link PlannerTimeBlockAdapter} at the unit seam (mocked JDBC): the
 * pre-check SELECT closes the deterministic cases, and the unique-index race the pre-check
 * cannot see (TOCTOU — a concurrent writer claims the executable between the SELECT and the
 * INSERT) degrades to a <em>rejection</em>, never an unhandled constraint violation that would
 * poison the ingestion transaction. The full deterministic MOVE is covered end-to-end in
 * {@code NotionTimeBlockInboundIT}.
 */
@DisplayName("PlannerTimeBlockAdapter — D5 pre-check rejections and the TOCTOU net (ADR-038)")
class PlannerTimeBlockAdapterConflictTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLOCK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEMBER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final OffsetDateTime START =
        OffsetDateTime.of(2026, 8, 5, 9, 0, 0, 0, ZoneOffset.ofHours(-5));

    private JdbcTemplate jdbcTemplate;
    private PlannerTimeBlockAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        adapter = new PlannerTimeBlockAdapter(jdbcTemplate);
        // Walls clear and the user's zone resolves; the scenarios differ only on the D5 check.
        when(jdbcTemplate.queryForObject(contains("type = 'AGENDA'"), eq(Boolean.class),
            any(Object[].class))).thenReturn(false);
        when(jdbcTemplate.queryForObject(contains("b.id <> ?"), eq(Boolean.class),
            any(Object[].class))).thenReturn(false);
        when(jdbcTemplate.queryForList(contains("FROM sys_user"), eq(String.class),
            any(Object[].class))).thenReturn(List.of("America/Bogota"));
    }

    @Test
    @DisplayName("TOCTOU net: a 23505 on the member INSERT becomes a rejection and the empty block is undone")
    void unique_index_race_degrades_to_rejection() {
        // Given the pre-check sees the executable as free…
        when(jdbcTemplate.queryForList(contains("FROM core_time_block_member m"),
            any(Object[].class))).thenReturn(List.of());
        // …the block row inserts, but a concurrent writer wins the member insert (unique index)
        when(jdbcTemplate.update(contains("'PLANNED', 'USER'"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO core_time_block_member"),
            any(Object[].class))).thenThrow(new DuplicateKeyException("uq_core_time_block_member"));
        when(jdbcTemplate.update(contains("DELETE FROM core_time_block"), any(Object[].class)))
            .thenReturn(1);

        // When
        TimeBlockEditOutcome outcome = adapter.createUserBlock(
            USER_ID, BLOCK_ID, START, START.plusHours(1), "theme", List.of(MEMBER_ID));

        // Then — rejected, not thrown; the anchored-but-memberless block is deleted again
        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.rejections())
            .anySatisfy(reason -> assertThat(reason).contains("concurrently"));
        verify(jdbcTemplate).update(contains("DELETE FROM core_time_block"), any(Object[].class));
    }

    @Test
    @DisplayName("pre-check: an executable already settled that day is rejected before any INSERT")
    void settled_holder_rejects_before_insert() {
        // Given the D5 pre-check finds settled work holding the executable that day
        when(jdbcTemplate.queryForList(contains("FROM core_time_block_member m"),
            any(Object[].class)))
            .thenReturn(List.of(Map.of("block_id", UUID.randomUUID(), "block_status", "SETTLED")));

        // When
        TimeBlockEditOutcome outcome = adapter.createUserBlock(
            USER_ID, BLOCK_ID, START, START.plusHours(1), "theme", List.of(MEMBER_ID));

        // Then — nothing was created at all
        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.rejections())
            .anySatisfy(reason -> assertThat(reason).contains("settled"));
        verify(jdbcTemplate, never()).update(contains("'PLANNED', 'USER'"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO core_time_block_member"),
            any(Object[].class));
    }
}
