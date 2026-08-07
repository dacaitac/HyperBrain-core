package com.hyperbrain.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A read-only lens over the deployed block model for the tests that are about <b>where the planner puts
 * things</b>, not about how a block is stored.
 *
 * <p>Since ADR-039 a block is a {@code core_executable} of type {@code TIME_BLOCK} holding its members
 * through {@code container_block_id}, and ADR-040 stops the planner writing the frozen
 * {@code core_time_block} table altogether. The placement tests assert on hours, counts and ordering;
 * making each of them re-learn the join would bury what they are actually checking. This view projects
 * one row per block with the column names those assertions already speak, so they keep reading as
 * statements about the day.
 *
 * <p>{@code executable_id} is the block's <b>first</b> member by container order — the anchor. The
 * persistence contract itself (containment columns, slot identity, withdrawal, write suppression) is
 * asserted against the real tables in {@code PlannerBlockMembershipIT}; nothing verifies the model
 * through this lens.
 */
public final class PlannerBlockView {

    private static final String CREATE_VIEW_SQL = """
        CREATE OR REPLACE VIEW planner_blocks AS
        SELECT b.id,
               b.user_id,
               b.start_time  AS date_start,
               b.end_time    AS date_end,
               b.status,
               b.origin,
               b.description AS reason,
               b.name        AS theme,
               b.template_slot_id,
               (SELECT m.id FROM core_executable m
                 WHERE m.container_block_id = b.id
                 ORDER BY m.container_ord NULLS LAST, m.id
                 LIMIT 1)    AS executable_id
        FROM core_executable b
        WHERE b.type = 'TIME_BLOCK'
        """;

    private PlannerBlockView() {
    }

    /**
     * Creates (or refreshes) the lens. Idempotent, so it is safe to call from every {@code @BeforeEach}.
     *
     * @param jdbcTemplate the test's template; never null
     */
    public static void create(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(CREATE_VIEW_SQL);
    }
}
