"""Integration test for /eval/run — needs Postgres."""

import os
import uuid

import pytest

pytestmark = pytest.mark.integration


def _postgres_available() -> bool:
    if "PLRS_ML_OPS_DB_URL" not in os.environ:
        return False
    try:
        import psycopg

        with psycopg.connect(
            os.environ["PLRS_ML_OPS_DB_URL"], connect_timeout=2
        ):
            return True
    except Exception:
        return False


pytest_skip_no_pg = pytest.mark.skipif(
    not _postgres_available(),
    reason="Postgres not reachable — set PLRS_ML_OPS_DB_URL",
)


@pytest_skip_no_pg
def test_run_eval_returns_metrics_shape_for_seeded_history():
    import psycopg

    from plrs_ml.eval import run_eval

    suffix = uuid.uuid4().hex[:8]
    bcrypt_stub = "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1"

    with psycopg.connect(os.environ["PLRS_ML_OPS_DB_URL"]) as conn, conn.cursor() as cur:
        cur.execute(
            "INSERT INTO plrs_ops.topics (topic_name, created_by)"
            " VALUES (%s, 'plrs-ml-eval-test') RETURNING topic_id",
            (f"eval-topic-{suffix}",),
        )
        topic_id = cur.fetchone()[0]

        content_ids: list[int] = []
        for i in range(5):
            cur.execute(
                "INSERT INTO plrs_ops.content (topic_id, title, ctype, difficulty,"
                " est_minutes, url) VALUES (%s, %s, 'VIDEO', 'BEGINNER', 5,"
                " 'https://x.y') RETURNING content_id",
                (topic_id, f"eval-c-{suffix}-{i}"),
            )
            content_ids.append(cur.fetchone()[0])

        # One user with 10 COMPLETE events spread across the 5 items.
        uid = uuid.uuid4()
        cur.execute(
            "INSERT INTO plrs_ops.users (id, email, password_hash,"
            " created_at, updated_at, created_by)"
            " VALUES (%s, %s, %s, NOW(), NOW(), 'plrs-ml-eval-test')",
            (uid, f"eval-{suffix}@example.com", bcrypt_stub),
        )
        for i in range(10):
            cur.execute(
                "INSERT INTO plrs_ops.interactions"
                " (user_id, content_id, occurred_at, event_type)"
                " VALUES (%s, %s, NOW() - (%s ||' minutes')::interval, 'COMPLETE')",
                (uid, content_ids[i % len(content_ids)], i),
            )
        conn.commit()

    out = run_eval("hybrid_v1", k=5)

    assert out["status"] == "OK"
    for key in (
        "precision_at_k",
        "ndcg_at_k",
        "coverage",
        "diversity",
        "novelty",
        "n_users",
        "ran_at",
        "variant",
        "k",
    ):
        assert key in out, f"missing key {key} in {out}"
    assert out["precision_at_k"] >= 0.0
    assert out["ndcg_at_k"] >= 0.0
    assert 0.0 <= out["coverage"] <= 1.0
    assert 0.0 <= out["diversity"] <= 1.0
    assert out["novelty"] >= 0.0
    assert out["k"] == 5
    assert out["variant"] == "hybrid_v1"
