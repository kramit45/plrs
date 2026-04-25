"""Integration test for idempotent fact_interaction inserts.

Requires a Postgres with the V16 warehouse migration applied; skipped
when ``PLRS_ETL_DW_DB_URL`` isn't set or the DB is unreachable. CI
brings up Postgres alongside this job; locally,
``docker-compose up postgres`` is enough.
"""

import os
import uuid
from datetime import datetime, timezone

import pytest

pytestmark = pytest.mark.integration


def _postgres_available() -> bool:
    if "PLRS_ETL_DW_DB_URL" not in os.environ:
        return False
    try:
        import psycopg

        with psycopg.connect(
            os.environ["PLRS_ETL_DW_DB_URL"], connect_timeout=2
        ):
            return True
    except Exception:
        return False


pytest_skip_no_pg = pytest.mark.skipif(
    not _postgres_available(),
    reason="Postgres not reachable — set PLRS_ETL_DW_DB_URL",
)


@pytest_skip_no_pg
def test_duplicate_messages_produce_one_fact_row():
    import psycopg

    from plrs_etl.main import process_event

    user_id = str(uuid.uuid4())
    suffix = uuid.uuid4().hex[:8]
    content_id = int.from_bytes(uuid.uuid4().bytes[:4], "big") & 0x7FFFFFFF
    topic_id = int.from_bytes(uuid.uuid4().bytes[:4], "big") & 0x7FFFFFFF
    occurred_at = datetime(2026, 4, 25, 10, 0, 0, tzinfo=timezone.utc).isoformat()

    payload = {
        "user_id": user_id,
        "content_id": content_id,
        "topic_id": topic_id,
        "topic_name": f"etl-topic-{suffix}",
        "title": f"etl-content-{suffix}",
        "ctype": "VIDEO",
        "difficulty": "BEGINNER",
        "est_minutes": 5,
        "event_type": "COMPLETE",
        "occurred_at": occurred_at,
        "dwell_sec": 120,
    }

    with psycopg.connect(os.environ["PLRS_ETL_DW_DB_URL"]) as conn:
        process_event(conn, payload)
        process_event(conn, payload)
        conn.commit()

        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM plrs_dw.fact_interaction"
                " WHERE user_sk = (SELECT user_sk FROM plrs_dw.dim_user"
                " WHERE user_id = %s::uuid)",
                (user_id,),
            )
            assert cur.fetchone()[0] == 1
