package com.hyperbrain.planner.domain.service;

import com.hyperbrain.planner.domain.model.EnergyProfile;
import com.hyperbrain.planner.domain.model.MciWig;
import com.hyperbrain.planner.domain.model.OccupiedInterval;
import com.hyperbrain.planner.domain.model.PlannerDayState;
import com.hyperbrain.planner.domain.model.SchedulableExecutable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Pure, deterministic digest of a day's generation input (HU-01c H2 idempotency key). The single
 * materialization owner claims {@code (user, day, hash)} before generating, so the hash must capture
 * <em>exactly</em> the state that changes the plan and <em>nothing volatile</em> that would defeat
 * deduplication.
 *
 * <p><b>Included</b> — every determinant of the deterministic floor's output: the ranked executables
 * with their rank and remaining-effort inputs, the hard walls (existing blocks + read-only AGENDA
 * windows), the WIG portfolio, the resolved energy (F3 margin / F6 quota), the {@code dataComplete}
 * flag, and the planning-window bounds. The window bounds are the derived <b>temporal frontier</b>:
 * for a full-day run the lower bound is {@code wake} (stable across the day), for a replan it is
 * {@code max(wake, T)} — the «nuevo borde temporal» that legitimately makes a later replan a new plan.
 *
 * <p><b>Excluded / normalized</b> — the raw reference instant {@code T} at sub-minute precision. The
 * window bounds are truncated to the minute so a redelivery of the same job (same frozen {@code T})
 * hashes identically, while a genuine button press a minute later hashes differently and re-plans.
 * No wall clock, no monotonic counter, no message id enters the digest.
 *
 * <p>Design note: the input to hash is precisely the {@link PlannerDayState} the floor consumes, so
 * "hash of the input" is literal — there is no second projection to drift from the generator.
 */
public class AgendaInputHasher {

    private static final char FIELD = '|';
    private static final char RECORD = '\n';

    /**
     * Computes the stable hex digest of the day's generation input.
     *
     * <p>The walls are passed explicitly rather than read from {@code state.occupied()} because the
     * hash must exclude the run's own regenerable output: the same-day {@code PLANNER}/{@code PLANNED}
     * blocks a prior materialization created are re-read as walls, so hashing them would make a
     * redelivery hash differently and defeat deduplication. The caller passes the walls minus those
     * regenerable blocks; the floor still plans against the full {@code state.occupied()}.
     *
     * @param state the resolved planner day state the floor will consume; never null
     * @param walls the hard walls to fold into the digest, excluding the run's regenerable blocks;
     *              never null
     * @return a lowercase hex SHA-256 digest; never null
     */
    public String hash(PlannerDayState state, List<OccupiedInterval> walls) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(state.windowStart().truncatedTo(ChronoUnit.MINUTES)).append(FIELD)
            .append(state.windowEnd().truncatedTo(ChronoUnit.MINUTES)).append(FIELD)
            .append(state.dataComplete()).append(RECORD);
        appendEnergy(canonical, state.energyProfile());
        appendRanked(canonical, state);
        appendWalls(canonical, walls);
        appendWig(canonical, state);
        return digest(canonical.toString());
    }

    /**
     * The idempotency digest for a replan whose start day has no forward window (a late replan at/after
     * bedtime): there is no {@link PlannerDayState} to hash, yet the 48 h run still plans the following
     * days, so the claim needs a stable key. It digests the replan anchor — the user, the start day, and
     * the frozen reference instant truncated to the minute — so a redelivery of the same job dedupes
     * while a genuinely later button press (a new minute) hashes anew and replans.
     *
     * @param userId     the replanning user; never null
     * @param startDay   the local start day of the horizon; never null
     * @param occurredAt the frozen reference instant of the replan; never null
     * @return a lowercase hex SHA-256 digest; never null
     */
    public String replanAnchorHash(UUID userId, LocalDate startDay, OffsetDateTime occurredAt) {
        String canonical = new StringJoiner(String.valueOf(FIELD))
            .add("REPLAN-EMPTY")
            .add(userId.toString())
            .add(startDay.toString())
            .add(occurredAt.truncatedTo(ChronoUnit.MINUTES).toString())
            .toString();
        return digest(canonical);
    }

    private void appendEnergy(StringBuilder canonical, EnergyProfile energy) {
        canonical.append("E").append(FIELD)
            .append(energy.tier()).append(FIELD)
            .append(energy.chaosMarginFraction()).append(FIELD)
            .append(energy.highLoadQuota()).append(RECORD);
    }

    private void appendRanked(StringBuilder canonical, PlannerDayState state) {
        // Rank order is a determinant, so the ranked list is hashed in the order the read port
        // supplied (priority-score desc) — never sorted here.
        for (SchedulableExecutable e : state.rankedExecutables()) {
            new Joiner(canonical, "R")
                .add(e.id())
                .add(e.type())
                .add(e.rankingScore())
                .add(e.inProgress())
                .add(e.energyDrain())
                .add(e.learnedUnitCost())
                .add(e.pendingSubtasks())
                .add(e.estimatedMinutes())
                .add(e.settledActualMinutes())
                .add(e.dueInstant())
                .add(e.cycleId())
                .end();
        }
    }

    private void appendWalls(StringBuilder canonical, List<OccupiedInterval> walls) {
        // Walls carry no intrinsic order from the read port, so sort by start for a stable digest.
        walls.stream()
            .sorted(Comparator.comparing(OccupiedInterval::start).thenComparing(OccupiedInterval::end))
            .forEach(w -> new Joiner(canonical, "W")
                .add(w.executableId())
                .add(w.start())
                .add(w.end())
                .add(w.readOnlyAgenda())
                .end());
    }

    private void appendWig(StringBuilder canonical, PlannerDayState state) {
        // The portfolio order (required-pace) is a determinant of the reservation, so it is preserved.
        for (MciWig w : state.wigPortfolio()) {
            new Joiner(canonical, "G")
                .add(w.mciCycleId())
                .add(w.leadMeasureId())
                .add(w.aggregatedProgress())
                .add(w.remainingFraction())
                .add(w.completed())
                .add(w.endDate())
                .add(w.receivedBlockYesterday())
                .add(w.degradedDaysWithoutBlock())
                .end();
        }
    }

    private static String digest(String canonical) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Small builder that renders one record with null-safe, field-delimited components. */
    private static final class Joiner {
        private final StringBuilder target;
        private final StringJoiner fields;

        private Joiner(StringBuilder target, String tag) {
            this.target = target;
            this.fields = new StringJoiner(String.valueOf(FIELD));
            this.fields.add(tag);
        }

        private Joiner add(Object value) {
            fields.add(value == null ? "~" : value.toString());
            return this;
        }

        private void end() {
            target.append(fields).append(RECORD);
        }
    }
}
