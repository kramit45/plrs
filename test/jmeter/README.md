# JMeter perf scenario — `/api/recommendations`

Verifies NFR-13 (P95 ≤ 300 ms warm), NFR-14 (P95 ≤ 900 ms cold), NFR-17
(50 req/s sustained) for the recommender HTTP surface.

## Files

- `recommendations-load.jmx` — JMeter test plan
- `seed-perf.sql` — 50 students + 100 content items + ~5 000 interactions
- `run.sh` / `run.ps1` — headless runner producing `report/index.html`

## Running

```bash
# 1. Bring the stack up and load the seeds.
PLRS_ML_HMAC_SECRET=dev-secret docker compose up -d
mvn -pl plrs-web spring-boot:run &  # or your usual run path
PGPASSWORD=plrs psql -h localhost -U plrs -d plrs \
    -f test/newman/seed.sql \
    -f test/newman/seed-iter3.sql \
    -f test/newman/seed-iter4.sql \
    -f test/jmeter/seed-perf.sql

# 2. Run JMeter.
./test/jmeter/run.sh

# 3. Open the report.
open test/jmeter/report/index.html
```

## Tunables (JMeter `-J` properties)

| Property      | Default | Meaning                              |
| ------------- | ------- | ------------------------------------ |
| `host`        | localhost | Target host                          |
| `port`        | 8080      | Target port                          |
| `threads`     | 20        | Concurrent users                     |
| `ramp`        | 600       | Ramp-up seconds                      |
| `duration`    | 1200      | Total run seconds (incl. ramp)       |
| `studentEmail`| `newman-student@example.com` | Login email   |
| `studentPassword` | `StudentPass01` | Login password         |

## Reading the output

Open `report/index.html`. Key panels:

- **APDEX table** — overall application performance index.
- **Statistics** — per-request mean / median / 90 / 95 / 99 percentile
  latencies. The `GET /api/recommendations` row is the one that maps to
  NFR-13/14.
- **Errors** — non-200 responses; should be 0.
- **Throughput over time** — rolling req/s. NFR-17 expects sustained ≥ 50.

## Baseline expectations (after warm-up)

| Metric             | Target               | Source     |
| ------------------ | -------------------- | ---------- |
| P95 latency        | < 300 ms warm        | NFR-13     |
| P95 latency        | < 900 ms cold start  | NFR-14     |
| Sustained req/s    | ≥ 50                 | NFR-17     |
| Error rate         | 0%                   | implicit   |

A regression here is rarely a code change in the recommender alone —
also check `mv_*` refresh frequency (Iter 4 step 149), `rec.cache_ttl_seconds`
in `config_params` (step 161), and whether `plrs-ml` is reachable
(otherwise CompositeCfScorer / CompositeCbScorer fall back to in-process
which is slower and more variable).

## CI

`.github/workflows/perf.yml` runs the same plan on `workflow_dispatch`
against an ephemeral docker-compose stack and uploads `report/` as an
artifact. Not part of the per-PR build (long-running).
