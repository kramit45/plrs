"""Kafka consumer worker that drains ``plrs.interactions`` into
``plrs_dw.fact_interaction`` with idempotent dimension upserts.

Watermark / resumability is handled by Kafka's consumer-group offset
(committed manually after each successful event). SCD-2 history,
late-arriving fact handling, and partial-failure quarantine are all
deferred to Iter 4 — the §2.e.2.6 full ETL spec.
"""

import json
import logging
from datetime import datetime, timezone
from typing import Any

import psycopg
from kafka import KafkaConsumer

from .config import settings

log = logging.getLogger(__name__)


def run() -> None:
    """Forever-running consumer loop. Use only from the worker entrypoint."""
    consumer = KafkaConsumer(
        settings.topic,
        bootstrap_servers=settings.bootstrap_servers,
        group_id=settings.group_id,
        auto_offset_reset="earliest",
        enable_auto_commit=False,
        value_deserializer=lambda v: json.loads(v.decode("utf-8")),
    )
    log.info(
        "plrs-etl: consuming topic=%s group=%s", settings.topic, settings.group_id
    )
    with psycopg.connect(settings.dw_db_url) as conn:
        for msg in consumer:
            try:
                process_event(conn, msg.value)
                conn.commit()
                consumer.commit()
            except Exception:
                conn.rollback()
                log.exception("plrs-etl: event processing failed; offset uncommitted")


def process_event(conn: psycopg.Connection, payload: dict[str, Any]) -> None:
    """Idempotently writes one event to fact_interaction.

    Required payload keys: ``user_id`` (UUID str), ``content_id`` (int),
    ``topic_id`` (int), ``event_type`` (str), ``occurred_at`` (ISO-8601 str).
    Optional: ``dwell_sec`` (int), ``rating`` (int).
    """
    user_id = payload["user_id"]
    content_id = int(payload["content_id"])
    topic_id = int(payload["topic_id"])
    event_type = payload["event_type"]
    occurred_at = _parse_ts(payload["occurred_at"])
    dwell_sec = payload.get("dwell_sec")
    rating = payload.get("rating")

    date_sk = int(occurred_at.strftime("%Y%m%d"))

    with conn.cursor() as cur:
        # SCD-1 dimension upserts. ON CONFLICT DO NOTHING followed by a
        # SELECT lets us read the surrogate key whether we just inserted
        # the row or it already existed.
        cur.execute(
            """
            INSERT INTO plrs_dw.dim_user (user_id, email_domain, registered_date)
            VALUES (%s::uuid, %s, %s)
            ON CONFLICT (user_id) DO NOTHING
            """,
            (user_id, payload.get("email_domain"), payload.get("registered_date")),
        )
        cur.execute(
            "SELECT user_sk FROM plrs_dw.dim_user WHERE user_id = %s::uuid",
            (user_id,),
        )
        user_sk = cur.fetchone()[0]

        cur.execute(
            """
            INSERT INTO plrs_dw.dim_topic (topic_id, topic_name, parent_topic_id)
            VALUES (%s, %s, %s)
            ON CONFLICT (topic_id) DO NOTHING
            """,
            (topic_id, payload.get("topic_name"), payload.get("parent_topic_id")),
        )
        cur.execute(
            "SELECT topic_sk FROM plrs_dw.dim_topic WHERE topic_id = %s",
            (topic_id,),
        )
        topic_sk = cur.fetchone()[0]

        cur.execute(
            """
            INSERT INTO plrs_dw.dim_content
                (content_id, title, ctype, difficulty, est_minutes, topic_id)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (content_id) DO NOTHING
            """,
            (
                content_id,
                payload.get("title"),
                payload.get("ctype"),
                payload.get("difficulty"),
                payload.get("est_minutes"),
                topic_id,
            ),
        )
        cur.execute(
            "SELECT content_sk FROM plrs_dw.dim_content WHERE content_id = %s",
            (content_id,),
        )
        content_sk = cur.fetchone()[0]

        # Idempotent fact insert — composite PK collisions silently
        # drop, which is the desired at-least-once delivery semantics.
        cur.execute(
            """
            INSERT INTO plrs_dw.fact_interaction
                (date_sk, user_sk, content_sk, topic_sk, occurred_at,
                 event_type, dwell_sec, rating)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (date_sk, user_sk, content_sk, occurred_at) DO NOTHING
            """,
            (
                date_sk,
                user_sk,
                content_sk,
                topic_sk,
                occurred_at,
                event_type,
                dwell_sec,
                rating,
            ),
        )


def _parse_ts(value: str) -> datetime:
    # Accept either a plain ISO-8601 string or one ending with "Z".
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run()
