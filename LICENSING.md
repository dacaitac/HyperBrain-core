# Estrategia de licenciamiento por módulo — HyperBrain-core

> **Naturaleza de este documento.** Es una **referencia orientativa y no vinculante**
> para decisiones futuras de licenciamiento. **No** cambia la licencia efectiva de
> nada: hoy **todo HyperBrain-core está bajo AGPLv3** ([LICENSE](LICENSE) ·
> [NOTICE](NOTICE)). Este archivo solo mapea qué módulos son candidatos naturales a
> permanecer bajo AGPL frente a cuáles podrían separarse a una licencia comercial en
> el futuro. Cualquier cambio real de licencia será una decisión explícita del
> titular (Daniel Caita), reflejada en un ADR y en el `NOTICE`, y **nunca retroactiva**
> sobre versiones ya publicadas bajo AGPLv3.

## Contexto estructural

HyperBrain-core es un **monolito modular de un solo módulo Gradle** (`hyperbrain-core`);
no es un proyecto multi-módulo de Gradle. Los "módulos" son **módulos DDD lógicos**,
materializados como paquetes bajo `com.hyperbrain.<módulo>`, con sus capas
`domain` / `application` / `infrastructure`. Las fronteras entre ellos se verifican
**estructuralmente con ArchUnit**.

Esa modularidad verificada es, precisamente, lo que hace **técnicamente viable** un
esquema open-core: un módulo con dependencias entrantes controladas puede, en el
futuro, extraerse o relicenciarse sin desgarrar el resto del sistema. Este documento
no ejecuta esa separación —no mueve código ni cambia el build—; solo la anticipa.

## Mapeo actual de módulos

Módulos presentes hoy en `src/main/java/com/hyperbrain/`:

| Módulo | Rol | Candidatura de licencia | Racional |
| :--- | :--- | :--- | :--- |
| `core` | Dominio central: agregados base, `Cycle`/executables, invariantes compartidas del modelo. | **AGPL indefinido** | Es el sustrato del que dependen todos los demás. Mantenerlo abierto es lo que da sentido al "open" de open-core. |
| `shared` | Infraestructura y utilidades transversales (contratos, plumbing común). | **AGPL indefinido** | Plataforma común; sin valor monetizable aislado y con dependencias entrantes desde todos los módulos. |
| `sync` | Puente de integración Apple/Notion ↔ PostgreSQL: merge source-aware, propagadores, outbox. | **AGPL indefinido** | Infraestructura de integración del ecosistema, no un diferencial de producto en sí mismo. |
| `prioritizer` | Cálculo del **Priority Score** y atributos de priorización. | **Candidato comercial** | Algoritmo de priorización = valor diferencial directo del producto. |
| `planner` | Generación de la agenda diaria y de los bloques de tiempo (motor determinista). | **Candidato comercial** | La planificación automática es una capacidad central monetizable. |
| `cognitive` | Capa LLM: comité de roles, enriquecimiento de agenda, voz de coach (ADR-005/019/029). | **Candidato comercial** | La capa de IA es el diferencial más claro y de mayor coste; candidata natural a licencia comercial. |
| `finance` | Dominio financiero: transacciones, categorías, alertas de presupuesto. | **Candidato comercial** | Vertical de funcionalidad con valor de producto independiente. |
| `learning` | Dominio de aprendizaje: FSRS, scheduling de repasos, sesiones de estudio. | **Candidato comercial** | Vertical de funcionalidad con valor de producto independiente. |

> **Nota:** el módulo `gateway` que aparece en documentación histórica fue eliminado
> del monolito por [ADR-014](https://github.com/dacaitac/HyperBrain-docs) (ingesta
> Lambda-first) y no existe como paquete; no aplica a este mapeo.

## Principios de la separación (si algún día ocurre)

1. **Sin retroactividad.** Un cambio de licencia sobre un módulo solo afecta a
   versiones posteriores de ese módulo. Lo ya liberado bajo AGPLv3 sigue bajo AGPLv3.
2. **El núcleo permanece abierto.** `core`, `shared` y `sync` están previstos para
   permanecer bajo AGPL indefinidamente; sin ellos el open-core deja de serlo.
3. **Decisión explícita y documentada.** Toda separación se decide por ADR, se refleja
   en `NOTICE` y se comunica; no ocurre por omisión.
4. **La titularidad lo habilita.** El dual-licensing solo es posible porque las
   contribuciones externas ceden una licencia amplia vía el CLA
   ([CONTRIBUTING.md](CONTRIBUTING.md)).

## Referencias

- [LICENSE](LICENSE) — GNU AGPLv3 (texto completo).
- [NOTICE](NOTICE) — titularidad y estrategia open-core.
- [CONTRIBUTING.md](CONTRIBUTING.md) — CLA y flujo de contribución.
