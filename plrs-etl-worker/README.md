# plrs-etl-worker

Python Kafka consumer that drains `plrs.interactions` into
`plrs_dw.fact_interaction`. Idempotent on the composite PK
`(date_sk, user_sk, content_sk, occurred_at)` so at-least-once
Kafka delivery doesn't double-count facts.

## Local development

Requires Python 3.10 and Poetry 1.8+ (Poetry 2.x also works).

```bash
cd plrs-etl-worker
poetry install
poetry run pytest
poetry run ruff check .
PLRS_ETL_BOOTSTRAP_SERVERS=localhost:9092 poetry run python -m plrs_etl.main
```

## Configuration

| Env var | Default | Notes |
| --- | --- | --- |
| `PLRS_ETL_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list |
| `PLRS_ETL_TOPIC` | `plrs.interactions` | Topic to consume |
| `PLRS_ETL_GROUP_ID` | `plrs-etl` | Kafka consumer group |
| `PLRS_ETL_DW_DB_URL` | `postgresql://plrs:plrs@localhost:5432/plrs` | Warehouse DSN |

## Iter 3 scope

- SCD-1 dimension upserts (`ON CONFLICT DO NOTHING`).
- Watermark via Kafka consumer-group offset.
- Single-event commit (offset committed only after successful insert).

Deferred to Iter 4: SCD-2 history, dead-letter quarantine, materialised
views, full §2.e.2.6 ETL semantics.
