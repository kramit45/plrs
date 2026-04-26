# PLRS — Deployment Guide

Bring the full stack up from a clean clone in under 15 minutes
(NFR-23). This document is the single source of truth for the viva
demo and the offline grader.

## 1. Prerequisites

| Tool       | Minimum version | Why                                                |
| ---------- | --------------- | -------------------------------------------------- |
| Java JDK   | Temurin 17      | Spring Boot 3.2.5 baseline                         |
| Maven      | 3.9.x           | Reactor build, surefire/failsafe                   |
| Docker     | 25.0+           | docker-compose stack (Postgres, Redis, Kafka, ML)  |
| Node.js    | 20.x            | Newman + Playwright                                |
| Python     | 3.10            | plrs-ml + plrs-etl-worker                          |
| Poetry     | 1.8+            | Python dep manager                                 |
| psql       | 15.x            | Loading seed SQL                                   |

Optional: `gh` CLI (only needed for `gh release` workflows in step 175).

Verify in one shot:
```bash
java -version && mvn -v && docker --version && node -v \
  && python3 --version && poetry --version && psql --version
```

## 2. Clone + build

```bash
git clone https://github.com/kramit45/plrs.git
cd plrs
mvn -DskipTests package      # ~90 s on a warm cache; ~3 min cold
```

## 3. Bootstrap data services

```bash
docker compose up -d postgres redis kafka
docker compose ps             # wait until all three are "healthy"
```

Load the consolidated seed (instructor + admin + demo quiz + lockee +
path-target topic; idempotent):
```bash
PGPASSWORD=plrs psql -h localhost -U plrs -d plrs -f test/newman/seed-full.sql
```

## 4. Boot the Python sidecars

ML service (FastAPI on :8000):
```bash
cd plrs-ml
poetry install
poetry run uvicorn plrs_ml.main:app --port 8000 &
cd ..
```

ETL worker (Kafka → warehouse drain):
```bash
cd plrs-etl-worker
poetry install
poetry run python -m plrs_etl.main &
cd ..
```

## 5. Boot the Java app

```bash
cd plrs-web
mvn spring-boot:run \
    -Dspring-boot.run.profiles=demo \
    -Dspring-boot.run.arguments="--plrs.kafka.enabled=true"
```

Leave this terminal running — the chaos scripts and demo flows need
the JVM up. (Packaging the JVM into docker-compose was deliberately
left out of scope for the demo; `mvn spring-boot:run` is the
operator-facing entrypoint.)

## 6. Verify

```bash
curl -sf http://localhost:8080/health             # → {"status":"UP"}
open http://localhost:8080                        # web UI
open http://localhost:8080/swagger-ui.html        # OpenAPI 3
```

Optional sanity logins (from `seed-full.sql`):
- Student:    `newman-student@example.com` / `StudentPass01`
- Instructor: `newman-instructor@example.com` / `InstructorPass01`
- Admin:      `newman-admin@example.com` / `AdminPass01`

## 7. Smoke test

```bash
cd test/newman
bash run-full.sh
```

Expects all 45 requests across 4 folders to pass against a freshly
seeded DB. Per-iter runners (`run-iter1.sh` … `run-iter4.sh`) are
available for narrower triage.

## 8. Tear down

```bash
# In the spring-boot:run terminal: Ctrl-C
# In the Python sidecar terminals: Ctrl-C (or kill %1 %2)
docker compose down -v        # -v drops the Postgres volume too
```

## Troubleshooting

| Symptom                                      | Likely cause / fix                                                                                  |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `mvn package` fails with port 5432 in use    | Another Postgres is bound; either stop it or change `DB_URL` to a free port.                        |
| `/health` 503 for >30 s after Postgres up    | Flyway migrations still running — first boot replays V1..V24. Tail `app.log`.                       |
| Recommender returns empty slate              | Seed didn't load, OR plrs-ml unreachable + Composite fallback returned no items. `/admin/dashboard` shows coverage. |
| Newman 429 on rapid re-runs                  | PerUserRateLimiter (NFR-31) caps at 20 req/min/user. Wait 60 s or test with a different account.    |
| Kafka unreachable, app still 200             | Outbox accumulates rows; restart Kafka and the publisher drains within ~60 s (see chaos kafka_down).|
