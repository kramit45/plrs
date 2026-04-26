# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## [1.0.0] — Submission Release — 2026-04-30

The IGNOU MCSP-232 submission cut. Iteration 5 closes out everything
needed to defend the project to the evaluator: NFR verification
artefacts (perf + chaos + backup + integrity), live API and Javadoc
sites, the deployment + architecture + status documents, the consolidated
regression suites that run in CI, and the bias guardrail metrics
(diversity + novelty) on the offline eval.

Highlights across all five iterations:

- **Iter 1 (auth + web)** — JWT API + form-login web, RS256 JWT,
  Redis refresh-token allow-list, registration / login / me / logout.
- **Iter 2 (catalog + interactions)** — content authoring, tsvector
  search (FR-13), VIEW debounce (FR-15), quiz authoring + scoring,
  mastery-radar dashboard.
- **Iter 3 (recommender + warehouse)** — hybrid CF + content + popularity
  scorer, MMR re-ranking, feasibility filter, cache-bust on quiz,
  offline-eval harness (precision/nDCG/coverage), ML-down fallback.
- **Iter 4 (paths + admin + RBAC)** — path planner (A6), KPI dashboard,
  account lockout, CSV import/export, audit log viewer, runtime
  tunables, integrity checks job.
- **Iter 5 (NFR + docs + release)** — JMeter perf baseline, four chaos
  scripts (NFR-9 + NFR-11), backup + restore-verify, three runbooks,
  consolidated Newman + Playwright regression, OpenAPI + Javadoc +
  pdoc sites, deployment / architecture / project-status docs,
  diversity + novelty in eval, v1.0.0 submission package.

## [0.5.0] — Iteration 5 — 2026-04-26

Closes out NFR verification, documentation, and the v1.0.0 submission
artefacts. 10 commits on `main`.

### NFR verification

- `4fbf4ce` test(perf): JMeter `/api/recommendations` plan + `RecommendationsLatencyIT` (p95 = 90 ms vs 500 ms ceiling)
- `6507ccd` test(chaos): scripts verifying NFR-11 graceful degradation for ML, Redis, Kafka
- `d3e687e` test(chaos): postgres_restart verifying NFR-9 30 s recovery via Hikari retry
- `8a2423c` ops: pg_dump backup + restore-verify scripts and three runbooks (RB-1 / RB-2 / RB-3)

### Regression + CI

- `1671b00` test(newman): consolidated full-regression collection across all 4 iters (45 requests, 4 folders)
- `b91332c` test(e2e): consolidated Playwright regression suite covering all iterations

### Documentation

- `406c4cf` docs: SpringDoc OpenAPI + Swagger UI + aggregated Javadoc + pdoc for Python
- `383b0d2` docs: deployment guide, architecture diagram, project status matrix

### Bias guardrails

- `73fde1a` feat(infra): add diversity + novelty to offline eval per NFR-35

### Submission release

- `[this commit]` docs: v1.0.0 submission release with final README, CHANGELOG, SUBMISSION.md, build script

## [0.4.0] — Iteration 4 — 2026-04-26

The fourth iteration delivers the FR-31 path planner, FR-36 admin
KPI dashboard, FR-06 account lockout, FR-04 password reset,
FR-10/11 CSV bulk import + export, FR-40 runtime tunables, FR-42
audit log viewer, the nightly integrity-checks defence-in-depth
sweep, plus a security-hardening pass (per-IP login rate limit,
RBAC matrix audit, CSP tightening). 24 commits on `main`, all green
in CI.

### Features — domain (path aggregate)

- `8f832e2` feat(domain): add PathId, LearnerPathStatus, StepStatus
- `962f4cf` feat(domain): add LearnerPath aggregate with status transitions

### Features — application (path use cases + planner + admin services)

- `3e8f70a` feat(application): add PathPlanner implementing algorithm A6
- `bd27a6f` feat(application): add Generate/Start/Pause/Resume/MarkStepDone/Abandon path use cases
- `32425eb` feat(application): add KpiService reading materialised views
- `ff7718d` feat(application+web): CSV bulk import for content per FR-10
- `814c2e3` feat(security): password reset request + confirm endpoints per FR-04 (minimum)
- `c469081` feat(admin): runtime tunables in config_params with cache + admin UI

### Features — infrastructure (path adapter + warehouse + integrity)

- `091e7ee` feat(infra): add flyway v18 learner_paths + steps with one-active partial unique
- `8cd7d69` feat(infra): add LearnerPathRepository adapter with JSONB snapshot mapping
- `cbadd54` feat(infra): add flyway v19 fact_recommendation table + 6 KPI materialised views
- `fdbf4cf` feat(infra): emit fact_recommendation rows on serve to backfill KPI views
- `8b861dd` feat(infra): add nightly integrity checks job (DAG, orphans, bounds, version)

### Features — security

- `f49c955` feat(security): account lockout after 5 failed logins per FR-06
- `e4ea077` feat(web): IP-based rate limit on /api/auth/login
- `4bc957d` feat(security): consolidate full RBAC matrix with reflective PreAuthorize audit
- `7bc6247` feat(web): security headers audit and CSP tightening (no inline scripts)

### Features — web

- `66da693` feat(web): add /api/learning-path REST endpoints
- `32d5ede` feat(web): add Thymeleaf path generate + view + dashboard active-path card
- `355f4c6` feat(web): add /admin/dashboard with KPI tiles and Chart.js trends
- `772ba6a` feat(web): CSV export of catalogue per FR-11
- `1e5eeb1` feat(web): admin audit log viewer per FR-42

### Tests — e2e

- `809ed16` test(e2e): Newman Iter 4 collection covering paths, admin, lockout, CSV
- `d47defa` test(e2e): Playwright path planner + admin dashboard flows

### Docs

- (this commit) docs: iteration 4 readme, changelog, demo script

## [0.3.0] — Iteration 3 — 2026-04-26

The third iteration delivers the recommender pipeline (CF + CB +
hybrid blend + MMR), an external Python ML microservice, a Kafka
producer + ETL worker, the minimum `plrs_dw` warehouse star, and the
offline evaluation harness with an admin trigger surface. 41 commits
on `main`, all green in CI.

### Features — domain

- `6edac3c` feat(domain): add RecommendationScore and RecommendationReason VOs
- `157178c` feat(domain): add Recommendation aggregate
- `1ee5da4` feat(domain): add RecommendationRepository port

### Features — application

- `31249cb` feat(application): add PopularityScorer over recent interactions
- `cc560e8` feat(application): RecommendationService skeleton with popularity scoring + prereq + feasibility filters
- `98dafd0` feat(application): GenerateRecommendationsUseCase with version-aware Redis cache
- `62eaf57` feat(application): add CfScorer with Redis sim slab + artifact fallback
- `a8bea3d` feat(application): integrate CfScorer into RecommendationService with cf_v1 variant
- `502dde7` test(application): add CF golden-data fixture with hand-computed expected ranking
- `71e45ac` feat(application): add CbScorer using TF-IDF centroid cosine
- `3aad023` feat(application): add HybridRanker with λ=0.65 blend and cold-start fallback
- `9824468` feat(application): add MmrReranker for diversity-aware reranking
- `831ed07` feat(application): add ExplanationTemplate with deterministic per-signal reasons
- `2a09250` test(application): add full-pipeline hybrid + MMR golden test
- `a15c18c` feat(java): add MlServiceClient with HMAC signing, retry, and timeout
- `46ab178` feat(application): route CF and CB scoring through ML service with in-process fallback (NFR-11)

### Features — infrastructure

- `003570e` feat(infra): add flyway v14 recommendations table
- `117623c` feat(infra): add SpringDataRecommendationRepository adapter
- `6451242` feat(infra): add nightly ItemSimilarityJob writing item-item cosine to Redis
- `7764f25` feat(infra): add flyway v15 model_artifacts and ArtifactRepository
- `2c7d815` feat(infra): add TfIdfBuildJob computing and storing TF-IDF for catalogue
- `eb5c96b` feat(infra): add KafkaOutboxPublisher conditional on plrs.kafka.enabled
- `f06aca4` feat(infra): add Kafka Testcontainers IT verifying outbox-to-topic flow
- `fd34d07` feat(infra): add flyway v16 minimum warehouse (dim_date, dim_user, dim_content, dim_topic, fact_interaction)

### Features — web

- `e3c281f` feat(web): GET /api/recommendations with k validation and per-user rate limit
- `6e95599` feat(web): add Recommended-for-you card to student dashboard
- `a540d83` feat(web): include score breakdown in recommendation response for ADMIN
- `bee1285` feat(infra+web): add fact_eval_run table and POST /api/admin/eval/run admin endpoint
- `be592c0` feat(web): add /admin home page with Run Evaluation tile

### Features — Python ML service (plrs-ml)

- `e110f1a` chore(plrs-ml): bootstrap FastAPI project with poetry, ruff, pytest
- `f07134e` feat(plrs-ml): add /health endpoint, Dockerfile, and docker-compose service
- `bc1566c` feat(plrs-ml): add scikit + implicit + ops_db client and Redis cache helper
- `9ff3883` feat(plrs-ml): add POST /features/rebuild computing TF-IDF over content
- `bcf69e9` feat(plrs-ml): add POST /cf/recompute using implicit CosineRecommender
- `fc310fa` feat(plrs-ml): add GET /cf/similar and /cb/similar with HMAC signature middleware
- `7d5e0c5` feat(plrs-ml): add POST /eval/run computing precision@10, ndcg@10, coverage

### Features — ETL worker (plrs-etl-worker)

- `9dbd624` feat(plrs-etl-worker): bootstrap Kafka consumer writing to fact_interaction with idempotent upserts

### Tests — e2e

- `975342e` test(e2e): Newman Iter 3 flow + admin recompute trigger endpoint
- `202c9de` test(e2e): Playwright verifying recommendations refresh after quiz attempt

### Chore and docs

- `c006988` chore: ignore .jqwik-database local cache
- `c4d877e` docs: save remaining Iter 3 step prompts (139, 140) for resumption
- (this commit) docs: iteration 3 readme, changelog, and demo script

## [0.2.0] — Iteration 2 — 2026-04-25

The second iteration delivers the catalogue, interaction tracking,
quiz scoring, EWMA mastery update inside TX-01, an `@Auditable` AOP
aspect, and a Chart.js student dashboard. 53 commits on `main`, all
green in CI.

### Features — domain

- `38aa6f3` feat(domain): add TopicId typed value object
- `d6c04c6` feat(domain): add ContentId typed value object
- `1919abc` feat(domain): add Rating value object bounded to [1,5]
- `46fbb75` feat(domain): add MasteryScore value object with blendWith EWMA helper
- `e4fb2fa` feat(domain): add Topic aggregate and TopicDraft transient form
- `c1b4349` feat(domain): add TopicRepository port
- `6295ff0` feat(domain): add ContentType and Difficulty enums
- `df9ecfd` feat(domain): add Content aggregate and ContentDraft (non-quiz ctypes)
- `2750255` feat(domain): add ContentRepository port with search contract
- `cc06fec` feat(domain): add PrerequisiteEdge, CycleDetectedException, and Content.canAddPrerequisite
- `6c77918` feat(domain): add EventType and InteractionEvent value types with per-type field invariants
- `8e452d0` feat(domain): add QuizItemOption value record
- `2f154bd` feat(domain): add QuizItem entity with exactly-one-correct-option invariant
- `5496d07` feat(domain): add QuizContentDraft and Content QUIZ-ctype coupling invariants
- `db31b06` feat(domain): add AnswerSubmission, PerItemFeedback, and QuizAttempt aggregate
- `d40d659` feat(domain): add Content.score returning QuizAttempt with per-topic weights
- `7a6bbd5` feat(domain): add UserSkill aggregate with applyEwma and confidence increment

### Features — application

- `333ca1f` feat(application): add CreateTopicUseCase and CreateContentUseCase
- `17356d7` feat(application): add AddPrerequisiteUseCase with SERIALIZABLE isolation and retry-once
- `2eb3be0` feat(application): add RecordInteractionUseCase with FR-15 10-minute VIEW debounce
- `8ca865a` feat(application): add OutboxEvent value type and OutboxRepository port
- `c61b190` feat(application): add SubmitQuizAttemptUseCase skeleton with advisory lock and PersistedQuizAttempt
- `609376d` feat(application): complete SubmitQuizAttemptUseCase — EWMA + version bump + outbox (TX-01, step 90)
- `1a41ffa` feat(application): add TopNCache port and post-commit Redis invalidation in quiz submit
- `7da6519` feat(application): add StudentDashboardService aggregating mastery, completions, and attempts
- `9b5cd23` feat(application): add @Auditable aspect and audit_log persistence with TRG-4 append-only

### Features — infrastructure

- `3f57afa` feat(infra): add flyway v4 migration creating plrs_ops.topics
- `4d52f5c` feat(infra): add SpringDataTopicRepository adapter with mapper and IT
- `c1ae550` feat(infra): add flyway v5 migration for content and content_tags with GIN search index
- `b64888f` feat(infra): add ContentJpaEntity with tag ElementCollection and ContentMapper
- `a930d2a` feat(infra): add SpringDataContentRepository with tsvector-based FR-13 search
- `fd1418f` feat(infra): add flyway v7 migration creating plrs_ops.prerequisites
- `6b322fa` feat(infra): add SpringDataPrerequisiteRepository with recursive-CTE cycle detection
- `11e6928` feat(infra): add flyway v8 migration creating plrs_ops.interactions with three CHECK constraints
- `0a9565d` feat(infra): add flyway v9 migration creating plrs_ops.outbox_event
- `cc7ee00` feat(infra): add SpringDataOutboxRepository adapter, OutboxPublisher port, and LoggingOutboxPublisher no-op
- `7795041` feat(infra): add scheduled OutboxDrainJob with configurable batch size and fixed delay
- `80d622b` feat(infra): add flyway v10 quiz_items + quiz_item_options with TRG-1 ctype-coupling and TRG-2 deferred-exactly-one-correct
- `f3f0ea8` feat(infra): add flyway v11 quiz_attempts + JPA adapter with JSONB serialisation
- `a940954` feat(infra): add flyway v12 user_skills + user_skills_version column + TRG-3
- `5deaf6f` feat(infra): add UserSkillRepository adapter and UserRepository.bumpSkillsVersion

### Features — web

- `997a299` feat(web): add REST /api/topics and /api/content with INSTRUCTOR/ADMIN guards and cycle-path problem details
- `35743b2` feat(web): add GET /api/content/search with clamped pagination
- `ebfcd08` feat(web): add Thymeleaf /catalog browse view with search and pagination
- `f7d4627` feat(web): add Thymeleaf /catalog/{id} detail view with prereqs and dependents
- `b23b43b` feat(web): add POST /api/interactions and /web-api/interactions with STUDENT guard and view beacon script
- `943a410` feat(web): add /api/quiz-attempts and Thymeleaf attempt + result views with is-correct stripped from view models
- `f2983ca` feat(web): add /dashboard with Chart.js mastery radar and recent activity tables
- `fa267dd` feat(web): add /web-api/me/activity-weekly endpoint and dashboard sparkline
- `0649b6e` feat(web): consolidate @PreAuthorize coverage on Iter 2 endpoints with role-matrix IT

### Tests — architecture and e2e

- `6078aa7` test(arch): enforce outbox writes occur inside @Transactional classes (TX-01)
- `27c1ac7` test(e2e): add Iter 2 Newman collection covering catalog, interactions, quiz flows
- `d22db50` test(e2e): add Playwright student demo flow exercising register→quiz→dashboard

### Docs

- (this commit) docs: iteration 2 readme, changelog, and demo script

## [Iteration 1] — 2026-04-24

The first iteration delivers a "walking skeleton" plus the full
authentication stack. 45 commits on `main`, all green in CI.

### Build

- `1489f0c` build: add root maven parent pom with dependency and plugin management
- `f19167b` build(domain): add plrs-domain module skeleton
- `5f473d3` build(application): add plrs-application module skeleton
- `e99e479` build(infra): add plrs-infrastructure module skeleton
- `517f96b` build(infra): drop flyway-database-postgresql, rely on flyway-core 9.22
- `49b371e` build(web): add plrs-web module with spring boot entrypoint

### CI

- `ebfcac2` ci: add github actions workflow for build and test

### Features — domain

- `83f7862` feat(domain): add UserId typed value object
- `2e3b8f8` feat(domain): add Email value object with normalisation and validation
- `7d05430` feat(domain): add PasswordPolicy with min-length and complexity rule
- `324376b` feat(domain): add BCryptHash value object enforcing cost 12
- `a59d377` feat(domain): add Role enum with STUDENT, INSTRUCTOR, ADMIN
- `950d0e9` feat(domain): add AuditFields value object
- `717f17f` feat(domain): add DomainValidationException hierarchy and migrate VOs
- `8be815e` feat(domain): add User aggregate root with register and rehydrate
- `f09aac5` feat(domain): add additive role assignment to User aggregate
- `272725f` feat(domain): add UserRepository port

### Features — application

- `e166b4d` feat(application): add PasswordEncoder port with BCrypt cost-12 adapter
- `58b526d` feat(application): add TokenService port and JJWT RS256 adapter
- `d8751f3` feat(application): add RefreshTokenStore port and Redis adapter
- `4331af1` feat(application): add RegisterUserUseCase with duplicate-email guard
- `02f1647` feat(application): add LoginUseCase with timing-safe credential check
- `25494da` feat(application): add LogoutUseCase revoking refresh-token allow-list entry

### Features — infrastructure

- `c8f9ec7` feat(infra): add postgresql datasource config and testcontainers base
- `9848ed9` feat(infra): add flyway v1 baseline migration creating ops and dw schemas
- `662f565` feat(infra): add redis configuration with testcontainers connectivity test
- `c009a04` feat(infra): add flyway v2 migration creating plrs_ops.users
- `0975043` feat(infra): add flyway v3 migration creating plrs_ops.user_roles
- `2398e4f` feat(infra): add UserJpaEntity, UserRoleJpaEntity, AuditFieldsEmbeddable, and UserMapper
- `e07dcc3` feat(infra): add SpringDataUserRepository adapter implementing UserRepository port
- `f1143d0` feat(infra): add JwtProperties and JwtKeyProvider with RS256 key loading

### Features — web

- `e3d209d` feat(web): add /health endpoint
- `c06919c` feat(web): add POST /api/auth/register and global problem-detail exception handler
- `d55c5fa` feat(web): add Thymeleaf registration view at /register
- `a768dc7` feat(web): add POST /api/auth/login with problem-detail 401 mapping
- `9eab192` feat(web): add POST /api/auth/logout with 204 and 401 on invalid token
- `c938310` feat(web): add spring security with jwt api chain and form-login web chain
- `b007e6d` feat(web): add csrf, cors, and security headers to both chains
- `fbf0d1f` feat(web): add home and login views with playwright smoke test

### Tests — architecture and e2e

- `229142d` test(arch): add archunit test harness with smoke rule
- `ba4d4cf` test(arch): enforce domain module is framework-free
- `ce90410` test(arch): enforce layered module dependency direction
- `4c4674b` test(e2e): add newman collection and /api/me endpoint for iter1 e2e

### Chore and docs

- `770393b` chore: initialize repository with gitignore, license, and readme stub
- `2a820e7` chore(infra): add structured json logging and request-id mdc filter
- (this commit) docs: iteration 1 run, demo, and scope documentation
