# HyperBrain-core

Backend del ecosistema **HyperBrain**: monolito Spring Boot 3 (Java 21, Gradle) con arquitectura
DDD modular y Event-Driven.

## Stack

- **Spring Boot 3.x**, Java 21, **Gradle** ([ADR-008](https://github.com/dacaitac/HyperBrain-docs))
- PostgreSQL + pgvector vía JPA/JDBC (SSoT)
- Amazon SQS + DLQ (EDA con patrón Transactional Outbox)
- Spring AI (módulo `cognitive`) · OpenRouter · Ollama-MLX

## Módulos DDD

`core` · `sync` · `finance` · `learning` · `cognitive` · `prioritizer` · `planner` · `gateway`

Las reglas de aislamiento entre módulos se validan con ArchUnit. Ver el detalle en
[`CLAUDE.md`](CLAUDE.md) y en la documentación viva del proyecto.

## Comandos

```bash
./gradlew test                        # Unit tests
./gradlew integrationTest             # Integración (Testcontainers: PG + LocalStack)
./gradlew build                       # Build completo (unit + integración + ensamblado)
./gradlew bootRun                     # Arrancar el core (requiere HyperBrain-Infra levantado)
```

## Documentación

La documentación de ingeniería vive en **HyperBrain-docs**. `CLAUDE.md` (symlink al brain de IA)
contiene las convenciones de dominio, arquitectura EDA y estándares de código del proyecto.
