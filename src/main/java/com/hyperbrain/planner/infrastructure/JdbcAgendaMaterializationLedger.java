package com.hyperbrain.planner.infrastructure;

import com.hyperbrain.planner.domain.port.out.AgendaMaterializationLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JDBC adapter for {@link AgendaMaterializationLedger} over {@code planner_agenda_materialization}.
 * The {@code ON CONFLICT DO NOTHING} on the {@code (user_id, agenda_date, input_hash)} key makes the
 * claim idempotent; the affected-row count tells the caller whether this input is new for the day.
 */
@Repository
public class JdbcAgendaMaterializationLedger implements AgendaMaterializationLedger {

    private static final String CLAIM_SQL = """
        INSERT INTO planner_agenda_materialization (user_id, agenda_date, input_hash, status)
        VALUES (?, ?, ?, 'MATERIALIZED')
        ON CONFLICT (user_id, agenda_date, input_hash) DO NOTHING
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgendaMaterializationLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean claim(UUID userId, LocalDate agendaDate, String inputHash) {
        return jdbcTemplate.update(CLAIM_SQL, userId, Date.valueOf(agendaDate), inputHash) == 1;
    }
}
