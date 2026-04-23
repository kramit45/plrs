# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

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
