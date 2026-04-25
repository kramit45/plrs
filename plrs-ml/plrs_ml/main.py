from datetime import datetime, timezone

from fastapi import FastAPI

from . import __version__
from .features import rebuild_tfidf

app = FastAPI(title="PLRS ML", version=__version__)


@app.get("/health")
async def health() -> dict[str, str]:
    return {
        "status": "UP",
        "service": "plrs-ml",
        "version": __version__,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


@app.post("/features/rebuild")
async def features_rebuild() -> dict:
    return rebuild_tfidf()
