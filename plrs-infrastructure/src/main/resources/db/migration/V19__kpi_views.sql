-- Traces to: §3.c.2.4 KPI views, FR-36.
--
-- Adds plrs_dw.fact_recommendation (the recommender-side fact table that
-- Iter 3 deferred to here) and six materialised views that back the
-- admin dashboard. Every MV gets a UNIQUE INDEX so the refresh job can
-- use REFRESH MATERIALIZED VIEW CONCURRENTLY (which requires a unique
-- key to detect inserted vs updated rows).
--
-- fact_recommendation is independent of fact_interaction — interactions
-- are observed events from the user; recommendations are predictions
-- the recommender served. The two diverge by design (an item can be
-- recommended without being interacted with, and vice versa).

CREATE TABLE plrs_dw.fact_recommendation (
  date_sk         INT          NOT NULL REFERENCES plrs_dw.dim_date(date_sk),
  user_sk         BIGINT       NOT NULL REFERENCES plrs_dw.dim_user(user_sk),
  content_sk      BIGINT       NOT NULL REFERENCES plrs_dw.dim_content(content_sk),
  topic_sk        BIGINT       NOT NULL REFERENCES plrs_dw.dim_topic(topic_sk),
  created_at      TIMESTAMPTZ  NOT NULL,
  score           NUMERIC(6,4) NOT NULL,
  rank_position   SMALLINT     NOT NULL,
  variant_name    VARCHAR(30)  NOT NULL DEFAULT 'popularity_v1',
  was_clicked     BOOLEAN      NOT NULL DEFAULT FALSE,
  was_completed   BOOLEAN      NOT NULL DEFAULT FALSE,
  rating          SMALLINT,
  PRIMARY KEY (date_sk, user_sk, content_sk, created_at),
  CONSTRAINT fact_rec_rating_bounded CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_fr_date    ON plrs_dw.fact_recommendation(date_sk);
CREATE INDEX idx_fr_variant ON plrs_dw.fact_recommendation(variant_name, created_at DESC);

COMMENT ON TABLE plrs_dw.fact_recommendation IS
  'One row per recommendation served; was_clicked/was_completed mirror the ops join.';

-- KPI MV 1: 7-day catalogue coverage (distinct served items / catalogue size).
CREATE MATERIALIZED VIEW plrs_dw.mv_coverage_7d AS
SELECT
  COALESCE(
    (SELECT COUNT(DISTINCT content_sk)
       FROM plrs_dw.fact_recommendation
      WHERE created_at >= NOW() - INTERVAL '7 days')::NUMERIC
    / NULLIF((SELECT COUNT(*) FROM plrs_dw.dim_content), 0),
    0
  ) AS coverage,
  NOW() AS computed_at;
CREATE UNIQUE INDEX mv_coverage_7d_pk ON plrs_dw.mv_coverage_7d(computed_at);

-- KPI MV 2: 7-day click-through rate.
CREATE MATERIALIZED VIEW plrs_dw.mv_ctr_7d AS
SELECT
  COALESCE(
    COUNT(*) FILTER (WHERE was_clicked)::NUMERIC / NULLIF(COUNT(*), 0),
    0
  ) AS ctr,
  NOW() AS computed_at
FROM plrs_dw.fact_recommendation
WHERE created_at >= NOW() - INTERVAL '7 days';
CREATE UNIQUE INDEX mv_ctr_7d_pk ON plrs_dw.mv_ctr_7d(computed_at);

-- KPI MV 3: per-day completion rate over the trailing 30 days.
CREATE MATERIALIZED VIEW plrs_dw.mv_completion_rate_30d AS
SELECT
  d.date_actual,
  COUNT(*) FILTER (WHERE f.was_completed)::NUMERIC
    / NULLIF(COUNT(*), 0) AS completion_rate
FROM plrs_dw.fact_recommendation f
JOIN plrs_dw.dim_date d ON d.date_sk = f.date_sk
WHERE d.date_actual >= CURRENT_DATE - 30
GROUP BY d.date_actual;
CREATE UNIQUE INDEX mv_completion_rate_30d_pk
  ON plrs_dw.mv_completion_rate_30d(date_actual);

-- KPI MV 4: 7-day cold-item exposure share — fraction of served items
-- whose content_sk has fewer than 3 prior interactions in fact_interaction.
-- "Cold" is the operational definition for Iter 4; the threshold can
-- move without a schema change.
CREATE MATERIALIZED VIEW plrs_dw.mv_cold_item_exposure_7d AS
WITH item_history AS (
  SELECT content_sk, COUNT(*) AS n
    FROM plrs_dw.fact_interaction
   GROUP BY content_sk
)
SELECT
  COALESCE(
    COUNT(*) FILTER (WHERE COALESCE(h.n, 0) < 3)::NUMERIC / NULLIF(COUNT(*), 0),
    0
  ) AS cold_share,
  NOW() AS computed_at
FROM plrs_dw.fact_recommendation r
LEFT JOIN item_history h ON h.content_sk = r.content_sk
WHERE r.created_at >= NOW() - INTERVAL '7 days';
CREATE UNIQUE INDEX mv_cold_item_exposure_7d_pk
  ON plrs_dw.mv_cold_item_exposure_7d(computed_at);

-- KPI MV 5: per-week average rating from explicit RATE interactions.
CREATE MATERIALIZED VIEW plrs_dw.mv_avg_rating_weekly AS
SELECT
  d.iso_year_week,
  AVG(f.rating)::NUMERIC(5,2) AS avg_rating,
  COUNT(*) AS n_ratings
FROM plrs_dw.fact_interaction f
JOIN plrs_dw.dim_date d ON d.date_sk = f.date_sk
WHERE f.rating IS NOT NULL
GROUP BY d.iso_year_week;
CREATE UNIQUE INDEX mv_avg_rating_weekly_pk
  ON plrs_dw.mv_avg_rating_weekly(iso_year_week);

-- KPI MV 6: latest precision@k / ndcg@k / coverage per variant.
CREATE MATERIALIZED VIEW plrs_dw.mv_precision_at_k_latest AS
SELECT DISTINCT ON (variant_name)
  variant_name,
  precision_at_k,
  ndcg_at_k,
  coverage,
  ran_at
FROM plrs_dw.fact_eval_run
ORDER BY variant_name, ran_at DESC;
CREATE UNIQUE INDEX mv_precision_at_k_latest_pk
  ON plrs_dw.mv_precision_at_k_latest(variant_name);

COMMENT ON MATERIALIZED VIEW plrs_dw.mv_coverage_7d IS
  '7-day catalogue coverage; refresh hourly from RefreshKpiViewsJob.';
COMMENT ON MATERIALIZED VIEW plrs_dw.mv_ctr_7d IS
  '7-day click-through rate.';
COMMENT ON MATERIALIZED VIEW plrs_dw.mv_completion_rate_30d IS
  'Per-day completion rate over trailing 30 days.';
COMMENT ON MATERIALIZED VIEW plrs_dw.mv_cold_item_exposure_7d IS
  'Share of 7-day served items that are cold (<3 prior interactions).';
COMMENT ON MATERIALIZED VIEW plrs_dw.mv_avg_rating_weekly IS
  'Weekly average rating from explicit RATE events.';
COMMENT ON MATERIALIZED VIEW plrs_dw.mv_precision_at_k_latest IS
  'Latest offline-eval metrics per variant; sourced from fact_eval_run.';
