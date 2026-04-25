# PLRS — Newman API E2E

This directory ships two collections:

- **`plrs-iter1.*`** — original Iter 1 register/login/me/logout flow
  (covered below).
- **`plrs-iter2.*`** — Iter 2 catalogue + interaction + quiz flow.
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

## CI

The GitHub Actions `newman` job runs this collection on every push/PR
against a freshly packaged build with containerised Postgres + Redis.
See `.github/workflows/build.yml`.
