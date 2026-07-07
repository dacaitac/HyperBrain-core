package com.hyperbrain.sync.application;

/**
 * Result of processing one inbound Notion page state (HU-14): what the sync services decided
 * to do with it. Lets the backfill and the tests distinguish convergent no-ops from writes.
 */
public enum SyncOutcome {
    /** No mapping existed; the entity and its {@code sync_mapping} were created (CA-28). */
    CREATED,
    /** A mapping existed; the entity was updated in place (CA-28). */
    UPDATED,
    /** The page state was older than the last synced state; discarded (CA-29). */
    SKIPPED_STALE,
    /** The page state was identical to the last synced state; discarded (CA-4/CA-20). */
    SKIPPED_ECHO,
    /** The page is archived or trashed; entity and mapping were removed (CA-7). */
    DELETED
}
