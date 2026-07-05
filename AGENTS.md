# HyperBrain-core — Agent Context

This repo uses Claude Code as the primary AI agent. All rules, patterns, and conventions are in `CLAUDE.md` (symlinked from HyperBrain-docs via ADR-007).

See `CLAUDE.md` in this directory for the full agent brief.

**Key pointers for other agent tools:**
- Stack: Spring Boot 3 / Java 21 / Gradle, DDD monolith, EDA with Transactional Outbox
- Tests: `./gradlew build` (unit + Testcontainers integration)
- Architecture constraints: ArchUnit rules in `src/test/unit/java/com/hyperbrain/arch/ModuleIsolationTest.java`
- Approval policy: [docs/06-agents-and-skills/approval-policy.md](../HyperBrain-docs/docs/06-agents-and-skills/approval-policy.md)
