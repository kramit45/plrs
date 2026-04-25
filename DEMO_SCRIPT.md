# PLRS — Iteration 2 demo script

Step-by-step walkthrough for the MCSP-232 viva. Every command below
is copy-paste runnable from the repository root, given the listed
prerequisites.

## Prerequisites

- JDK 17, Maven 3.9+, Node.js 20+ with `newman` (for the API E2E).
- Docker running (`docker compose` for the bundled Postgres + Redis,
  or a native install at the same coordinates).
- The `psql` client on `PATH` (used to load the seed file).

## 1. Boot the stack

```bash
# Postgres + Redis
docker run -d --name plrs-pg -p 5432:5432 \
    -e POSTGRES_DB=plrs -e POSTGRES_USER=plrs -e POSTGRES_PASSWORD=plrs \
    postgres:15-alpine
docker run -d --name plrs-redis -p 6379:6379 redis:7-alpine

# Application — Flyway migrates V1..V13 on first boot.
mvn -pl plrs-web spring-boot:run
```

Watch the log for `Started PlrsApplication in <n> seconds`. Visit
`http://localhost:8080/health` — should return 200 with
`{"status":"UP"}`.

## 2. Load the demo seed

The seed inserts a known instructor, a topic, and a demo quiz at
stable ids (`900001`, `900002`):

```bash
PGPASSWORD=plrs psql -h localhost -U plrs -d plrs \
    -f test/newman/seed.sql
```

Credentials baked into the seed:

- Instructor: `newman-instructor@example.com` / `InstructorPass01`
- Demo quiz id: `900002`

## 3. Instructor authoring (JSON API)

```bash
# 3a. Login as the seeded instructor; capture the JWT.
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"newman-instructor@example.com","password":"InstructorPass01"}' \
  | jq -r .accessToken)

# 3b. Create a fresh topic.
TOPIC_ID=$(curl -s -X POST http://localhost:8080/api/topics \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"name":"Demo Topic","description":"Viva demo"}' \
  | jq -r .topicId)

# 3c. Create a VIDEO under that topic.
curl -s -X POST http://localhost:8080/api/content \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"topicId\":$TOPIC_ID,\"title\":\"Demo Video\",\"ctype\":\"VIDEO\",
         \"difficulty\":\"BEGINNER\",\"estMinutes\":8,
         \"url\":\"https://example.com/demo\"}"
```

Open `/catalog` in the browser as the instructor — both the seeded
demo quiz and the new VIDEO appear in the listing.

## 4. Student journey through the browser

1. Log out (top-right nav). Visit `/register`.
2. Register with a fresh email and `Password01`.
3. Land on `/login?registered`; log in with the same credentials.
4. Click **Catalogue** → click the seeded *Newman Demo Quiz* card.
5. On the detail view, click **Attempt this quiz**.
6. Pick *Option A (correct)* and submit.
7. The result page shows **You scored 1 out of 1 (100.00%)** and a
   **Go to dashboard** button.
8. Click **Go to dashboard** — the Chart.js radar shows the demo
   topic at the post-EWMA mastery level (≈ 0.70 from the
   `α_quiz × weight × score` blend), the activity sparkline shows
   today's bucket bumped, and the recent-attempts table lists the
   submission.

## 5. Database cross-check

In a separate terminal:

```bash
PGPASSWORD=plrs psql -h localhost -U plrs -d plrs <<'SQL'
-- The student's post-quiz mastery row.
SELECT user_id, topic_id, mastery_score, confidence, updated_at
FROM plrs_ops.user_skills
ORDER BY updated_at DESC
LIMIT 5;

-- TRG-3 monotonic version bump.
SELECT id, email, user_skills_version
FROM plrs_ops.users
ORDER BY updated_at DESC
LIMIT 5;

-- Audit trail (NFR-29).
SELECT occurred_at, action, entity_type
FROM plrs_ops.audit_log
ORDER BY occurred_at DESC
LIMIT 20;

-- Outbox queue (delivered_at populated by the drain job).
SELECT outbox_id, aggregate_type, aggregate_id, delivered_at
FROM plrs_ops.outbox_event
ORDER BY created_at DESC
LIMIT 5;
SQL
```

Expected rows:

- `user_skills` has one row for the just-registered student under
  topic 900001, mastery ≈ 0.70.
- `users.user_skills_version` = 1 for that student (TRG-3 enforces
  monotonic; any decrease attempt raises an exception).
- `audit_log` has rows for `USER_REGISTERED`, `LOGIN_OK`,
  `QUIZ_ATTEMPTED`, plus any `INTERACTION_RECORDED` events from the
  catalogue page-view beacon.
- `outbox_event` has a `QUIZ_ATTEMPTED` row whose `delivered_at` is
  populated within seconds by the scheduled drain job (Iter 2 ships
  a no-op `LoggingOutboxPublisher`; the real Kafka producer is
  Iter 3).

## 6. Headless E2E

Two Newman collections run the same flows from the command line:

```bash
# Iter 1 — register/login/me/logout. (5 requests)
./test/newman/run.sh

# Iter 2 — instructor authoring + student interaction + quiz attempt. (9 requests)
./test/newman/run-iter2.sh
```

The browser-driven Playwright E2E is gated behind `E2E=true` (so the
default `mvn verify` doesn't pay the Chromium download / Gatekeeper
cost on every developer machine):

```bash
E2E=true mvn -pl plrs-web verify
```

## 7. Test reports

After `mvn verify`:

- `**/target/site/jacoco/index.html` — line + branch coverage per
  module.
- `**/target/surefire-reports/` and `**/target/failsafe-reports/` —
  per-class test results.
- `target/site/jacoco-aggregate/index.html` — aggregate coverage if
  the multi-module JaCoCo merge has been enabled in your local pom
  override.

## Stopping the demo

```bash
# Ctrl-C the spring-boot:run terminal, then:
docker rm -f plrs-pg plrs-redis
```
