"""HMAC middleware unit tests.

The tests build their own minimal app + middleware so the assertion
isn't entangled with whatever routes main.py happens to expose at
the time the suite runs.
"""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from plrs_ml.auth import (
    SIGNATURE_HEADER,
    HmacAuthMiddleware,
    compute_signature,
)
from plrs_ml.config import settings


def _build_client() -> TestClient:
    app = FastAPI()
    app.add_middleware(HmacAuthMiddleware)

    @app.get("/health")
    async def health():
        return {"status": "UP"}

    @app.get("/protected")
    async def protected():
        return {"ok": True}

    @app.post("/echo")
    async def echo(body: dict):
        return body

    return TestClient(app)


def test_health_is_exempt_no_signature_required():
    client = _build_client()
    assert client.get("/health").status_code == 200


def test_protected_without_signature_returns_401():
    client = _build_client()
    response = client.get("/protected")
    assert response.status_code == 401
    assert "missing" in response.json()["detail"]


def test_protected_with_wrong_signature_returns_401():
    client = _build_client()
    response = client.get(
        "/protected",
        headers={SIGNATURE_HEADER: "deadbeef"},
    )
    assert response.status_code == 401
    assert "invalid" in response.json()["detail"]


def test_protected_with_valid_signature_returns_200():
    client = _build_client()
    sig = compute_signature("GET", "/protected", b"", settings.hmac_secret)
    response = client.get(
        "/protected",
        headers={SIGNATURE_HEADER: sig},
    )
    assert response.status_code == 200
    assert response.json() == {"ok": True}


def test_post_signature_includes_body():
    client = _build_client()
    body = b'{"x":1}'
    sig = compute_signature("POST", "/echo", body, settings.hmac_secret)
    response = client.post(
        "/echo",
        content=body,
        headers={
            SIGNATURE_HEADER: sig,
            "Content-Type": "application/json",
        },
    )
    assert response.status_code == 200
    assert response.json() == {"x": 1}
