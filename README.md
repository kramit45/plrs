# PLRS — Personalized Learning Recommendation System

[![build](https://github.com/kramit45/plrs/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kramit45/plrs/actions/workflows/build.yml)
[![version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/kramit45/plrs/releases/tag/v1.0.0)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![IGNOU](https://img.shields.io/badge/IGNOU-MCSP--232-orange.svg)](docs/PROJECT_STATUS.md)

> **Project metadata**
> - **Title:** PLRS — Personalized Learning Recommendation System
> - **Course:** IGNOU MCA (MCAOL) MCSP-232
> - **Enrolment:** 2452345135
> - **Guide:** Himanshu Katiyar
> - **Regional Centre:** RC Ranchi
> - **Synopsis approved:** 30-Nov-2025
> - **Submission:** 30-Apr-2026

PLRS is the capstone project submitted for **IGNOU MCA course MCSP-232**.
It explores a recommendation system that tailors learning resources to
individual learners based on their profile, progress, and performance
signals, combining content-based and collaborative-filtering techniques
into a single deployable Java application.

## Status

**v1.0.0 — submission release.** All five iterations closed; tagged
`v1.0.0` for IGNOU evaluation.

Quick links: [Architecture](docs/ARCHITECTURE.md) ·
[Deployment](docs/DEPLOYMENT.md) ·
[Demo Script](DEMO_SCRIPT.md) ·
[Project Status](docs/PROJECT_STATUS.md) ·
[Submission](SUBMISSION.md) ·
[API docs](http://localhost:8080/swagger-ui.html) (live) ·
[Javadoc](docs/javadoc/) (regenerate via `mvn javadoc:aggregate`)

### Feature highlights

- **Hybrid recommender** — collaborative filtering + content-based
  TF-IDF + popularity, MMR-reranked, with feasibility gating on
  per-topic mastery and live cache-bust on quiz events.
- **EWMA mastery model** — server-authoritative quiz scoring updates
  per-topic mastery snapshots inside one TX (TX-01) with outbox
  publishing for the warehouse pipeline.
- **Path planner** — prerequisite-aware DAG topo-sort (algorithm A6)
  with persisted learner paths, step-status tracking, and a dashboard
  active-path card.
- **Admin dashboards** — six KPI tiles backed by `plrs_dw` MVs,
  audit log viewer, runtime tunables (FR-40), CSV import/export,
  triggered offline-eval with diversity + novelty (NFR-35).
- **Security + ops** — full `@PreAuthorize` RBAC, per-IP + per-user
  rate limits, account lockout, CSP without `unsafe-inline`,
  three NFR-11 chaos scripts (ML/Redis/Kafka), NFR-9 Postgres-restart
  recovery test, pg_dump backup + restore-verify, three runbooks.

### Testing summary

- ~280 unit + integration tests (Maven `verify`)
- 45-request Newman regression across 4 iter folders + per-iter runs
- Playwright E2E (per-iter classes + consolidated `FullRegressionE2E`)
- JMeter latency baseline (`/api/recommendations` p95 = 90 ms observed)
- 4 chaos scripts verifying NFR-9 + NFR-11 graceful degradation

## Prerequisites

- **JDK 17** — Eclipse Temurin 17 recommended.
- **Maven 3.9+**.
- **Docker Desktop** (or equivalent) — required for Testcontainers and
  for running Postgres + Redis locally.
- **Node.js 20 + Newman** (optional) — only needed to run the API E2E
  collection: `npm install -g newman`.
- **Playwright Chromium** (optional) — one-time install for the
  browser E2E:

  ```sh
  mvn -pl plrs-web exec:java \
      -Dexec.mainClass="com.microsoft.playwright.CLI" \
      -Dexec.args="install chromium" \
      -Dexec.classpathScope=test
  ```

## Architecture

Four Maven modules in the JVM tree, plus two sibling Python services
that ship in the same `docker-compose.yml`. Hexagonal layering inside
the JVM tree is enforced by ArchUnit; the Java→Python boundary is a
single port (`MlServiceClient`) with an in-process fallback so the
JVM stays runnable when the ML service is down (NFR-11).

| Module | Role | Depends on |
| --- | --- | --- |
| `plrs-domain` | Pure domain: aggregates, value objects, ports. No framework imports. | (none) |
| `plrs-application` | Use cases, application-owned ports (PasswordEncoder, TokenService, RefreshTokenStore, recommender + ML ports). | `plrs-domain`, `spring-context` |
| `plrs-infrastructure` | Adapters: Spring Data JPA, Redis, BCrypt, JJWT, Kafka producer, item-sim + TF-IDF jobs. | `plrs-application` |
| `plrs-web` | Spring Boot entrypoint: REST controllers, Thymeleaf views, Spring Security. | `plrs-infrastructure` |
| `plrs-ml` (Python) | FastAPI microservice owning scikit-learn / `implicit` work — `/features/rebuild`, `/cf/recompute`, `/cf/similar`, `/cb/similar`, `/eval/run`, all HMAC-signed. | (Postgres + Redis) |
| `plrs-etl-worker` (Python) | Kafka consumer of `plrs.interactions`, idempotent upsert into `plrs_dw.fact_interaction`. | (Kafka + Postgres) |

Persistence and transport:

- **PostgreSQL 15**, two schemas — `plrs_ops` (operational: users,
  user_roles, topics, content, content_tags, prerequisites,
  interactions, outbox_event, quiz_items, quiz_item_options,
  quiz_attempts, user_skills, audit_log, recommendations,
  model_artifacts) and `plrs_dw` (warehouse star: dim_date, dim_user,
  dim_content, dim_topic, fact_interaction, fact_eval_run). Migrations
  run by Flyway: V1 baseline, V2 users, V3 user_roles, V4 topics,
  V5 content + GIN search, V6 content audit actor, V7 prerequisites,
  V8 interactions, V9 outbox_event, V10 quiz_items + quiz_item_options
  (TRG-1, TRG-2), V11 quiz_attempts, V12 user_skills (TRG-3),
  V13 audit_log (TRG-4 append-only), V14 recommendations,
  V15 model_artifacts, V16 minimum warehouse, V17 fact_eval_run.
- **Redis 7** — refresh-token allow-list, per-user top-N
  recommendation cache (`rec:topN:{uuid}`, version-stamped per
  §2.e.2.3.3), per-item cosine similarity slabs (`sim:item:{id}`),
  and TF-IDF feature vectors.
- **Kafka 7.5** (KRaft, single-broker) — `plrs.interactions` topic
  populated by `KafkaOutboxPublisher` when `PLRS_KAFKA_ENABLED=true`;
  the `plrs-etl-worker` consumes and writes the warehouse fact table.
  When the flag is off the existing `LoggingOutboxPublisher` keeps
  the JVM functional without a broker.
- **JWT RS256** — 2h access tokens, 30d refresh tokens with jti tracked
  in Redis. Keys are PEM-supplied in prod (`PLRS_JWT_PRIVATE_KEY_PEM` /
  `PLRS_JWT_PUBLIC_KEY_PEM`) or generated per-JVM in dev.
- **Security** — two Spring Security filter chains: JWT bearer-token
  on `/api/**` (stateless, CORS enabled) and session-based form login
  on the web routes (CSRF via `CookieCsrfTokenRepository`). Both chains
  emit HSTS, X-Frame-Options: DENY, X-Content-Type-Options: nosniff,
  Referrer-Policy, Permissions-Policy, and a strict CSP. The
  Java↔Python hop carries an HMAC-SHA256 signature over
  `{method}\n{path}\n{body}` using `PLRS_ML_HMAC_SECRET`.
- **Observability** — structured JSON logs (LogstashEncoder) with an
  `X-Request-Id` MDC filter that echoes the header on responses.

Design references: the section numbers cited in Javadoc (`§3.a`, `§7`,
etc.) point at the MCSP-232 design report submitted with the synopsis.

## Quick Start

The fastest path is `docker compose up` for the whole stack
(`postgres`, `redis`, `plrs-ml`, `kafka`, `plrs-etl-worker`) plus
`spring-boot:run` for the JVM app:

```bash
# 1. Bring up Postgres + Redis + plrs-ml + Kafka + plrs-etl-worker.
PLRS_ML_HMAC_SECRET=dev-secret docker compose up -d

# 2. Boot the JVM app, pointed at the composed services.
PLRS_KAFKA_ENABLED=true \
PLRS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
PLRS_ML_BASE_URL=http://localhost:8000 \
PLRS_ML_HMAC_SECRET=dev-secret \
mvn -pl plrs-web spring-boot:run

# 3. Open the browser.
open http://localhost:8080
```

Two flags govern the new Iter 3 wiring:

- `PLRS_KAFKA_ENABLED=true` — switches the outbox publisher from the
  no-op `LoggingOutboxPublisher` to `KafkaOutboxPublisher`. Leave it
  unset for a JVM-only run; the warehouse simply won't receive new
  rows.
- `PLRS_ML_BASE_URL` + `PLRS_ML_HMAC_SECRET` — wire the composite
  CF/CB scorers to the Python service. If `PLRS_ML_BASE_URL` is unset
  the recommender falls back to the in-process scorers (NFR-11), and
  the offline eval endpoint is gated off (it requires the Python
  service to compute the slate).

If you only need the Iter 2 surface (no recommender, no warehouse),
the lighter Postgres + Redis pair still works:

```bash
docker run -d --name plrs-pg -p 5432:5432 \
    -e POSTGRES_DB=plrs -e POSTGRES_USER=plrs -e POSTGRES_PASSWORD=plrs \
    postgres:15-alpine
docker run -d --name plrs-redis -p 6379:6379 redis:7-alpine
mvn -pl plrs-web spring-boot:run
```

If host port `5432` is occupied (e.g. by a native Postgres install),
map the container to a spare port and override `DB_URL`:

```bash
docker run -d --name plrs-pg -p 55432:5432 \
    -e POSTGRES_DB=plrs -e POSTGRES_USER=plrs -e POSTGRES_PASSWORD=plrs \
    postgres:15-alpine
DB_URL=jdbc:postgresql://localhost:55432/plrs mvn -pl plrs-web spring-boot:run
```

## Demo script (viva)

The full step-by-step walkthrough lives in
[DEMO_SCRIPT.md](DEMO_SCRIPT.md). A one-paragraph summary:

1. Boot Postgres + Redis, then `mvn -pl plrs-web spring-boot:run`.
2. Load the seeded INSTRUCTOR + demo quiz via
   `psql … -f test/newman/seed.sql`.
3. As an instructor (the seeded user), POST a topic and a VIDEO via
   the JSON API.
4. As a fresh student, browse `/catalog`, open the seeded quiz, click
   **Attempt this quiz**, submit, then **Go to dashboard** to see the
   Chart.js radar populated by the EWMA update and the activity
   sparkline.
5. Run the headless equivalents:

   ```bash
   ./test/newman/run.sh         # Iter 1 register/login/me/logout
   ./test/newman/run-iter2.sh   # Iter 2 catalog/interactions/quiz
   ```

6. Cross-check the database: `user_skills` has the EWMA row,
   `audit_log` has rows for `USER_REGISTERED`, `INTERACTION_RECORDED`,
   `QUIZ_ATTEMPTED`, and `outbox_event` has a `QUIZ_ATTEMPTED`
   payload pending publication.

## Testing

```bash
# Unit + integration + ArchUnit + slice tests.
mvn verify

# Browser E2E (Playwright, gated behind E2E=true so the default
# verify doesn't hang on first-run Chromium download / macOS quarantine).
E2E=true mvn -pl plrs-web verify

# API E2E (requires the app running from Quick Start).
./test/newman/run.sh
```

What `mvn verify` covers:

- `plrs-domain`: JUnit 5 unit tests for every value object, the User
  aggregate, and the exception hierarchy (≈90 cases).
- `plrs-application`: use-case tests with Mockito + the ArchUnit rules
  enforcing layered dependency direction.
- `plrs-infrastructure`: Testcontainers-backed integration tests for
  Flyway migrations, JPA mappings, the Redis refresh-token store, and
  the BCrypt adapter.
- `plrs-web`: `@WebMvcTest` slices for every controller and filter
  plus a security-routing slice that drives the full filter chain.

## Iteration 4 scope

**Included**

- Path planner (algorithm A6, §3.c.5.6) with `LearnerPath` aggregate,
  V18 partial-unique-active schema invariant, REST surface
  (`/api/learning-path`), Thymeleaf views (`/path/generate`,
  `/path/{id}`), dashboard active-path card.
- Admin KPI dashboard at `/admin/dashboard` with six tiles
  (coverage, CTR, completion-rate avg, cold-item exposure, weekly
  rating, latest precision@10) and two Chart.js trend lines
  (30-day completion + weekly rating). Backed by six materialised
  views (V19) with `RefreshKpiViewsJob` cron + manual refresh.
  `SpringDataRecommendationRepository` mirrors writes to
  `fact_recommendation` so the views have data without waiting for
  the Kafka → ETL hop.
- FR-06 account lockout: V21 columns + atomic CASE-based decision
  SQL, 423 + `Retry-After`, ADMIN unlock at
  `POST /api/admin/users/{id}/unlock`.
- NFR-31 IP-based rate limit on `/api/auth/login` (10 attempts per
  5 min per source IP), wired before the JWT filter.
- FR-04 password reset (minimum): V22 reset_token columns,
  `/api/auth/password-reset/{request,confirm}` endpoints with
  enumeration-proof 204 on request, `RefreshTokenStore.revokeAllForUser`
  on confirm so old sessions can't survive the change.
- FR-10/11 CSV bulk import + export of catalogue content
  (`/api/content/{import,export}`).
- Reflective `PreAuthorizeAuditTest` enforcing every mutating
  endpoint is `@PreAuthorize`-guarded, with an explicit
  `PUBLIC_ALLOWLIST` for the deliberately public auth surface.
- FR-42 audit log viewer at `/admin/audit` with actor/action/date
  filters and pagination.
- FR-40 runtime-tunable parameters: V23 `config_params`,
  `ConfigParamService` with `@Cacheable` reads + `@CacheEvict` on
  update, admin editor at `/admin/config`. `HybridRanker`,
  `MmrReranker`, `PathPlanner` now resolve their key thresholds
  from the service (with hardcoded defaults as fallback).
- Nightly `IntegrityChecksJob` (DAG-acyclic via recursive CTE,
  orphan-interactions, mastery-bounds, user-skills-version
  non-negative) writing to V24 `plrs_ops.integrity_checks`.
- CSP tightening: every inline `<script>` externalised; no
  `'unsafe-inline'` in `script-src`. Header audit test covers HSTS,
  X-Frame-Options, Referrer-Policy, Permissions-Policy, X-Robots-Tag.
- Newman Iter 4 collection (paths + admin + lockout + CSV) with CI
  step; Playwright Iter 4 E2Es (path planner flow + admin dashboard
  flow) gated by `E2E=true`.

**Deferred to Iter 5**

- Diagnostic quiz (FR-23) — was provisionally listed for Iter 4
  but didn't make the timing.
- JMeter load tests (NFR-13/14/17).
- Chaos tests (NFR-9/10/11).
- axe-core accessibility audit.
- OWASP ZAP automated scan in CI.
- Email-based password reset (real SMTP).
- SCD-2 dimensions in the warehouse.
- Per-instructor admin dashboard.
- Backup verification.

## Iteration 3 scope

**Included**

- Eight algorithms from §3.c.5: signal aggregation (P4.1, in
  `PopularityScorer`), TF-IDF, item-item CF, hybrid blend
  (`HybridRanker`, λ=0.65), MMR rerank (`MmrReranker`), prereq filter
  (Iter 2 + recommender wiring), feasibility filter (FR-27), EWMA
  (Iter 2), and a minimum offline evaluation harness (precision@10,
  ndcg@10, coverage).
- `GET /api/recommendations` with `k` validation (1..50) and the
  per-user 20 req/min `PerUserRateLimiter` (NFR-31).
- "Recommended for you" dashboard card backed by
  `/web-api/recommendations` (session-cookie surface, same use case
  as the JWT API).
- Python ML microservice (`plrs-ml`, FastAPI) exposing
  `/features/rebuild`, `/cf/recompute`, `/cf/similar`, `/cb/similar`,
  and `/eval/run` — all gated by HMAC-SHA256 signature middleware.
- Java→Python composite scorers (`CompositeCfScorer`,
  `CompositeCbScorer`) with NFR-11 in-process fallback when the
  Python service times out, errors, or is unconfigured.
- `KafkaOutboxPublisher` gated on `plrs.kafka.enabled`, plus a Kafka
  Testcontainers IT verifying the outbox-to-topic flow end to end.
- Python ETL worker (`plrs-etl-worker`) consuming `plrs.interactions`
  into `plrs_dw.fact_interaction` with idempotent upserts.
- Minimum warehouse star: `dim_date`, `dim_user`, `dim_content`,
  `dim_topic`, `fact_interaction`, `fact_eval_run`.
- Admin `/admin` page with a Run Evaluation tile that POSTs
  `/api/admin/eval/run` and renders precision@10 / ndcg@10 /
  coverage; a sibling `POST /api/admin/recommender/recompute`
  drives `ItemSimilarityJob` + `TfIdfBuildJob` synchronously for
  Newman / E2E flows.

**Deferred to Iter 4**

- Learning paths (FR-31..FR-34).
- Full admin dashboard (CTR, completion rate, cold-item exposure).
- Diagnostic quiz (FR-23).
- All materialised views except eval-related.
- Account lockout, email verification, password reset.
- Rate limiting on more endpoints (only the recommender API is
  rate-limited today).
- CSV bulk import / export.
- OWASP ZAP scan and axe-core accessibility audit.

**Deferred to Iter 5**

- JMeter load tests (NFR-13/14/17).
- Chaos tests (NFR-9/10/11).
- Backup verification.
- Final report integration.

## Project metadata

- **Programme**: IGNOU MCA (Master of Computer Applications).
- **Course**: MCSP-232 — Project Work.
- **Enrolment**: 2452345135.
- **Guide**: Himanshu Katiyar.
- **Regional Centre**: Ranchi.
- **Synopsis approved**: 30 November 2025.
