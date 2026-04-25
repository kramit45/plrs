from fastapi.testclient import TestClient

from plrs_ml.main import app

client = TestClient(app)


def test_health_returns_up():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "plrs-ml"
    assert "version" in body
    assert "timestamp" in body
