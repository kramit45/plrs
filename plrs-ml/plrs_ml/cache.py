"""Module-level Redis client factory.

The connection is created lazily on first ``cache()`` call and reused
across requests — Redis-py's connection pool handles concurrency
internally.
"""

import redis

from .config import settings

_client: redis.Redis | None = None


def cache() -> redis.Redis:
    """Returns the process-wide Redis client (string-decoded)."""
    global _client
    if _client is None:
        _client = redis.from_url(settings.redis_url, decode_responses=True)
    return _client
