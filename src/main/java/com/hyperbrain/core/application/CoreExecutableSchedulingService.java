package com.hyperbrain.core.application;

import com.hyperbrain.core.application.event.ExecutableOutboxEvents;
import com.hyperbrain.core.domain.port.in.ExecutableSchedulingService;
import com.hyperbrain.core.domain.port.out.ExecutableStateRepository;
import com.hyperbrain.shared.outbox.OutboxRepository;
import com.hyperbrain.sync.domain.model.ExecutableSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The {@code core}-side implementation of {@link ExecutableSchedulingService}.
 *
 * <p>The whole of it is one guard and one conditional write. The guard is the point: the planner may
 * choose the hour, the user owns the day, and an operation that merely <em>documented</em> that
 * boundary would be a convention. Refusing the write makes it an invariant.
 */
@Service
public class CoreExecutableSchedulingService implements ExecutableSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(CoreExecutableSchedulingService.class);

    private final ExecutableStateRepository stateRepo;
    private final OutboxRepository outboxRepo;

    public CoreExecutableSchedulingService(ExecutableStateRepository stateRepo,
                                           OutboxRepository outboxRepo) {
        this.stateRepo = stateRepo;
        this.outboxRepo = outboxRepo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retimeWithinDay(UUID executableId, OffsetDateTime start, OffsetDateTime end,
                                   ZoneId zone) {
        if (executableId == null || start == null || zone == null) {
            throw new IllegalArgumentException("executableId, start and zone must not be null");
        }
        List<ExecutableSnapshot> found = stateRepo.findAllById(List.of(executableId));
        if (found.isEmpty()) {
            return false;
        }
        ExecutableSnapshot current = found.get(0);
        if (current.startTime() == null) {
            throw new IllegalArgumentException(
                "executable " + executableId + " stands on no day, so it cannot be re-timed within one");
        }
        LocalDate currentDay = current.startTime().atZoneSameInstant(zone).toLocalDate();
        LocalDate targetDay = start.atZoneSameInstant(zone).toLocalDate();
        if (!currentDay.equals(targetDay)) {
            throw new IllegalArgumentException(
                "the day is the user's: " + executableId + " stands on " + currentDay
                    + " and may not be moved to " + targetDay);
        }
        if (!stateRepo.reschedule(executableId, start, end)) {
            return false;
        }
        outboxRepo.append(ExecutableOutboxEvents.updated(executableId));
        log.info("Executable {} re-timed to {} within {}", executableId, start, currentDay);
        return true;
    }
}
