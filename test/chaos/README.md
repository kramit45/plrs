# Chaos tests — NFR-9 / NFR-11 verification

Manual / staging-only scripts that bring real failures and assert the app
still meets its degradation NFRs. They are **not run in CI** because they
toggle docker-compose services in disruptive ways; run them on a
developer laptop or a dedicated staging host before each release.

## Prerequisites (all scripts)

- `docker compose up -d` already running (`postgres`, `redis`, `plrs-ml`,
  `kafka`, `plrs-etl-worker`).
- JVM app started separately, e.g.:
  ```bash
  PLRS_KAFKA_ENABLED=true \
  PLRS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  PLRS_ML_BASE_URL=http://localhost:8000 \
  PLRS_ML_HMAC_SECRET=dev-secret \
  mvn -pl plrs-web spring-boot:run
  ```
- `test/newman/seed.sql` loaded so `newman-student@example.com` exists.

The JVM app is *not* part of `docker-compose.yml` — packaging it would
require a Dockerfile and image-pinning that is out of scope for the demo.
The chaos scripts assume the operator runs the JVM in a separate
terminal, mirroring the `DEMO_SCRIPT.md` flow.

## Scripts

| Script                        | Verifies         | What it does                                                                                          |
| ----------------------------- | ---------------- | ----------------------------------------------------------------------------------------------------- |
| `ml_down.sh` / `.ps1`         | NFR-11           | Stop `plrs-ml`, expect `/api/recommendations` still 200 (in-process fallback via Composite scorers).  |
| `redis_down.sh`               | NFR-11           | Stop Redis, expect `/api/recommendations` still 200 (top-N cache misses but DB-backed slate works).   |
| `kafka_down.sh`               | NFR-11           | Stop Kafka, submit a quiz attempt, watch outbox accumulate; restart Kafka, drain catches up ≤ 60 s.   |
| `postgres_restart.sh` / `.ps1`| NFR-9            | Restart Postgres mid-traffic; recovery within 30 s via Hikari pool retry.                             |

## Running

```bash
chmod +x test/chaos/*.sh    # one-time
./test/chaos/ml_down.sh
./test/chaos/redis_down.sh
./test/chaos/kafka_down.sh
./test/chaos/postgres_restart.sh
```

Each prints its own ▶ / ✓ / ✗ trace and exits non-zero on failure.

## Cleanup

The scripts try to restore stopped services on exit, but if you
interrupt one (Ctrl-C) you may need to run `docker compose start <service>`
yourself.
