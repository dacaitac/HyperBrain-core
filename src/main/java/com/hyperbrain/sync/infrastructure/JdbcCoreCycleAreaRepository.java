package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.port.out.CoreCycleAreaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC adapter for {@link CoreCycleAreaRepository} (ADR-036). Full-mirror replace per cycle:
 * delete-then-insert inside the caller's ingestion transaction. Idempotent — re-applying the same set
 * yields the same rows.
 */
@Repository
class JdbcCoreCycleAreaRepository implements CoreCycleAreaRepository {

    private static final String DELETE_BY_CYCLE_SQL =
        "DELETE FROM core_cycle_area WHERE cycle_id = ?";

    private static final String INSERT_SQL = """
        INSERT INTO core_cycle_area (cycle_id, area_id)
        VALUES (?, ?)
        ON CONFLICT (cycle_id, area_id) DO NOTHING
        """;

    private static final String FIND_AREAS_SQL =
        "SELECT area_id FROM core_cycle_area WHERE cycle_id = ?";

    private final JdbcTemplate jdbcTemplate;

    JdbcCoreCycleAreaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void replaceMembershipsForCycle(UUID cycleId, Set<UUID> areaIds) {
        jdbcTemplate.update(DELETE_BY_CYCLE_SQL, cycleId);
        for (UUID areaId : areaIds) {
            jdbcTemplate.update(INSERT_SQL, cycleId, areaId);
        }
    }

    @Override
    public Set<UUID> findAreaIdsByCycle(UUID cycleId) {
        List<UUID> ids = jdbcTemplate.query(FIND_AREAS_SQL,
            (rs, rowNum) -> rs.getObject("area_id", UUID.class), cycleId);
        return new HashSet<>(ids);
    }
}
