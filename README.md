# PLRS — Personalized Learning Recommendation System

[![build](https://github.com/kramit45/plrs/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/kramit45/plrs/actions/workflows/build.yml)

PLRS is the capstone project submitted for **IGNOU MCA course MCSP-232**.
It explores a recommendation system that tailors learning resources to
individual learners based on their profile, progress, and performance
signals, combining content-based and collaborative-filtering techniques
into a single deployable Java application.

## Status

**Iteration 1 complete — walking skeleton with authentication.** The
codebase boots as a Spring Boot application, persists users against
PostgreSQL, issues RS256 JWTs, serves server-rendered Thymeleaf views
alongside a JSON API, and is verified end-to-end via Playwright
(browser) and Newman (HTTP).

## Prerequisites

- **JDK 17** — Eclipse Temurin 17 recommended.
- **Maven 3.9+**.
- **Docker Desktop** (or equivalent) — required for Testcontainers and
  for running Postgres + Redis locally.
- **Node.js 20 + Newman** (optional) — only needed to run the API E2E
  collection: `npm install -g newman`.
- **Playwright Chromium** (optional) — one-time install for the
  browser E2E:

  ```sh
  mvn -pl plrs-web exec:java \
      -Dexec.mainClass="com.microsoft.playwright.CLI" \
      -Dexec.args="install chromium" \
      -Dexec.classpathScope=test
  ```

## Architecture

Four Maven modules, hexagonal layering enforced by ArchUnit:

| Module | Role | Depends on |
| --- | --- | --- |
| `plrs-domain` | Pure domain: aggregates, value objects, ports. No framework imports. | (none) |
| `plrs-application` | Use cases, application-owned ports (PasswordEncoder, TokenService, RefreshTokenStore). | `plrs-domain`, `spring-context` |
| `plrs-infrastructure` | Adapters: Spring Data JPA, Redis, BCrypt, JJWT. | `plrs-application` |
| `plrs-web` | Spring Boot entrypoint: REST controllers, Thymeleaf views, Spring Security. | `plrs-infrastructure` |

Persistence and transport:

- **PostgreSQL 15**, two schemas — `plrs_ops` (operational: users,
  user_roles) and `plrs_dw` (warehouse, reserved for Iter 2+). Schema
  migrations run by Flyway (V1 baseline, V2 users, V3 user_roles).
- **Redis 7** — backs the refresh-token allow-list.
- **JWT RS256** — 2h access tokens, 30d refresh tokens with jti tracked
  in Redis. Keys are PEM-supplied in prod (`PLRS_JWT_PRIVATE_KEY_PEM` /
  `PLRS_JWT_PUBLIC_KEY_PEM`) or generated per-JVM in dev.
- **Security** — two Spring Security filter chains: JWT bearer-token
  on `/api/**` (stateless, CORS enabled) and session-based form login
  on the web routes (CSRF via `CookieCsrfTokenRepository`). Both chains
  emit HSTS, X-Frame-Options: DENY, X-Content-Type-Options: nosniff,
  Referrer-Policy, Permissions-Policy, and a strict CSP.
- **Observability** — structured JSON logs (LogstashEncoder) with an
  `X-Request-Id` MDC filter that echoes the header on responses.

Design references: the section numbers cited in Javadoc (`§3.a`, `§7`,
etc.) point at the MCSP-232 design report submitted with the synopsis.

## Quick Start

```bash
# 1. Start Postgres and Redis.
docker run -d --name plrs-pg -p 5432:5432 \
    -e POSTGRES_DB=plrs -e POSTGRES_USER=plrs -e POSTGRES_PASSWORD=plrs \
    postgres:15-alpine
docker run -d --name plrs-redis -p 6379:6379 redis:7-alpine

# 2. Boot the app.
mvn -pl plrs-web spring-boot:run

# 3. Open the browser.
open http://localhost:8080
```

If host port `5432` is occupied (e.g. by a native Postgres install),
map the container to a spare port and override `DB_URL`:

```bash
docker run -d --name plrs-pg -p 55432:5432 \
    -e POSTGRES_DB=plrs -e POSTGRES_USER=plrs -e POSTGRES_PASSWORD=plrs \
    postgres:15-alpine
DB_URL=jdbc:postgresql://localhost:55432/plrs mvn -pl plrs-web spring-boot:run
```

## Demo script (viva)

1. Open `http://localhost:8080/register`, enter a fresh email plus a
   password of at least 10 chars mixing letters and digits (e.g.
   `Password01`). Submit.
2. Redirected to `/login?registered` with a success banner. Enter the
   same credentials and log in.
3. Redirected to `/`, which now shows **Signed in as
   *<your-email>*** with a `ROLE_STUDENT` badge.
4. Click the **Log out** button in the nav; redirected to
   `/login?logout` with a confirmation banner.
5. (API) In a second terminal, run the same flow headlessly:

   ```bash
   ./test/newman/run.sh
   ```

   The five-request Newman collection exercises register → login →
   authenticated `/api/me` → logout → logout-again (idempotency proof).

## Testing

```bash
# Unit + integration + ArchUnit + slice tests.
mvn verify

# Browser E2E (Playwright, gated behind E2E=true so the default
# verify doesn't hang on first-run Chromium download / macOS quarantine).
E2E=true mvn -pl plrs-web verify

# API E2E (requires the app running from Quick Start).
./test/newman/run.sh
```

What `mvn verify` covers:

- `plrs-domain`: JUnit 5 unit tests for every value object, the User
  aggregate, and the exception hierarchy (≈90 cases).
- `plrs-application`: use-case tests with Mockito + the ArchUnit rules
  enforcing layered dependency direction.
- `plrs-infrastructure`: Testcontainers-backed integration tests for
  Flyway migrations, JPA mappings, the Redis refresh-token store, and
  the BCrypt adapter.
- `plrs-web`: `@WebMvcTest` slices for every controller and filter
  plus a security-routing slice that drives the full filter chain.

## Iteration 1 scope

**Included**

- Walking skeleton: 4 Maven modules, GitHub Actions CI, PostgreSQL +
  Flyway, Redis, JSON logs with request-id correlation, ArchUnit rules
  enforcing domain purity and layered dependency direction.
- Domain: `User` aggregate with `STUDENT` / `INSTRUCTOR` / `ADMIN`
  additive roles, value objects (`UserId`, `Email`, `BCryptHash`,
  `AuditFields`), `PasswordPolicy`, `DomainValidationException`
  hierarchy.
- Auth: BCrypt cost-12 hashing, RS256 JWT issuance + verification,
  Redis-backed refresh-token allow-list, constant-time login flow
  resistant to account enumeration.
- Web: `POST /api/auth/{register,login,logout}`, `GET /api/me`,
  Thymeleaf `/register` + `/login` + `/` views with Bootstrap 5 CSS.
- Security: two Spring Security chains (JWT + form login), CSRF on web
  routes, CORS on `/api/**`, full header hardening (HSTS, CSP, etc.).
- Testing: Playwright browser E2E (register → login → home → logout)
  and Newman API E2E (5-request collection).

**Deferred to Iter 2**

- Catalogue, interactions, quiz, mastery (EWMA).
- Outbox pattern + Kafka for domain events.
- Value objects for the content domain.

**Deferred to Iter 4 (hardening)**

- Rate limiting and account lockout.
- OWASP ZAP scan and axe-core accessibility audit.
- Email verification and password reset flows.

**Deferred to Iter 5**

- Load tests, chaos tests, final documentation pass.

## Project metadata

- **Programme**: IGNOU MCA (Master of Computer Applications).
- **Course**: MCSP-232 — Project Work.
- **Enrolment**: 2452345135.
- **Guide**: Himanshu Katiyar.
- **Regional Centre**: Ranchi.
- **Synopsis approved**: 30 November 2025.
