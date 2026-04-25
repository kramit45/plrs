"""Integration test for /cf/recompute — needs Postgres + Redis."""

import json
import os
import uuid

import pytest

pytestmark = pytest.mark.integration


def _services_up() -> bool:
    if "PLRS_ML_OPS_DB_URL" not in os.environ or "PLRS_ML_REDIS_URL" not in os.environ:
        return False
    try:
        import psycopg
        import redis

        with psycopg.connect(
            os.environ["PLRS_ML_OPS_DB_URL"], connect_timeout=2
        ):
            pass
        return redis.from_url(os.environ["PLRS_ML_REDIS_URL"], decode_responses=True).ping()
    except Exception:
        return False


pytest_skip_no_services = pytest.mark.skipif(
    not _services_up(),
    reason="Postgres + Redis required (set PLRS_ML_OPS_DB_URL and PLRS_ML_REDIS_URL)",
)


@pytest_skip_no_services
def test_recompute_writes_sim_slabs():
    import psycopg

    from plrs_ml.cache import cache
    from plrs_ml.cf import recompute_cf

    suffix = uuid.uuid4().hex[:8]
    bcrypt_stub = "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1"

    with psycopg.connect(os.environ["PLRS_ML_OPS_DB_URL"]) as conn, conn.cursor() as cur:
        # Topic + 3 content rows.
        cur.execute(
            "INSERT INTO plrs_ops.topics (topic_name, created_by)"
            " VALUES (%s, 'plrs-ml-cf-test') RETURNING topic_id",
            (f"plrs-ml-cf-{suffix}",),
        )
        topic_id = cur.fetchone()[0]
        content_ids = []
        for i in range(3):
            cur.execute(
                "INSERT INTO plrs_ops.content (topic_id, title, ctype, difficulty,"
                " est_minutes, url) VALUES (%s, %s, 'VIDEO', 'BEGINNER', 5,"
                " 'https://x.y') RETURNING content_id",
                (topic_id, f"cf-{suffix}-{i}"),
            )
            content_ids.append(cur.fetchone()[0])

        # 2 users, each completes the same 2 content items so the third
        # has no co-engagement and won't end up in their slabs.
        user_ids = []
        for tag in ("u1", "u2"):
            uid = uuid.uuid4()
            cur.execute(
                "INSERT INTO plrs_ops.users (id, email, password_hash,"
                " created_at, updated_at, created_by)"
                " VALUES (%s, %s, %s, NOW(), NOW(), 'plrs-ml-cf-test')",
                (uid, f"{tag}-{suffix}@example.com", bcrypt_stub),
            )
            user_ids.append(uid)

        for uid in user_ids:
            for cid in content_ids[:2]:
                cur.execute(
                    "INSERT INTO plrs_ops.interactions"
                    " (user_id, content_id, occurred_at, event_type)"
                    " VALUES (%s, %s, NOW(), 'COMPLETE')",
                    (uid, cid),
                )
        conn.commit()

    out = recompute_cf()
    assert out["status"] == "OK"
    assert out["items"] >= 2

    raw = cache().get(f"sim:item:{content_ids[0]}")
    assert raw is not None
    neighbours = json.loads(raw)
    # Item 0 should list item 1 as a neighbour with similarity > 0
    # because the two users co-completed both.
    assert any(
        n["contentId"] == content_ids[1] and n["similarity"] > 0
        for n in neighbours
    )
