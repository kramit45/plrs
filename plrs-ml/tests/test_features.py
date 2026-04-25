"""Integration test for /features/rebuild — needs Postgres + Redis."""

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
def test_rebuild_writes_tfidf_matrix_to_redis():
    import psycopg

    from plrs_ml.cache import cache
    from plrs_ml.features import rebuild_tfidf

    # Seed at least 5 content rows with overlapping vocabulary so the
    # vectoriser has signal. Each insert uses a fresh UUID-tagged
    # title to avoid colliding with earlier test runs.
    suffix = uuid.uuid4().hex[:8]
    titles_descriptions = [
        ("Algebra introduction " + suffix, "Linear equations and variables."),
        ("Algebra polynomials " + suffix, "Factoring and roots of polynomials."),
        ("Calculus introduction " + suffix, "Limits and derivatives basics."),
        ("Calculus integrals " + suffix, "Definite integrals and series."),
        ("Statistics primer " + suffix, "Distributions and hypothesis tests."),
    ]

    with psycopg.connect(os.environ["PLRS_ML_OPS_DB_URL"]) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO plrs_ops.topics (topic_name, created_by)"
            " VALUES (%s, 'plrs-ml-test') RETURNING topic_id",
            (f"plrs-ml-topic-{suffix}",),
        )
        topic_id = cur.fetchone()[0]
        for title, desc in titles_descriptions:
            cur.execute(
                "INSERT INTO plrs_ops.content (topic_id, title, ctype, difficulty,"
                " est_minutes, url, description)"
                " VALUES (%s, %s, 'VIDEO', 'BEGINNER', 5, 'https://x.y', %s)",
                (topic_id, title, desc),
            )
        conn.commit()

    out = rebuild_tfidf()
    assert out["status"] == "OK"
    assert out["items"] >= 5
    assert out["vocab_size"] > 0

    raw = cache().get("tfidf:matrix")
    assert raw is not None
    payload = json.loads(raw)
    assert "vocab" in payload
    assert "rows" in payload
    assert payload["rows"]
