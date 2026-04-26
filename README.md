# PLRS — Personalized Learning Recommendation System

[![build](https://github.com/kramit45/plrs/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kramit45/plrs/actions/workflows/build.yml)

PLRS is the capstone project submitted for **IGNOU MCA course MCSP-232**.
It explores a recommendation system that tailors learning resources to
individual learners based on their profile, progress, and performance
signals, combining content-based and collaborative-filtering techniques
into a single deployable Java application.

## Status

**Iteration 3 complete — recommender (CF + CB + MMR), Python ML
microservice, Kafka producer, minimum warehouse, offline eval.**
Building on the Iter 2 catalogue + EWMA mastery, Iter 3 adds the full
recommender pipeline (popularity → item-item CF → TF-IDF CB → λ-blend
→ MMR diversification → prereq + feasibility filters), an external
Python ML microservice that owns the heavy scikit-learn / `implicit`
work behind HMAC-signed endpoints, a `KafkaOutboxPublisher` that the
existing outbox drain can swap to, a Python ETL worker that consumes
`plrs.interactions` into a minimum `plrs_dw` warehouse star (dim_date,
dim_user, dim_content, dim_topic, fact_interaction, fact_eval_run),
the `Recommended for you` dashboard card, and an `/admin` page with a
Run Evaluation tile that computes precision@10 / ndcg@10 / coverage.

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
