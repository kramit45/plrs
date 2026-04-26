# PLRS — Architecture

References §3.a (component boundaries) and §2.e (data flow) of the
design report. This document is the at-a-glance picture; the report is
the canonical reference.

## High-level component diagram

```mermaid
flowchart LR
  subgraph Client
    Browser[Student / Instructor / Admin browser]
  end

  subgraph PLRS-JVM["plrs-web (Spring Boot)"]
    direction TB
    Web[Thymeleaf web chain<br/>session cookies]
    Api[/api JSON chain<br/>JWT/]
    UC[plrs-application<br/>use cases]
    Domain[plrs-domain<br/>aggregates + ports]
    Infra[plrs-infrastructure<br/>JPA + Redis + Kafka adapters]
    Web --> UC
    Api --> UC
    UC --> Domain
    UC --> Infra
  end

  subgraph PythonSidecars
    ML[plrs-ml<br/>FastAPI :8000<br/>CF + content scorers]
    ETL[plrs-etl-worker<br/>Kafka → DW drain]
  end

  subgraph DataPlane
    PG[(Postgres 15<br/>plrs_ops + plrs_dw)]
    R[(Redis 7<br/>top-N cache + JWT allow-list)]
    K[(Kafka<br/>plrs.interactions / plrs.mastery)]
  end

  Browser -->|HTTPS| Web
  Browser -->|JWT| Api

  Infra <--> PG
  Infra <--> R
  Infra -->|outbox publisher| K

  K --> ETL
  ETL --> PG

  UC -->|HTTP+HMAC| ML
  ML <--> PG
  ML <--> R
```

## Module boundaries

The Java side is hexagonal — one Maven module per layer with ArchUnit
guarding the dependency direction.

| Module                | Depends on             | Holds                                                                 |
| --------------------- | ---------------------- | --------------------------------------------------------------------- |
| `plrs-domain`         | (nothing)              | Aggregates, value objects, repository ports, domain services          |
| `plrs-application`    | domain                 | Use cases (commands + queries), application services, scoring policy  |
| `plrs-infrastructure` | application + domain   | JPA entities + mappers, Redis/Kafka adapters, Flyway migrations, jobs |
| `plrs-web`            | infrastructure         | Spring Boot entrypoint, controllers, filters, Thymeleaf views, security |

ArchUnit rules in `plrs-domain/src/test/java/.../HexagonalArchitectureTest.java`
fail the build if any leaf points the wrong way.

## Data flow — student takes a quiz

```mermaid
sequenceDiagram
  autonumber
  participant B as Browser
  participant W as plrs-web
  participant U as SubmitQuizAttemptUseCase (TX-01)
  participant DB as Postgres (plrs_ops)
  participant K as Kafka
  participant E as plrs-etl-worker
  participant DW as Postgres (plrs_dw)
  participant ML as plrs-ml

  B->>W: POST /api/quiz-attempts
  W->>U: handle(SubmitQuizAttemptCommand)
  U->>DB: insert quiz_attempt
  U->>DB: upsert mastery_snapshot (version bump)
  U->>DB: insert outbox_event (mastery + interaction)
  U-->>W: SubmitQuizAttemptResult
  W-->>B: 201 + score
  Note over W,B: TX-04 post-commit hook<br/>evicts rec:topN:{uuid}
  W->>K: outbox publisher drains rows (5 s tick)
  K->>E: plrs.interactions consumer
  E->>DW: insert fact_interaction
  Note over ML,DW: nightly recompute job<br/>refreshes CF model from fact_interaction
  B->>W: GET /dashboard (next page load)
  W->>ML: POST /score (top-N for user)
  ML->>DB: read mastery + content
  ML-->>W: ranked slate
  W-->>B: dashboard HTML + slate
```

Steps (1)–(8) all complete inside one HTTP request; (9)–(11) happen
asynchronously. The cache eviction at TX-04 makes the *next* dashboard
visit trip a fresh slate computation against the new mastery.

## ML interaction

`plrs-application` calls plrs-ml via `MlScorerClient` (HTTP + HMAC SHA-256
on `X-Plrs-Signature`). The endpoints used:

- `POST /cf/similar`     — collaborative-filtering nearest-N items
- `POST /content/score`  — content-based vector score
- `POST /score`          — composite blend (used by the live recommender)
- `POST /eval/run`       — offline evaluation harness (admin-triggered)

The composite blender lives **inside the JVM** as a Spring service; the
Python service exposes the model artefacts and the evaluation
machinery. If plrs-ml is down, the in-process Composite scorer falls
back to the deterministic content-based path (verified by
`test/chaos/ml_down.sh`).

## Operational topology

- The JVM, Postgres, and Redis are critical. Their NFR-9 recovery
  budget is 30 s; verified by `test/chaos/postgres_restart.sh` (Hikari
  retry).
- plrs-ml, Kafka, plrs-etl-worker are non-critical. Per NFR-11,
  recommendations and quiz submission still serve 200 with any of them
  down (verified by the corresponding chaos scripts).
- The outbox table makes Kafka decoupling safe: messages persist with
  `delivered_at = NULL` and the publisher drains them on a 5 s tick.

## Where to look next

- Iteration scopes — `README.md` and `CHANGELOG.md`
- Per-step traceability — design report §2.c (FRs), §2.d (NFRs)
- Module boundaries enforcement — `plrs-domain/src/test/java/.../HexagonalArchitectureTest.java`
- Outbox publisher cadence — `plrs.outbox.drain.fixed-delay-ms` in `application.yml`
- Recommender pipeline — `plrs-application/src/main/java/com/plrs/application/recommendation/`
