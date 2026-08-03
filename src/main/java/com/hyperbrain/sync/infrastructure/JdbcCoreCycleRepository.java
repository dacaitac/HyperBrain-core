package com.hyperbrain.sync.infrastructure;

import com.hyperbrain.sync.domain.model.CycleSnapshot;
import com.hyperbrain.sync.domain.port.out.CoreCycleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link CoreCycleRepository}. Writes only the columns mapped from Notion
 * (HU-14); WOOP columns keep their values or defaults.
 */
@Repository
class JdbcCoreCycleRepository implements CoreCycleRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO core_cycle (id, user_id, parent_cycle_id, name, type, status, start_date, end_date)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET
            parent_cycle_id = EXCLUDED.parent_cycle_id,
            name            = EXCLUDED.name,
            type            = EXCLUDED.type,
            status          = EXCLUDED.status,
            start_date      = EXCLUDED.start_date,
            end_date        = EXCLUDED.end_date
        """;

    private static final String DELETE_BY_ID_SQL = "DELETE FROM core_cycle WHERE id = ?";

    private static final String FIND_TYPE_SQL = "SELECT type FROM core_cycle WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    JdbcCoreCycleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsert(CycleSnapshot c) {
        jdbcTemplate.update(UPSERT_SQL,
            c.id(), c.userId(), c.parentCycleId(), c.name(), c.type(), c.status(),
            toDate(c.startDate()), toDate(c.endDate()));
    }

    @Override
    public Optional<String> findType(UUID id) {
        List<String> types = jdbcTemplate.query(FIND_TYPE_SQL,
            (rs, rowNum) -> rs.getString("type"), id);
        return types.stream().findFirst();
    }

    @Override
    public void deleteById(UUID id) {
        jdbcTemplate.update(DELETE_BY_ID_SQL, id);
    }

    private static Date toDate(LocalDate date) {
        return date != null ? Date.valueOf(date) : null;
    }
}
