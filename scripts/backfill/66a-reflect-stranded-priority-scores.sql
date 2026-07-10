-- #66a / HU-01b backfill — reflect stranded SYSTEM-owned Priority Scores to Notion (ADR-020).
--
-- Context
-- -------
-- Before the #66a fix, editing a task IN NOTION recomputed and persisted priority_score /
-- urgency_score, but the only outbox event carried source_system=NOTION. The Notion propagator's
-- loop protection (RF-17) ignores its own origin, so the SYSTEM-authored score never reached Notion:
-- it is stranded in core_executable, invisible in the Notion "Priority Score" / "Urgence" columns
-- (~136 rows in prod).
--
-- What this does
-- --------------
-- Stages ONE outbox event per affected row with source_system=SYSTEM and event_type
-- ExecutableUpdatedEvent (the same event the on-event reflection and the daily tick already use — no
-- new event type). The OutboxWorker drains each one, the Notion propagator re-reads the row and
-- mirrors the current priority_score / urgency_score. It is emitted UNCONDITIONALLY (it does NOT go
-- through the Prioritizer's epsilon "unchanged" guard): the score already sits in core_executable, so
-- a re-score would report "unchanged" and stage nothing — yet the value was never mirrored. The write
-- is a full-mirror, idempotent PATCH; a row whose Notion page already matches is re-written harmlessly.
--
-- Invariants
-- ----------
--  * Writes ONLY outbox_events. It never touches core_executable, so priority_computed_at is
--    preserved verbatim — re-stamping it to now() would make ADR-020 treat these rows as freshly
--    prioritized and reorder the agenda. Do not add any UPDATE on core_executable here.
--  * Update-only reflection: the Notion propagator skips rows without a Notion sync_mapping, so this
--    never fabricates a page (regression 9921c80). The join to sync_mappings already scopes the set
--    to mapped rows.
--  * Excludes system_generated rows (internal accounting, never mirrored) and AGENDA (score-less).
--
-- Selection
-- ---------
-- Every NOTION-mapped, user-owned executable that carries a computed score (priority_score IS NOT
-- NULL). This is the superset that contains the stranded rows; already-consistent rows converge to a
-- no-op mirror write, so emitting for the whole mapped-and-scored set is safe and needs no in-SQL
-- reproduction of the application checksum.
--
-- Operational notes (run by Daniel against prod)
-- ----------------------------------------------
--  * Review the SELECT count first (see the preview query below) — expected ~136 rows.
--  * Run inside a transaction; the OutboxWorker drains via LISTEN/NOTIFY once committed.
--  * Safe to re-run: a second run stages duplicate reflections, each converging to the same
--    idempotent mirror write. Prefer a single run to avoid needless Notion API calls.

-- Preview (run first, does not write): how many reflections would be staged.
-- SELECT count(*)
-- FROM core_executable e
-- JOIN sync_mappings m
--   ON m.local_id = e.id AND m.external_system = 'NOTION'
-- WHERE e.priority_score IS NOT NULL
--   AND e.system_generated = false
--   AND e.type <> 'AGENDA';

BEGIN;

INSERT INTO outbox_events (
    id, aggregate_type, aggregate_id, event_type, payload, source_system, occurred_at
)
SELECT
    gen_random_uuid(),
    'CORE_EXECUTABLE',
    e.id::text,
    'ExecutableUpdatedEvent',
    '{"operation":"UPDATED"}'::jsonb,
    'SYSTEM',
    now()
FROM core_executable e
JOIN sync_mappings m
  ON m.local_id = e.id
 AND m.external_system = 'NOTION'
WHERE e.priority_score IS NOT NULL
  AND e.system_generated = false
  AND e.type <> 'AGENDA';

COMMIT;
