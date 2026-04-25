"""Thin wrapper over psycopg for raw SELECT reads from the ops DB.

The ML service is read-only against ``plrs_ops`` — every write that
matters lands in Redis or the ``model_artifacts`` BYTEA column the
Java side owns. Keeping the surface area to a couple of fetch
helpers makes it easy to mock in tests and impossible to accidentally
mutate operational data.
"""

from contextlib import contextmanager
from typing import Iterator

import psycopg

from .config import settings


@contextmanager
def ops_conn() -> Iterator[psycopg.Connection]:
    """Yields a fresh psycopg connection bound to the ops DB."""
    with psycopg.connect(settings.ops_db_url) as conn:
        yield conn


def fetch_all_content() -> list[tuple]:
    """Returns ``(content_id, topic_id, title, description, tags)`` rows.

    ``tags`` is a Postgres array; ``None`` when the content has no tags.
    """
    with ops_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT c.content_id,
                   c.topic_id,
                   c.title,
                   c.description,
                   array_agg(t.tag) FILTER (WHERE t.tag IS NOT NULL) AS tags
            FROM plrs_ops.content c
            LEFT JOIN plrs_ops.content_tags t ON t.content_id = c.content_id
            GROUP BY c.content_id
            """
        )
        return cur.fetchall()


def fetch_recent_interactions(days: int = 180) -> list[tuple]:
    """Returns ``(user_id, content_id, event_type, dwell_sec, rating)``
    rows from the last ``days`` days. Used by the CF recompute path.
    """
    with ops_conn() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT user_id, content_id, event_type, dwell_sec, rating
            FROM plrs_ops.interactions
            WHERE occurred_at >= NOW() - %s::interval
            """,
            (f"{days} days",),
        )
        return cur.fetchall()
