-- V1: Core infrastructure tables
-- Owned by the Core module; domain tables will be added in the DDL v1 migration (issue #2).

CREATE EXTENSION IF NOT EXISTS vector;

-- Transactional Outbox: written atomically with domain events; read only by OutboxWorker.
CREATE TABLE IF NOT EXISTS outbox_events (
    id             UUID        PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(200) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_unprocessed
    ON outbox_events (created_at)
    WHERE processed_at IS NULL;

-- Deduplication store: Consumer checks this before invoking any IEventHandler.
CREATE TABLE IF NOT EXISTS processed_message (
    message_id   VARCHAR(256) PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
