# HyperBrain-core

[![CI](https://github.com/dacaitac/HyperBrain-core/actions/workflows/ci.yml/badge.svg)](https://github.com/dacaitac/HyperBrain-core/actions/workflows/ci.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

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

## Licencia

HyperBrain-core se distribuye bajo la **GNU Affero General Public License v3.0 (AGPLv3)** —
ver [`LICENSE`](LICENSE) y [`NOTICE`](NOTICE). Copyright © 2026 Daniel Caita.

El proyecto sigue un modelo **open-core**: hoy **todo el core es AGPLv3**. Gracias a su
arquitectura modular (DDD + ArchUnit), ciertos **módulos futuros orientados a monetización**
podrían separarse a una **licencia comercial cerrada**. Ese posible cambio **no es retroactivo**:
el código ya liberado bajo AGPLv3 permanece bajo AGPLv3 en las versiones en que se publicó. El
mapeo orientativo módulo-por-módulo está en [`LICENSING.md`](LICENSING.md).

Las **contribuciones externas** requieren firmar un **CLA** (ver [`CONTRIBUTING.md`](CONTRIBUTING.md)),
que cede al titular una licencia amplia y habilita el dual-licensing.
