# plrs-ml

Python ML microservice for PLRS. Owns the offline TF-IDF and CF
artifact builds, plus the read-only `/cf/similar` and `/cb/similar`
endpoints the Java app queries through `MlServiceClient`.

## Local development

Requires Python 3.10 and Poetry 1.8+ (Poetry 2.x also works).

```bash
cd plrs-ml
poetry install
poetry run pytest
poetry run ruff check .
poetry run uvicorn plrs_ml.main:app --reload
```

## Docker / docker-compose

```bash
docker build -t plrs-ml ./plrs-ml
# or, from the repo root, bring up the full stack:
docker-compose up plrs-ml
curl http://localhost:8000/health   # → {"status":"UP",...}
```

`docker-compose up` starts Postgres, Redis, and plrs-ml; the
service joins the compose network so Postgres is reachable at
`postgres:5432` and Redis at `redis:6379`.

## Configuration

All settings are environment-driven, prefixed with `PLRS_ML_`:

| Env var | Default | Notes |
| --- | --- | --- |
| `PLRS_ML_OPS_DB_URL` | `postgresql://plrs:plrs@localhost:5432/plrs` | ops DB DSN |
| `PLRS_ML_REDIS_URL` | `redis://localhost:6379/0` | Redis DSN |
| `PLRS_ML_HMAC_SECRET` | `dev-secret-replace-in-prod` | shared HMAC key for X-PLRS-Signature |
