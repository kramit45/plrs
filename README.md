# PLRS — Personalized Learning Recommendation System

[![build](https://github.com/kramit45/plrs/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kramit45/plrs/actions/workflows/build.yml)

PLRS is the capstone project submitted for **IGNOU MCA course MCSP-232**.
It explores a recommendation system that tailors learning resources to
individual learners based on their profile, progress, and performance
signals, combining content-based and collaborative-filtering techniques
into a single deployable Java application.

## Status

**Iteration 2 complete — catalogue, interactions, quiz, mastery (EWMA).**
Building on the Iter 1 walking skeleton, Iter 2 adds the full content
catalogue (topics, content, prerequisites with cycle detection, GIN
full-text search), implicit-feedback interaction tracking with the
FR-15 10-minute VIEW debounce, server-authoritative quiz scoring, the
EWMA mastery update inside the TX-01 transactional invariant (mastery
upsert + version bump + outbox event in one transaction), a
post-commit Redis cache-invalidation hook, an `@Auditable` AOP aspect
writing to an append-only `audit_log`, and a student dashboard with a
Chart.js mastery radar plus an 8-week activity sparkline.

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

Four Maven modules, hexagonal layering enforced by ArchUnit:

| Module | Role | Depends on |
| --- | --- | --- |
| `plrs-domain` | Pure domain: aggregates, value objects, ports. No framework imports. | (none) |
| `plrs-application` | Use cases, application-owned ports (PasswordEncoder, TokenService, RefreshTokenStore). | `plrs-domain`, `spring-context` |
| `plrs-infrastructure` | Adapters: Spring Data JPA, Redis, BCrypt, JJWT. | `plrs-application` |
| `plrs-web` | Spring Boot entrypoint: REST controllers, Thymeleaf views, Spring Security. | `plrs-infrastructure` |

Persistence and transport:

- **PostgreSQL 15**, two schemas — `plrs_ops` (operational: users,
  user_roles, topics, content, content_tags, prerequisites,
  interactions, outbox_event, quiz_items, quiz_item_options,
  quiz_attempts, user_skills, audit_log) and `plrs_dw` (warehouse,
  reserved for Iter 3 reporting). Migrations run by Flyway: V1
  baseline, V2 users, V3 user_roles, V4 topics, V5 content + GIN
  search, V6 content audit actor, V7 prerequisites, V8 interactions,
  V9 outbox_event, V10 quiz_items + quiz_item_options (TRG-1, TRG-2),
  V11 quiz_attempts, V12 user_skills (TRG-3), V13 audit_log (TRG-4
  append-only).
- **Redis 7** — backs the refresh-token allow-list.
- **JWT RS256** — 2h access tokens, 30d refresh tokens with jti tracked
  in Redis. Keys are PEM-supplied in prod (`PLRS_JWT_PRIVATE_KEY_PEM` /
  `PLRS_JWT_PUBLIC_KEY_PEM`) or generated per-JVM in dev.
- **Security** — two Spring Security filter chains: JWT bearer-token
  on `/api/**` (stateless, CORS enabled) and session-based form login
  on the web routes (CSRF via `CookieCsrfTokenRepository`). Both chains
  emit HSTS, X-Frame-Options: DENY, X-Content-Type-Options: nosniff,
  Referrer-Policy, Permissions-Policy, and a strict CSP.
- **Observability** — structured JSON logs (LogstashEncoder) with an
  `X-Request-Id` MDC filter that echoes the header on responses.

Design references: the section numbers cited in Javadoc (`§3.a`, `§7`,
etc.) point at the MCSP-232 design report submitted with the synopsis.

## Quick Start

```bash
# 1. Start Postgres and Redis.
docker run -d --name plrs-pg -p 5432:5432 \
    -e POSTGRES_DB=plrs -e POSTGRES_USER=plrs -e POSTGRES_PASSWORD=plrs \
    postgres:15-alpine
docker run -d --name plrs-redis -p 6379:6379 redis:7-alpine

# 2. Boot the app.
mvn -pl plrs-web spring-boot:run

# 3. Open the browser.
open http://localhost:8080
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

## Iteration 2 scope

**Included**

- Full catalogue: topics, content (4 ctypes — VIDEO, ARTICLE,
  EXERCISE, QUIZ), tags, prerequisites with cycle detection at
  SERIALIZABLE isolation + retry-once.
- Full-text search via Postgres GIN tsvector (`/api/content/search`,
  `/catalog`).
- Implicit-feedback interaction recording with the FR-15 10-minute
  VIEW debounce and an idempotent composite-PK contract.
- Server-authoritative quiz scoring (`Content.score`) with per-topic
  weight rounding correction; Iter 1 quiz authoring is partial — the
  domain side (`QuizContentDraft`, `QuizItem`, `QuizItemOption`) and
  the schema (TRG-1 ctype-coupling, TRG-2 deferred exactly-one-correct)
  are wired, but the `/api/content/quiz` HTTP endpoint is deferred to
  Iter 3 alongside the recommender — the demo quiz is seeded via SQL.
- EWMA mastery update with confidence increment, gated by an advisory
  lock and committed atomically with the quiz attempt, the
  `users.user_skills_version` bump (TRG-3 monotonic), and a
  `QUIZ_ATTEMPTED` outbox row (TX-01).
- Outbox pattern with a no-op `LoggingOutboxPublisher` and a scheduled
  drain job (real Kafka producer is Iter 3).
- Post-commit Redis cache-invalidation port (`TopNCache`) on the
  recommender's per-user top-N key.
- Student dashboard: `GET /dashboard` with Chart.js mastery radar
  (top-6 topics by mastery DESC), recent completes (last 5), recent
  attempts (last 10), and an 8-week activity sparkline backed by
  `GET /web-api/me/activity-weekly`.
- `@Auditable` AOP aspect appending to `plrs_ops.audit_log` (V13);
  every state-changing use case is annotated, and TRG-4 enforces
  append-only at the database boundary.
- Method-level `@PreAuthorize` across every mutating endpoint,
  exercised by a 20-pair `RoleMatrixIT`.
- ArchUnit rule enforcing classes that depend on `OutboxRepository`
  must be `@Transactional` (TX-01 structural surrogate).

**Deferred to Iter 3**

- Recommender pipeline (CF + CB + MMR).
- Python ML microservice.
- Real Kafka producer (replacing the no-op logger).
- Diagnostic quiz (FR-23) and `POST /api/content/quiz` authoring
  endpoint.
- Offline evaluation harness.
- Warehouse star schema in `plrs_dw`.

**Deferred to Iter 4 (hardening)**

- Learning paths (FR-31..FR-34).
- Admin dashboard with KPIs.
- Account lockout, email verification, password reset.
- Rate limiting.
- CSV bulk import / export.
- OWASP ZAP scan and axe-core accessibility audit.

**Deferred to Iter 5**

- JMeter load tests.
- Chaos tests.
- Final report integration.

## Project metadata

- **Programme**: IGNOU MCA (Master of Computer Applications).
- **Course**: MCSP-232 — Project Work.
- **Enrolment**: 2452345135.
- **Guide**: Himanshu Katiyar.
- **Regional Centre**: Ranchi.
- **Synopsis approved**: 30 November 2025.
