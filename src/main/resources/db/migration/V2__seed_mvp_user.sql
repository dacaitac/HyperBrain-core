-- Seed: default system user for single-user MVP (mirrors Infra 20260706120000)
-- Required by integration tests: handlers write to core_executable which has
-- a NOT NULL FK to sys_user. DataFixture.insertSystemUser() uses ON CONFLICT DO NOTHING
-- on top of this, so both are safe to coexist.

INSERT INTO sys_user (id, email, password_hash, role, status, timezone, settings)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'daniel@hyperbrain.local',
    'x',
    'ADMIN',
    'ACTIVE',
    'America/Bogota',
    '{}'
)
ON CONFLICT (id) DO NOTHING;
