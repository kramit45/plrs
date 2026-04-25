import json
from collections import defaultdict
from datetime import datetime, timezone

from fastapi import FastAPI

from . import __version__
from .auth import HmacAuthMiddleware
from .cache import cache
from .cf import recompute_cf
from .features import rebuild_tfidf

app = FastAPI(title="PLRS ML", version=__version__)
app.add_middleware(HmacAuthMiddleware)


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


@app.post("/cf/recompute")
async def cf_recompute() -> dict:
    return recompute_cf()


@app.get("/cf/similar")
async def cf_similar(itemId: int, k: int = 50) -> dict:
    raw = cache().get(f"sim:item:{itemId}")
    if not raw:
        return {"itemId": itemId, "neighbours": []}
    return {"itemId": itemId, "neighbours": json.loads(raw)[:k]}


@app.get("/cb/similar")
async def cb_similar(itemId: int, k: int = 50) -> dict:
    """Top-k items by TF-IDF cosine to ``itemId`` (computed on the fly).

    Reads the cached ``tfidf:matrix`` payload, finds the row for
    ``itemId``, and dot-products against every other row. Returns an
    empty list if the matrix isn't cached yet or the item has no row.
    """
    raw = cache().get("tfidf:matrix")
    if not raw:
        return {"itemId": itemId, "neighbours": []}

    data = json.loads(raw)
    target = next((r for r in data["rows"] if r["id"] == itemId), None)
    if not target:
        return {"itemId": itemId, "neighbours": []}

    target_v: dict[int, float] = defaultdict(float)
    for term in target["terms"]:
        target_v[term["idx"]] = term["w"]

    sims = []
    for row in data["rows"]:
        if row["id"] == itemId:
            continue
        dot = sum(target_v.get(t["idx"], 0.0) * t["w"] for t in row["terms"])
        if dot > 0.01:
            sims.append({"contentId": row["id"], "similarity": dot})
    sims.sort(key=lambda x: x["similarity"], reverse=True)
    return {"itemId": itemId, "neighbours": sims[:k]}
