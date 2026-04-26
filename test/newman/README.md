# PLRS — Newman API E2E

This directory ships per-iteration collections and a consolidated
full-regression collection:

- **`plrs-iter1.*`** — original Iter 1 register/login/me/logout flow
  (covered below).
- **`plrs-iter2.*`** — Iter 2 catalogue + interaction + quiz flow.
- **`plrs-iter3.*`** — Iter 3 recommendations + offline eval flow.
- **`plrs-iter4.*`** — Iter 4 path planner, CSV import/export, lockout,
  admin tunables.
- **`plrs-full-regression.*`** — consolidated regression: every Iter
  1..4 flow inside one Newman invocation, organised into four folders
  (one per iter). Generated from the per-iter collections by
  `build-full-regression.sh`. Run via `./run-full.sh` / `.\run-full.ps1`.

CI runs **both** the iter-by-iter steps and the consolidated regression
on every push/PR — the iter-by-iter reports localise failures fast, and
the consolidated run proves the flows still compose end-to-end.

  Requires `seed.sql` to be loaded first (creates a known
  `INSTRUCTOR` user and a seeded demo quiz). Run via
  `./run-iter2.sh` / `.\run-iter2.ps1`. Loading the seed:

  ```bash
  psql "$DB_URL" -f test/newman/seed.sql
  # or against a containerised DB:
  docker compose exec -T db psql -U plrs -d plrs < test/newman/seed.sql
  ```

  Iter 2 deviation: the spec mentions
  `GET /web-api/me/activity-weekly` — that endpoint lives on the
  session-cookie web chain (not the JWT API chain), so it is exercised
  by the Playwright E2E in step 99 rather than Newman.

## Iter 1 — register → login → me → logout

Five-request Postman collection that exercises the full Iter 1 JSON API flow:

1. **Register new user** — `POST /api/auth/register`, generates a unique
   `testEmail` per run so the suite is re-runnable against a single
   database.
2. **Login** — `POST /api/auth/login`, stashes `accessToken` +
   `refreshToken` in the environment for the next two requests.
3. **Authenticated `/api/me`** — `GET /api/me` with the Bearer access
   token; asserts the returned `userId` matches the registered user and
   that the roles list contains `ROLE_STUDENT`.
4. **Logout** — `POST /api/auth/logout`, expects 204 No Content.
5. **Logout again** — same request, proves the revoke is idempotent and
   still returns 204 even though the refresh token has been revoked.

Traces to: §3.e (test strategy — Newman for API E2E).

## Running locally

Prereqs:
- PLRS Postgres + Redis running (see the project root `README.md` for the
  Docker one-liners).
- PLRS application running: `DB_URL=jdbc:postgresql://localhost:55432/plrs
  mvn -pl plrs-web spring-boot:run`.
- Newman: `npm install -g newman` (requires Node.js 20+).

Then from any directory:

```bash
./test/newman/run.sh
```

Or on Windows:

```powershell
.\test\newman\run.ps1
```

Override the target:

```bash
BASE_URL=https://plrs.staging.example ./test/newman/run.sh
```

## Full regression (one invocation)

When you want a single command that exercises every iter:

```bash
# load all per-iter seeds in one shot, then run the consolidated collection
./test/newman/run-full.sh --seed
```

`run-full.sh` regenerates `plrs-full-regression.postman_collection.json`
from the per-iter sources if any of them is newer, so the consolidated
run never drifts from the iter-by-iter runs.

## CI

The GitHub Actions `newman-e2e` job runs every per-iter collection
(Iter 1..4) AND the consolidated full-regression collection on every
push/PR against a freshly packaged build with containerised Postgres +
Redis. The iter-by-iter steps localise failures fast; the consolidated
run is the pre-release smoke gate. See `.github/workflows/build.yml`.
