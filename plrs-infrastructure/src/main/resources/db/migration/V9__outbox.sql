-- Traces to: §3.c.1.5 outbox_event, §2.e.3.6 (transactional outbox pattern),
-- FR-18 (event publishing), NFR-10 (at-least-once delivery), TX-06.
--
-- Filename note: V9 (not V8 as the step prompt suggested) because V8 was
-- used by the interactions migration in step 70.

CREATE TABLE plrs_ops.outbox_event (
  outbox_id      BIGSERIAL    PRIMARY KEY,
  aggregate_type VARCHAR(40)  NOT NULL,
  aggregate_id   VARCHAR(60)  NOT NULL,
  payload_json   JSONB        NOT NULL,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  delivered_at   TIMESTAMPTZ,
  attempts       SMALLINT     NOT NULL DEFAULT 0,
  last_error     VARCHAR(500),
  CONSTRAINT outbox_agg_nn          CHECK (length(trim(aggregate_type)) > 0),
  CONSTRAINT outbox_aggregate_id_nn CHECK (length(trim(aggregate_id)) > 0),
  CONSTRAINT outbox_attempts_bounded CHECK (attempts >= 0 AND attempts <= 20)
);

-- Drain job scans undelivered in FIFO order. Partial index keeps it tiny.
CREATE INDEX idx_outbox_undelivered
  ON plrs_ops.outbox_event (created_at)
  WHERE delivered_at IS NULL;

-- Dashboard / debugging: look up events by aggregate reference
CREATE INDEX idx_outbox_aggregate
  ON plrs_ops.outbox_event (aggregate_type, aggregate_id);

COMMENT ON TABLE plrs_ops.outbox_event IS
  'Transactional outbox (§2.e.3.6). Drain job publishes undelivered rows to Kafka and marks delivered_at. At-least-once delivery; consumers dedupe by outbox_id.';
