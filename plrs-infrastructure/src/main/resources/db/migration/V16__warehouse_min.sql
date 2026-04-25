-- Traces to: §3.c.2 star schema (minimum subset for Iter 3 offline eval).
-- Other dimensions and facts (dim_session, dim_quiz_attempt, fact_recommendation,
-- fact_eval_run, etc.) are deferred to Iter 4 dashboards / Iter 5.

CREATE SCHEMA IF NOT EXISTS plrs_dw;

CREATE TABLE plrs_dw.dim_date (
  date_sk       INT          PRIMARY KEY,
  date_actual   DATE         NOT NULL UNIQUE,
  year          SMALLINT     NOT NULL,
  month         SMALLINT     NOT NULL,
  day           SMALLINT     NOT NULL,
  iso_year_week VARCHAR(10)  NOT NULL
);

CREATE TABLE plrs_dw.dim_user (
  user_sk          BIGSERIAL    PRIMARY KEY,
  user_id          UUID         NOT NULL UNIQUE,
  email_domain     VARCHAR(60),
  registered_date  DATE,
  effective_from   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE plrs_dw.dim_topic (
  topic_sk         BIGSERIAL    PRIMARY KEY,
  topic_id         BIGINT       NOT NULL UNIQUE,
  topic_name       VARCHAR(120),
  parent_topic_id  BIGINT
);

CREATE TABLE plrs_dw.dim_content (
  content_sk    BIGSERIAL    PRIMARY KEY,
  content_id    BIGINT       NOT NULL UNIQUE,
  title         VARCHAR(200),
  ctype         VARCHAR(15),
  difficulty    VARCHAR(15),
  est_minutes   INT,
  topic_id      BIGINT
);

CREATE TABLE plrs_dw.fact_interaction (
  date_sk       INT          NOT NULL REFERENCES plrs_dw.dim_date(date_sk),
  user_sk       BIGINT       NOT NULL REFERENCES plrs_dw.dim_user(user_sk),
  content_sk    BIGINT       NOT NULL REFERENCES plrs_dw.dim_content(content_sk),
  topic_sk      BIGINT       NOT NULL REFERENCES plrs_dw.dim_topic(topic_sk),
  occurred_at   TIMESTAMPTZ  NOT NULL,
  event_type    VARCHAR(20)  NOT NULL,
  dwell_sec     INT,
  rating        SMALLINT,
  PRIMARY KEY (date_sk, user_sk, content_sk, occurred_at)
);

CREATE INDEX idx_fi_date ON plrs_dw.fact_interaction(date_sk);
CREATE INDEX idx_fi_user ON plrs_dw.fact_interaction(user_sk);

-- Seed dim_date for 2025-01-01..2027-12-31 (1095 days).
INSERT INTO plrs_dw.dim_date (date_sk, date_actual, year, month, day, iso_year_week)
SELECT to_char(d, 'YYYYMMDD')::int       AS date_sk,
       d::date                            AS date_actual,
       extract(year  from d)::smallint    AS year,
       extract(month from d)::smallint    AS month,
       extract(day   from d)::smallint    AS day,
       to_char(d, 'IYYY-IW')              AS iso_year_week
FROM generate_series('2025-01-01'::date, '2027-12-31'::date, '1 day'::interval) d
ON CONFLICT DO NOTHING;

COMMENT ON SCHEMA plrs_dw IS
  'Star-schema warehouse populated by the ETL worker (step 134).';
COMMENT ON TABLE plrs_dw.fact_interaction IS
  'One row per user-content interaction; idempotent on the composite PK.';
