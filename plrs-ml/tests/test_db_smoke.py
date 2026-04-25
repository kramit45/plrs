"""Integration-only smoke tests for the db client.

Skipped on workstations without Postgres on ``PLRS_ML_OPS_DB_URL``;
CI runs them when the postgres service is up.
"""

import os

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
    reason="ops Postgres not reachable — set PLRS_ML_OPS_DB_URL",
)


@pytest_skip_no_pg
def test_ops_conn_smoke():
    from plrs_ml.db import ops_conn

    with ops_conn() as conn, conn.cursor() as cur:
        cur.execute("SELECT 1")
        assert cur.fetchone() == (1,)


@pytest_skip_no_pg
def test_fetch_all_content_returns_list():
    from plrs_ml.db import fetch_all_content

    rows = fetch_all_content()
    assert isinstance(rows, list)
