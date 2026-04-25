"""Integration-only smoke test for the Redis cache helper."""

import os

import pytest

pytestmark = pytest.mark.integration


def _redis_available() -> bool:
    if "PLRS_ML_REDIS_URL" not in os.environ:
        return False
    try:
        import redis

        client = redis.from_url(os.environ["PLRS_ML_REDIS_URL"], decode_responses=True)
        return client.ping()
    except Exception:
        return False


@pytest.mark.skipif(
    not _redis_available(),
    reason="Redis not reachable — set PLRS_ML_REDIS_URL",
)
def test_cache_round_trip():
    from plrs_ml.cache import cache

    client = cache()
    client.set("plrs-ml:test", "ok", ex=10)
    assert client.get("plrs-ml:test") == "ok"
    client.delete("plrs-ml:test")
