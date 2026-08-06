package com.hyperbrain.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperbrain.sync.domain.NotionPageNotFoundException;
import com.hyperbrain.sync.domain.model.NotionPageEditState;
import com.hyperbrain.sync.domain.model.SyncMapping;
import com.hyperbrain.sync.domain.port.out.NotionPort;
import com.hyperbrain.sync.infrastructure.NotionPageParser;
import com.hyperbrain.sync.infrastructure.NotionSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Outbound counterpart of the CA-29 monotonicity guard (ADR-020 burst protection), extracted so
 * every full-mirror Notion write-back — executables, cycles and time blocks alike — shares one
 * copy of the rule: decides whether a write would clobber a human edit still in flight.
 *
 * <p>Re-reads the page and yields to a <em>person</em>'s edit — the reliable signal being
 * {@code last_edited_by} (Notion truncates {@code last_edited_time} to the minute, exactly the
 * width of an edit burst, so the timestamp alone gives false negatives). Bias, opposite to CA-29
 * (which uses strictly-older to keep same-minute human edits inbound): this discards on
 * <em>equal-or-newer</em>, so a same-minute human edit wins over the write-back rather than
 * being overwritten. The actor gate is what makes the equal-minute case decidable — the Core's
 * own just-written page also carries the current minute, but as {@code last_edited_by} = the
 * integration bot, so it never blocks the Core's own follow-up writes.
 *
 * <p>Inert when the integration bot id is not configured (the actor cannot be identified): the
 * guard returns false and the write-back proceeds as before.
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.notion", name = "enabled", havingValue = "true")
public class NotionHumanEditGuard {

    private static final Logger log = LoggerFactory.getLogger(NotionHumanEditGuard.class);

    private final NotionPort notion;
    private final NotionPageParser pageParser;
    private final NotionSyncProperties properties;
    private final ObjectMapper objectMapper;

    public NotionHumanEditGuard(
        NotionPort notion,
        NotionPageParser pageParser,
        NotionSyncProperties properties,
        ObjectMapper objectMapper
    ) {
        this.notion = notion;
        this.pageParser = pageParser;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns whether a full-mirror write-back of the given page must be discarded because a
     * person is editing it. Performs one Notion page read; call it only on genuine domain
     * changes (reflections skip it by design).
     *
     * @param externalId the Notion page id about to be written
     * @param mapping    the page's {@code sync_mapping} (for {@code last_synced_at})
     * @return true when a human edit is in flight and the write-back must yield
     */
    public boolean hasHumanEditInFlight(String externalId, SyncMapping mapping) {
        String botUserId = NotionPageParser.normalizeId(properties.getBotUserId());
        if (botUserId == null || botUserId.isBlank()) {
            return false;
        }
        NotionPageEditState edit;
        try {
            edit = pageParser.parseEditState(objectMapper.readTree(notion.retrievePage(externalId)));
        } catch (NotionPageNotFoundException ex) {
            return false; // gone: let the update path 404 and repair the mapping (CA-15)
        } catch (JsonProcessingException ex) {
            log.warn("Unparseable Notion page {} on the outbound guard; proceeding with write-back",
                externalId);
            return false;
        }
        boolean editedByBot = edit.lastEditedById() == null
            || edit.lastEditedById().equals(botUserId);
        if (editedByBot) {
            return false; // the last touch was the Core's own write — no human edit in flight
        }
        OffsetDateTime lastSyncedAt = mapping.lastSyncedAt();
        OffsetDateTime lastEdited = edit.lastEditedTime();
        // Person edited: discard unless their edit is strictly older than our last sync (already
        // reconciled). Missing timestamps fall back to the actor signal alone (discard, to be safe).
        return lastSyncedAt == null || lastEdited == null || !lastEdited.isBefore(lastSyncedAt);
    }
}
