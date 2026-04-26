# PLRS — Iteration 3 demo script

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
# Whole supporting stack: Postgres + Redis + plrs-ml + Kafka + plrs-etl-worker.
PLRS_ML_HMAC_SECRET=dev-secret docker compose up -d

# Confirm all five services are up.
docker compose ps
```

Expected `STATUS` column for each row: `Up (healthy)` for `postgres`,
`redis`, `kafka`; `Up` for `plrs-ml` and `plrs-etl-worker`.

```bash
# Application — Flyway migrates V1..V17 on first boot.
PLRS_KAFKA_ENABLED=true \
PLRS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
PLRS_ML_BASE_URL=http://localhost:8000 \
PLRS_ML_HMAC_SECRET=dev-secret \
mvn -pl plrs-web spring-boot:run
```

Watch the log for `Started PlrsApplication in <n> seconds`. Visit
`http://localhost:8080/health` — should return 200 with
`{"status":"UP"}`. Also check `curl http://localhost:8000/health` for
the Python ML service: 200 `{"status":"ok"}`.

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

## 8. Iter 3 — recommender, warehouse, admin eval

### 8a. Student sees a fresh recommendation slate after a quiz

1. Still logged in as the student from §4, return to `/dashboard`.
2. The **Recommended for you** card lists up to ten items. Cold-start
   shows popularity-blended BEGINNER content; right after the §4
   quiz attempt, the post-mastery slate refreshes (the version-bust
   path nukes `rec:topN:{userUuid}` in Redis).
3. Hover the **Why?** badge on any row — the `RecommendationReason`
   string explains which signal dominated (CF neighbour, CB
   topic-similarity, popularity).

### 8b. Admin runs the offline evaluation

1. Open `/admin` (logged in as an ADMIN — promote a user via
   `INSERT INTO plrs_ops.user_roles … 'ADMIN'` if needed).
2. Click **Run Evaluation**. The card POSTs to
   `/api/admin/eval/run`, which proxies to the Python `/eval/run`
   endpoint and persists a row in `plrs_dw.fact_eval_run`.
3. The card renders the three numbers: **precision@10**,
   **ndcg@10**, **coverage**.

### 8c. Warehouse fact rows flow through Kafka

```bash
PGPASSWORD=plrs psql -h localhost -U plrs -d plrs <<'SQL'
-- Interactions consumed off plrs.interactions by plrs-etl-worker.
SELECT user_id, content_id, event_type, occurred_at
FROM plrs_dw.fact_interaction
ORDER BY occurred_at DESC
LIMIT 5;

-- Eval runs persisted by /api/admin/eval/run.
SELECT eval_run_id, ran_at, precision_at_10, ndcg_at_10, coverage
FROM plrs_dw.fact_eval_run
ORDER BY ran_at DESC
LIMIT 3;
SQL
```

The fact_interaction rows are populated by the ETL worker after the
beacon writes traverse the outbox → Kafka → consumer → warehouse
path; expect a few seconds of delay between the catalogue
view-beacon firing and the row landing.

### 8d. NFR-11: ML service can fail without taking the JVM down

```bash
# Stop only the Python ML service.
docker compose stop plrs-ml

# Refresh /dashboard in the browser. The Recommended-for-you card
# still populates — composite scorers fall back to the in-process
# popularity + sim-slab path. The dashboard never errors.

# Bring it back.
docker compose start plrs-ml

# Refresh again. Recommendations resume the ML-backed slate; admin
# /admin Run Evaluation is functional again (eval requires the
# Python service since it owns the slate computation).
```

The fallback toggle is `plrs.ml.base-url`: when unreachable, both
`CompositeCfScorer` and `CompositeCbScorer` swallow the failure and
return their in-process result, while `RunEvalUseCase` is gated off
since precision@10 / ndcg@10 / coverage requires the slate the
Python service computes.

## Stopping the demo

```bash
# Ctrl-C the spring-boot:run terminal, then:
docker compose down
```
