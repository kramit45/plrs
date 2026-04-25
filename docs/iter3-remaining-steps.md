# Iteration 3 — remaining steps

Saved verbatim from the user's pasted prompts so the work can be picked
up even if the conversation gets compacted. Both committed steps 137 and
138 are already on `main`; 139 and 140 are next.

---

## STEP 139 OF 140 — test(e2e): Playwright — student takes quiz then sees recs reflect new mastery

PROJECT CONTEXT
- Repo: github.com/kramit45/plrs

STEP GOAL
Browser test: student takes quiz, returns to dashboard, recommendations card
populated and reflects topic from the quiz. Asserts the version-bust cache
invalidation actually changes the surfaced recommendations.

SCOPE
- DO:
  - plrs-web/src/test/java/com/plrs/web/e2e/RecommendationsRefreshE2E.java:
    extends PlaywrightTestBase + PostgresTestBase + RedisTestBase

    @Test recommendations_refresh_after_quiz() {
      // Pre-condition: seed catalog with 8 content items across 2 topics,
      //  including 1 quiz on topic T1.
      // 1. Register + login as student
      // 2. Visit /dashboard — capture initial recommendation list (likely
      //    cold-start popularity)
      // 3. Navigate to quiz attempt page, complete quiz with high score on T1
      // 4. Trigger admin recompute via test helper (or use a short-fixed-delay
      //    config in the test profile)
      // 5. Re-visit /dashboard — recommendation list should differ from step 2
      //    AND should include at least one T1-related item not in the original
      //    list (assert by content title presence)
      // 6. Verify cache key was invalidated: check Redis for absence of
      //    rec:topN:{userId} immediately after quiz, presence after dashboard re-fetch
    }

ACCEPTANCE
- [ ] mvn verify passes including this E2E
- [ ] Test reproducible across runs

COMMIT
- Message: test(e2e): Playwright verifying recommendations refresh after quiz attempt
- Scope: e2e/RecommendationsRefreshE2E.java + any helper for admin recompute
- Single commit

STOP AFTER COMMIT.

---

## STEP 140 OF 140 — docs: Iter 3 README + CHANGELOG + tag v0.3.0-iter3

PROJECT CONTEXT
- Repo: github.com/kramit45/plrs

STEP GOAL
Iter 3 closure: docs, changelog, demo script update, tag.

SCOPE
- DO:
  - README.md UPDATE
    - Status: "Iteration 3 complete — recommender (CF + CB + MMR), Python ML
      microservice, Kafka producer, minimum warehouse, offline eval"
    - Architecture: add ML service, Kafka, ETL worker, plrs_dw warehouse
    - New Quick Start additions:
      - docker-compose now includes plrs-ml + kafka + plrs-etl-worker
      - PLRS_KAFKA_ENABLED=true required to use real Kafka producer
      - plrs-ml HMAC secret in env
    - Iter 3 scope:
      Included:
        - 8 algorithms from §3.c.5: signal aggregation (P4.1, in popularity scorer),
          TF-IDF, item-item CF, hybrid blend, MMR rerank, prereq filter (Iter 2 +
          recommender wiring), feasibility filter, EWMA (Iter 2), offline eval (min)
        - GET /api/recommendations with rate limit
        - "Recommended for you" dashboard card
        - Python ML service with /features/rebuild, /cf/recompute, /cf/similar,
          /cb/similar, /eval/run, all HMAC-protected
        - Java→Python composite scorers with NFR-11 fallback
        - KafkaOutboxPublisher + Kafka Testcontainers IT
        - Python ETL worker consuming plrs.interactions to fact_interaction
        - Minimum warehouse: dim_date, dim_user, dim_content, dim_topic,
          fact_interaction, fact_eval_run
        - Admin /admin page with Run Evaluation tile
      Deferred to Iter 4:
        - Learning paths
        - Full admin dashboard (CTR, completion rate, cold-item exposure)
        - Diagnostic quiz (FR-23)
        - All materialised views except eval-related
        - Account lockout, email verification, password reset
        - Rate limiting on more endpoints
        - CSV import/export
        - OWASP ZAP, axe-core
      Deferred to Iter 5:
        - JMeter load tests (NFR-13/14/17)
        - Chaos tests (NFR-9/10/11)
        - Backup verification
        - Final docs

  - DEMO_SCRIPT.md UPDATE — add Iter 3 demo steps:
    - Show docker-compose ps with plrs-ml, kafka, plrs-etl-worker UP
    - Take quiz as student, return to dashboard, point at "Recommended for you"
    - Open admin /admin, click Run Evaluation, show precision@10 / ndcg@10 / coverage
    - psql query: SELECT * FROM plrs_dw.fact_interaction LIMIT 5; show rows
      flowing in via Kafka pipeline
    - psql query: SELECT * FROM plrs_dw.fact_eval_run ORDER BY ran_at DESC LIMIT 3;
    - Demonstrate NFR-11: docker stop plrs-ml, refresh dashboard, recommendations
      still load (fallback path); docker start plrs-ml, recommendations resume
      using ML

  - CHANGELOG.md APPEND
    ## [0.3.0] — Iteration 3 — 2026-04-XX
    Added: bullet per commit from `git log --oneline v0.2.0-iter2..HEAD`

  - Post-commit: tag v0.3.0-iter3

ACCEPTANCE
- [ ] README renders cleanly
- [ ] Demo script runnable end-to-end with docker-compose

COMMIT
- Message: docs: iteration 3 readme, changelog, and demo script
- Scope: README.md, DEMO_SCRIPT.md, CHANGELOG.md

POST-COMMIT
- git tag -a v0.3.0-iter3 -m "Iteration 3 — Recommender + ML service + Kafka + minimum warehouse"
- git push --tags

STOP AFTER COMMIT. ITERATION 3 COMPLETE.
