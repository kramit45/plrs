"""Unit tests for /cf/similar and /cb/similar.

Stubs the Redis client via ``plrs_ml.cache._client`` so we can drive
the endpoints without a live Redis. Includes a valid HMAC header on
each request.
"""

import json

import pytest
from fastapi.testclient import TestClient

from plrs_ml import cache as cache_mod
from plrs_ml.auth import SIGNATURE_HEADER, compute_signature
from plrs_ml.config import settings
from plrs_ml.main import app


class _FakeRedis:
    def __init__(self, store: dict[str, str]):
        self.store = store

    def get(self, key: str):
        return self.store.get(key)


@pytest.fixture
def client(monkeypatch):
    return TestClient(app)


def _sign_get(path_with_query: str) -> dict[str, str]:
    # Signature is computed over the path component only (no query
    # string) — that's what the middleware reads from request.url.path.
    path = path_with_query.split("?", 1)[0]
    sig = compute_signature("GET", path, b"", settings.hmac_secret)
    return {SIGNATURE_HEADER: sig}


def _stub_cache(monkeypatch, store: dict[str, str]):
    monkeypatch.setattr(cache_mod, "_client", _FakeRedis(store))


def test_cf_similar_returns_neighbours_from_cache(monkeypatch, client):
    payload = [
        {"contentId": 200, "similarity": 0.9},
        {"contentId": 201, "similarity": 0.5},
    ]
    _stub_cache(monkeypatch, {"sim:item:100": json.dumps(payload)})

    response = client.get(
        "/cf/similar?itemId=100&k=10",
        headers=_sign_get("/cf/similar"),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["itemId"] == 100
    assert body["neighbours"] == payload


def test_cf_similar_missing_key_returns_empty(monkeypatch, client):
    _stub_cache(monkeypatch, {})

    response = client.get(
        "/cf/similar?itemId=999",
        headers=_sign_get("/cf/similar"),
    )
    assert response.status_code == 200
    assert response.json() == {"itemId": 999, "neighbours": []}


def test_cb_similar_uses_tfidf_matrix(monkeypatch, client):
    matrix = {
        "vocab": ["algebra", "calculus", "intro"],
        "rows": [
            {"id": 1, "terms": [{"idx": 0, "w": 0.7}, {"idx": 2, "w": 0.7}]},
            {"id": 2, "terms": [{"idx": 0, "w": 0.7}, {"idx": 2, "w": 0.7}]},
            {"id": 3, "terms": [{"idx": 1, "w": 1.0}]},
        ],
    }
    _stub_cache(monkeypatch, {"tfidf:matrix": json.dumps(matrix)})

    response = client.get(
        "/cb/similar?itemId=1&k=5",
        headers=_sign_get("/cb/similar"),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["itemId"] == 1
    # Item 2 shares both non-zero terms with item 1 → cosine 0.98;
    # item 3 has no overlap → filtered out by the 0.01 threshold.
    assert len(body["neighbours"]) == 1
    assert body["neighbours"][0]["contentId"] == 2
    assert body["neighbours"][0]["similarity"] > 0.5


def test_cb_similar_missing_matrix_returns_empty(monkeypatch, client):
    _stub_cache(monkeypatch, {})

    response = client.get(
        "/cb/similar?itemId=1",
        headers=_sign_get("/cb/similar"),
    )
    assert response.status_code == 200
    assert response.json() == {"itemId": 1, "neighbours": []}


def test_cf_similar_rejects_unsigned_request(client):
    response = client.get("/cf/similar?itemId=1")
    assert response.status_code == 401
