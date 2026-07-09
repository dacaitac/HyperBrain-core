-- V1: Complete DDL for Testcontainers integration tests (ERD v2.0.0, S0-07).
-- Consolidated mirror of supabase/migrations/20260704120000–120600.
-- Flyway runs this ONLY in the integration-test profile (ephemeral Testcontainers DB).
-- The real Compose DB uses the individual Supabase migrations instead.
--
-- SYNC RULE: every change to a supabase/migrations/*.sql file MUST be reflected here.
-- See S0-07 (issue #29) for the rationale behind this dual-track schema strategy.

CREATE EXTENSION IF NOT EXISTS vector;

-- ─── Users ───────────────────────────────────────────────────────────────────

CREATE TABLE sys_user (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          TEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    role           TEXT NOT NULL DEFAULT 'USER'
                       CHECK (role IN ('ADMIN', 'USER')),
    status         TEXT NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'INACTIVE')),
    timezone       TEXT NOT NULL DEFAULT 'America/Bogota',
    settings       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sync_credential (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    provider       TEXT NOT NULL CHECK (provider IN ('NOTION', 'APPLE', 'N8N')),
    access_token   TEXT,
    refresh_token  TEXT,
    expires_at     TIMESTAMPTZ,
    -- Mirrors HyperBrain-Infra migration 20260706150000 (S0-07): idempotent token upsert (HU-10).
    UNIQUE (user_id, provider)
);

CREATE INDEX idx_sync_credential_user ON sync_credential (user_id);

-- ─── Core ────────────────────────────────────────────────────────────────────

-- Mirrors HyperBrain-Infra/supabase/migrations/20260708190000_adr015_cycle_unifies_project.sql
-- ADR-015: CORE_PROJECT is absorbed into CORE_CYCLE (type = PROJECT); the entity no longer exists.

CREATE TABLE core_cycle (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    -- ADR-015: free-form self-nesting models the GTD horizon ladder; parent type is not constrained
    parent_cycle_id  UUID REFERENCES core_cycle (id) ON DELETE SET NULL,
    name             TEXT NOT NULL,
    start_date       DATE,
    end_date         DATE,
    -- ADR-015: type absorbs the former CORE_PROJECT (PROJECT) and horizon levels (GOAL, OBJECTIVE)
    type             TEXT NOT NULL
                         CHECK (type IN ('MCI', 'GOAL', 'OBJECTIVE', 'PROJECT', 'PHASE', 'ROUTINE')),
    status           TEXT NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'COMPLETED')),
    woop_obstacle    TEXT,
    woop_plan        TEXT
);

CREATE INDEX idx_core_cycle_user   ON core_cycle (user_id);
CREATE INDEX idx_core_cycle_parent ON core_cycle (parent_cycle_id);

CREATE TABLE core_executable (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    parent_id          UUID REFERENCES core_executable (id) ON DELETE CASCADE,
    blocked_by         UUID REFERENCES core_executable (id) ON DELETE SET NULL,
    cycle_id           UUID REFERENCES core_cycle (id) ON DELETE SET NULL,
    name               TEXT NOT NULL,
    description        TEXT,
    type               TEXT NOT NULL
                           CHECK (type IN ('TASK', 'HABIT', 'LEAD_MEASURE',
                                           'ACTIVITY', 'AGENDA', 'LEARNING_SESSION')),
    status             TEXT NOT NULL DEFAULT 'TODO'
                           CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE',
                                             'FAILED', 'PLANNED', 'WAITING')),
    priority_score     DOUBLE PRECISION
                           CHECK (priority_score IS NULL OR priority_score BETWEEN 0 AND 1),
    urgency_score      DOUBLE PRECISION,
    effort_score       DOUBLE PRECISION
                           CHECK (effort_score IS NULL OR effort_score BETWEEN 0 AND 5),
    is_important       BOOLEAN NOT NULL DEFAULT false,
    frequency          DOUBLE PRECISION,
    -- FK to lrn_topic added after that table exists (ADR-012 D4, Notion Tasks parity)
    learning_topic_id  UUID,
    estimated_cost     NUMERIC(19, 4),
    current_streak     INTEGER NOT NULL DEFAULT 0,
    best_streak        INTEGER NOT NULL DEFAULT 0,
    last_completed_at  TIMESTAMPTZ,
    start_time         TIMESTAMPTZ,
    end_time           TIMESTAMPTZ,
    source_calendar    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_core_executable_user    ON core_executable (user_id);
CREATE INDEX idx_core_executable_parent  ON core_executable (parent_id);
CREATE INDEX idx_core_executable_cycle   ON core_executable (cycle_id);
CREATE INDEX idx_core_executable_status  ON core_executable (status);

CREATE TABLE core_execution_profile (
    executable_id      UUID PRIMARY KEY REFERENCES core_executable (id) ON DELETE CASCADE,
    estimated_minutes  INTEGER,
    energy_drain       INTEGER CHECK (energy_drain IS NULL OR energy_drain BETWEEN 1 AND 5),
    mental_load        INTEGER CHECK (mental_load IS NULL OR mental_load BETWEEN 1 AND 5),
    impact             INTEGER CHECK (impact IS NULL OR impact BETWEEN 1 AND 8),
    context_location   TEXT    CHECK (context_location IS NULL OR
                                      context_location IN ('CASA', 'OFICINA', 'RECADOS', 'ANY'))
);

-- Mirrors HyperBrain-Infra/supabase/migrations/20260708180000_planner_time_blocks.sql (ADR-013)
CREATE TABLE core_time_block (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executable_id            UUID NOT NULL REFERENCES core_executable (id) ON DELETE CASCADE,
    date_start               TIMESTAMPTZ NOT NULL,
    date_end                 TIMESTAMPTZ,
    actual_duration_minutes  INTEGER,
    status                   TEXT NOT NULL DEFAULT 'PLANNED'
                                 CHECK (status IN ('PLANNED', 'ACTIVE', 'SETTLED', 'EXPIRED')),
    origin                   TEXT NOT NULL DEFAULT 'PLANNER'
                                 CHECK (origin IN ('PLANNER', 'FOCUS', 'USER')),
    planned_minutes          INTEGER,
    settled_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_core_time_block_executable ON core_time_block (executable_id);
CREATE INDEX idx_core_time_block_open
    ON core_time_block (status, date_end)
    WHERE status IN ('PLANNED', 'ACTIVE');

-- Mirrors HyperBrain-Infra/supabase/migrations/20260708180000_planner_time_blocks.sql (ADR-013):
-- SYSTEM-owned focus & progress accounting on core_executable. The FK to core_time_block must be
-- added after that table exists.
ALTER TABLE core_executable
    ADD COLUMN progress             DOUBLE PRECISION
        CHECK (progress IS NULL OR progress BETWEEN 0 AND 1),
    ADD COLUMN system_generated     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN pending_reestimation BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN imputed_time_block_id UUID REFERENCES core_time_block (id) ON DELETE SET NULL;

CREATE INDEX idx_core_executable_imputed_block
    ON core_executable (imputed_time_block_id);

-- ─── Finance ─────────────────────────────────────────────────────────────────

CREATE TABLE fin_account (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id   UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    name      TEXT NOT NULL,
    type      TEXT NOT NULL CHECK (type IN ('ASSET', 'LIABILITY')),
    balance   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency  TEXT NOT NULL DEFAULT 'COP'
);

CREATE INDEX idx_fin_account_user ON fin_account (user_id);

CREATE TABLE fin_category (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    parent_id  UUID REFERENCES fin_category (id) ON DELETE SET NULL,
    name       TEXT NOT NULL,
    flow_type  TEXT NOT NULL CHECK (flow_type IN ('INCOME', 'EXPENSE'))
);

CREATE INDEX idx_fin_category_user   ON fin_category (user_id);
CREATE INDEX idx_fin_category_parent ON fin_category (parent_id);

CREATE TABLE fin_budget_template (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    category_id   UUID NOT NULL REFERENCES fin_category (id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    period        TEXT NOT NULL CHECK (period IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'CUSTOM')),
    limit_amount  NUMERIC(19, 4) NOT NULL,
    carry_policy  TEXT NOT NULL DEFAULT 'RESET'
                      CHECK (carry_policy IN ('ROLLOVER', 'RESET')),
    active        BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_fin_budget_template_user     ON fin_budget_template (user_id);
CREATE INDEX idx_fin_budget_template_category ON fin_budget_template (category_id);

CREATE TABLE fin_budget (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    category_id   UUID NOT NULL REFERENCES fin_category (id) ON DELETE CASCADE,
    template_id   UUID REFERENCES fin_budget_template (id) ON DELETE SET NULL,
    name          TEXT NOT NULL,
    start_date    DATE NOT NULL,
    end_date      DATE NOT NULL,
    limit_amount  NUMERIC(19, 4) NOT NULL,
    carry_policy  TEXT NOT NULL DEFAULT 'RESET'
                      CHECK (carry_policy IN ('ROLLOVER', 'RESET')),
    exceeded      BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_fin_budget_user     ON fin_budget (user_id);
CREATE INDEX idx_fin_budget_category ON fin_budget (category_id);
CREATE INDEX idx_fin_budget_template ON fin_budget (template_id);

CREATE TABLE fin_goal (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    cycle_id            UUID REFERENCES core_cycle (id) ON DELETE SET NULL,
    name                TEXT NOT NULL,
    goal_type           TEXT NOT NULL DEFAULT 'STANDARD'
                            CHECK (goal_type IN ('STANDARD', 'GENERAL_POOL')),
    target_amount       NUMERIC(19, 4),
    accumulated_amount  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status              TEXT NOT NULL DEFAULT 'SAVING'
                            CHECK (status IN ('SAVING', 'FUNDED', 'COMPLETED')),
    target_date         DATE
);

CREATE INDEX idx_fin_goal_user    ON fin_goal (user_id);
CREATE INDEX idx_fin_goal_cycle   ON fin_goal (cycle_id);

CREATE TABLE fin_transaction (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    executable_id           UUID REFERENCES core_executable (id) ON DELETE SET NULL,
    origin_account_id       UUID REFERENCES fin_account (id) ON DELETE SET NULL,
    destination_account_id  UUID REFERENCES fin_account (id) ON DELETE SET NULL,
    category_id             UUID REFERENCES fin_category (id) ON DELETE SET NULL,
    cycle_id                UUID REFERENCES core_cycle (id) ON DELETE SET NULL,
    goal_id                 UUID REFERENCES fin_goal (id) ON DELETE SET NULL,
    amount                  NUMERIC(19, 4) NOT NULL,
    currency                TEXT NOT NULL DEFAULT 'COP',
    description             TEXT,
    type                    TEXT NOT NULL CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    status                  TEXT NOT NULL DEFAULT 'COMPLETED'
                                CHECK (status IN ('PLANNED', 'PENDING', 'COMPLETED')),
    occurred_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fin_transaction_origin      ON fin_transaction (origin_account_id);
CREATE INDEX idx_fin_transaction_destination ON fin_transaction (destination_account_id);
CREATE INDEX idx_fin_transaction_category    ON fin_transaction (category_id);
CREATE INDEX idx_fin_transaction_cycle       ON fin_transaction (cycle_id);
CREATE INDEX idx_fin_transaction_goal        ON fin_transaction (goal_id);
CREATE INDEX idx_fin_transaction_executable  ON fin_transaction (executable_id);
CREATE INDEX idx_fin_transaction_occurred    ON fin_transaction (occurred_at);

CREATE TABLE fin_networth_snapshot (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    snapshot_date      DATE NOT NULL,
    total_assets       NUMERIC(19, 4) NOT NULL,
    total_liabilities  NUMERIC(19, 4) NOT NULL,
    net_worth          NUMERIC(19, 4) NOT NULL,
    UNIQUE (user_id, snapshot_date)
);

CREATE INDEX idx_fin_networth_snapshot_user ON fin_networth_snapshot (user_id);

-- ─── Learning ────────────────────────────────────────────────────────────────

CREATE TABLE lrn_topic (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    name              TEXT NOT NULL,
    description       TEXT,
    status            TEXT NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE', 'MASTERED', 'ARCHIVED')),
    current_score     INTEGER CHECK (current_score IS NULL OR current_score BETWEEN 0 AND 100),
    stability         DOUBLE PRECISION,
    difficulty        DOUBLE PRECISION,
    last_review_at    TIMESTAMPTZ,
    next_review_date  TIMESTAMPTZ
);

CREATE INDEX idx_lrn_topic_user        ON lrn_topic (user_id);
CREATE INDEX idx_lrn_topic_next_review ON lrn_topic (next_review_date);

-- ADR-012 D4: deferred FK — core_executable is created before lrn_topic in this consolidated DDL
ALTER TABLE core_executable
    ADD CONSTRAINT fk_core_executable_learning_topic
        FOREIGN KEY (learning_topic_id) REFERENCES lrn_topic (id) ON DELETE SET NULL;

CREATE INDEX idx_core_executable_learning ON core_executable (learning_topic_id);

CREATE TABLE lrn_assessment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id            UUID NOT NULL REFERENCES lrn_topic (id) ON DELETE CASCADE,
    executable_id       UUID REFERENCES core_executable (id) ON DELETE SET NULL,
    score_internals     INTEGER CHECK (score_internals IS NULL OR score_internals BETWEEN 0 AND 100),
    score_architecture  INTEGER CHECK (score_architecture IS NULL OR score_architecture BETWEEN 0 AND 100),
    score_production    INTEGER CHECK (score_production IS NULL OR score_production BETWEEN 0 AND 100),
    score_seniority     INTEGER CHECK (score_seniority IS NULL OR score_seniority BETWEEN 0 AND 100),
    score_general       INTEGER CHECK (score_general IS NULL OR score_general BETWEEN 0 AND 100),
    identified_gaps     TEXT,
    recommended_prompt  TEXT CHECK (recommended_prompt IS NULL OR
                                    recommended_prompt IN ('A', 'B', 'C', 'D', 'E')),
    assessed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lrn_assessment_topic      ON lrn_assessment (topic_id);
CREATE INDEX idx_lrn_assessment_executable ON lrn_assessment (executable_id);

-- ─── Telemetry & Brain ───────────────────────────────────────────────────────

CREATE TABLE tel_sleep_record (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    start_time        TIMESTAMPTZ NOT NULL,
    end_time          TIMESTAMPTZ,
    duration_minutes  INTEGER,
    sleep_score       INTEGER,
    stages            JSONB,
    collected_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tel_sleep_record_user ON tel_sleep_record (user_id);

CREATE TABLE tel_activity_stream (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    app_name          TEXT,
    window_title      TEXT,
    start_time        TIMESTAMPTZ NOT NULL,
    duration_seconds  INTEGER,
    is_afk            BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_tel_activity_stream_user ON tel_activity_stream (user_id);

CREATE TABLE brain_idea (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    title            TEXT,
    content          TEXT,
    idea_type        TEXT NOT NULL DEFAULT 'CAPTURE'
                         CHECK (idea_type IN ('CAPTURE', 'REFLECTION')),
    status           TEXT NOT NULL DEFAULT 'RAW'
                         CHECK (status IN ('RAW', 'REFINING', 'SOMEDAY',
                                           'REFERENCE', 'ARCHIVED', 'CONVERTED')),
    converted_to_id  UUID REFERENCES core_executable (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_brain_idea_user      ON brain_idea (user_id);
CREATE INDEX idx_brain_idea_converted ON brain_idea (converted_to_id);

CREATE TABLE context_event (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    source       TEXT NOT NULL CHECK (source IN ('MANUAL', 'SYSTEM', 'INTEGRATION')),
    provider     TEXT,
    event_type   TEXT,
    content      TEXT,
    payload      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_context_event_user ON context_event (user_id);

-- embedding dimension: 768 (nomic-embed-text via local Ollama-MLX, ADR-005).
-- Changing the model requires adjusting vector(N) and rebuilding the HNSW index.
CREATE TABLE rag_embedding (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    source_type  TEXT NOT NULL CHECK (source_type IN ('BRAIN_IDEA', 'INTERACTION', 'REFLECTION')),
    source_id    UUID,
    content      TEXT,
    embedding    vector(768),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rag_embedding_user   ON rag_embedding (user_id);
CREATE INDEX idx_rag_embedding_source ON rag_embedding (source_type, source_id);
CREATE INDEX idx_rag_embedding_hnsw
    ON rag_embedding USING hnsw (embedding vector_cosine_ops);

-- ─── Sync & Event Pipeline ───────────────────────────────────────────────────

CREATE TABLE sync_mappings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    local_id            UUID NOT NULL,
    external_system     TEXT NOT NULL CHECK (external_system IN ('NOTION', 'APPLE')),
    external_id         TEXT NOT NULL,
    last_known_checksum TEXT,
    sync_status         TEXT,
    last_synced_at      TIMESTAMPTZ,
    UNIQUE (external_system, external_id)
);

CREATE INDEX idx_sync_mappings_user  ON sync_mappings (user_id);
CREATE INDEX idx_sync_mappings_local ON sync_mappings (local_id);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  TEXT NOT NULL,
    aggregate_id    TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    processed       BOOLEAN NOT NULL DEFAULT false,
    processed_at    TIMESTAMPTZ,
    source_system   TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_events_unprocessed
    ON outbox_events (occurred_at)
    WHERE processed = false;

CREATE TABLE processed_message (
    message_id    TEXT PRIMARY KEY,
    event_type    TEXT,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Correlation log of WriteCommands emitted to apple-commands.fifo (HU-09c, ADR-010).
-- Mirrors HyperBrain-Infra/supabase/migrations/20260706130000_sync_write_commands.sql (S0-07).
CREATE TABLE sync_write_commands (
    command_id    UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES sys_user (id) ON DELETE CASCADE,
    local_id      UUID NOT NULL,
    command_type  TEXT NOT NULL CHECK (command_type IN ('REMINDER', 'CALENDAR_EVENT')),
    operation     TEXT NOT NULL CHECK (operation IN ('CREATED', 'UPDATED', 'DELETED')),
    entity_id     TEXT,
    payload       JSONB,
    status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPLIED', 'FAILED')),
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ
);

CREATE INDEX idx_sync_write_commands_pending
    ON sync_write_commands (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_sync_write_commands_local ON sync_write_commands (local_id);
