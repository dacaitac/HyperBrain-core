package com.hyperbrain.planner.application;

import com.hyperbrain.sync.domain.model.CommandType;
import com.hyperbrain.sync.domain.model.Operation;
import com.hyperbrain.sync.domain.model.PendingWriteCommand;
import com.hyperbrain.sync.domain.model.ReminderPayload;
import com.hyperbrain.sync.domain.model.WriteCommand;
import com.hyperbrain.sync.domain.port.out.WriteCommandLogRepository;
import com.hyperbrain.sync.domain.port.out.WriteCommandPublisher;
import com.hyperbrain.sync.infrastructure.WriteCommandWireMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Emits the "no blocks today, planned for tomorrow" signal when a day yields no useful blocks (e.g. a
 * run after bedtime). The Planner never delivers empty reminders and never leaves the user in silence
 * (Triángulo de Control): it proposes the next day and tells the user, once.
 *
 * <p>Both agenda paths use this collaborator — the legacy synchronous morning dispatch
 * ({@code AgendaDeliveryService}) and the single-owner {@code AgendaJobConsumer} (HU-01c H2), which is
 * where this orchestration concern now lives so the result and its side effects belong to the one
 * component that materializes the day.
 *
 * <p><b>Exactly-once.</b> The command id and the signal's local id are deterministic per
 * {@code (user, day)}, so {@link WriteCommandLogRepository#upsertPending} is idempotent and the FIFO
 * publish is deduplicated by the local id: a re-emission for the same empty day never doubles the
 * signal. In the single-owner path the upstream materialization claim
 * ({@code planner_agenda_materialization}) additionally guarantees this runs at most once per
 * materialized input, so a redelivery is deduplicated before it ever reaches here.
 */
@Service
public class EmptyAgendaNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmptyAgendaNotifier.class);

    private static final String COMMAND_ID_NAMESPACE = "hyperbrain-agenda-empty-command:";
    private static final String SIGNAL_LOCAL_ID_NAMESPACE = "hyperbrain-agenda-empty-signal:";
    private static final String STATUS_PENDING = "PENDING";
    private static final String REMINDER_LIST_NAME = "HyperBrain";
    private static final String EMPTY_DAY_TITLE = "No agenda blocks today";
    private static final String EMPTY_DAY_BODY =
        "No useful blocks fit today's window — planned for tomorrow instead.";

    private final WriteCommandPublisher commandPublisher;
    private final WriteCommandLogRepository commandLogRepo;
    private final WriteCommandWireMapper wireMapper;

    public EmptyAgendaNotifier(
        WriteCommandPublisher commandPublisher,
        WriteCommandLogRepository commandLogRepo,
        WriteCommandWireMapper wireMapper
    ) {
        this.commandPublisher = commandPublisher;
        this.commandLogRepo = commandLogRepo;
        this.wireMapper = wireMapper;
    }

    /**
     * Emits the next-day proposal signal reminder directly, bypassing the block outbox path (there are
     * no blocks to carry). The command id is deterministic per {@code (user, day)} so a retried
     * dispatch never doubles the signal.
     *
     * @param userId          the user to notify; never null
     * @param today           the empty day being reported; never null
     * @param energyCriterion the readable energy-trim chain to append to the body; may be blank
     * @param now             the reference instant for the reminder payload; never null
     */
    public void proposeNextDay(UUID userId, LocalDate today, String energyCriterion,
                               OffsetDateTime now) {
        UUID commandId = deterministicId(COMMAND_ID_NAMESPACE, userId, today);
        UUID signalLocalId = deterministicId(SIGNAL_LOCAL_ID_NAMESPACE, userId, today);
        String body = energyCriterion != null && !energyCriterion.isBlank()
            ? EMPTY_DAY_BODY + "\n\n" + energyCriterion.trim()
            : EMPTY_DAY_BODY;
        ReminderPayload payload = new ReminderPayload(
            EMPTY_DAY_TITLE, body, now, false, 0, "", REMINDER_LIST_NAME);
        WriteCommand command =
            new WriteCommand(commandId, CommandType.REMINDER, Operation.CREATED, null, payload);

        commandLogRepo.upsertPending(new PendingWriteCommand(
            commandId, userId, signalLocalId, CommandType.REMINDER, Operation.CREATED, null,
            wireMapper.payloadJson(payload), STATUS_PENDING));
        commandPublisher.publish(command, signalLocalId.toString());
        log.info("No blocks fit today for user {} on {}; emitted next-day proposal signal {}",
            userId, today, commandId);
    }

    private static UUID deterministicId(String namespace, UUID userId, LocalDate day) {
        return UUID.nameUUIDFromBytes(
            (namespace + userId + ":" + day).getBytes(StandardCharsets.UTF_8));
    }
}
