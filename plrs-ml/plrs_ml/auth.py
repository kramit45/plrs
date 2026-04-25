"""HMAC X-PLRS-Signature verification (EIR-09).

Every authenticated route requires the caller to send an
``X-PLRS-Signature`` header equal to
``hex(HMAC-SHA256(method + path + body, hmac_secret))``. The
``/health`` endpoint is intentionally exempt so liveness probes
don't need the shared secret.

Implemented as a raw ASGI middleware (not Starlette's
BaseHTTPMiddleware) because the verification needs to consume the
request body to compute the signature and then replay it to the
downstream app — BaseHTTPMiddleware's body wrapper doesn't expose
a clean re-prime path.
"""

import hashlib
import hmac
import json

from .config import settings

SIGNATURE_HEADER = "X-PLRS-Signature"
EXEMPT_PATHS = {"/health", "/openapi.json", "/docs", "/redoc"}


def compute_signature(method: str, path: str, body: bytes, secret: str) -> str:
    """Returns the lowercase-hex HMAC-SHA256 of ``method + path + body``."""
    msg = method.upper().encode() + path.encode() + body
    return hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()


class HmacAuthMiddleware:
    """ASGI middleware: rejects unsigned or mis-signed requests with 401."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope["path"]
        if path in EXEMPT_PATHS:
            await self.app(scope, receive, send)
            return

        # Drain the receive stream so we can both verify against and
        # replay the body to the downstream app.
        body = b""
        more_body = True
        while more_body:
            message = await receive()
            body += message.get("body", b"")
            more_body = message.get("more_body", False)

        headers = {
            k.decode("latin-1").lower(): v.decode("latin-1")
            for k, v in scope.get("headers", [])
        }
        provided = headers.get(SIGNATURE_HEADER.lower())
        if not provided:
            await _send_json_response(
                send, 401, {"detail": "missing X-PLRS-Signature"}
            )
            return

        expected = compute_signature(
            scope["method"], path, body, settings.hmac_secret
        )
        if not hmac.compare_digest(provided, expected):
            await _send_json_response(
                send, 401, {"detail": "invalid X-PLRS-Signature"}
            )
            return

        replayed = False

        async def receive_replay():
            nonlocal replayed
            if replayed:
                return {"type": "http.disconnect"}
            replayed = True
            return {
                "type": "http.request",
                "body": body,
                "more_body": False,
            }

        await self.app(scope, receive_replay, send)


async def _send_json_response(send, status: int, payload: dict) -> None:
    body = json.dumps(payload).encode()
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", str(len(body)).encode()),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body})
