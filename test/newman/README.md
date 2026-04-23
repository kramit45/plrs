# PLRS Iter 1 — Newman API E2E

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
