# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

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
