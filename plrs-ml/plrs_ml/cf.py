"""CF recompute using implicit's CosineRecommender.

Builds a weighted user-item matrix from the last 180 days of
interactions, fits a cosine-nearest-neighbour model on the item-user
transpose, and writes the top-N neighbours per item to Redis under
``sim:item:{contentId}`` keys (24 h TTL). The Java side reads the
same keys from ``RedisCfScorer`` and from the upcoming
``CompositeCfScorer`` (step 130).
"""

import json
from collections import defaultdict
from typing import Iterable

import numpy as np
from implicit.nearest_neighbours import CosineRecommender
from scipy.sparse import csr_matrix

from .cache import cache
from .db import fetch_recent_interactions

# Weights match the FR-22 implicit-feedback table the Java side uses.
EVENT_WEIGHTS: dict[str, float] = {
    "VIEW": 0.10,
    "COMPLETE": 0.80,
    "BOOKMARK": 0.40,
    "LIKE": 0.60,
}

WINDOW_DAYS = 180
TTL_SECONDS = 24 * 60 * 60
MIN_SIMILARITY = 0.01


def _row_weight(row: tuple) -> float:
    """``row`` is ``(user_id, content_id, event_type, dwell_sec, rating)``."""
    event = row[2]
    if event == "RATE":
        rating = row[4]
        return float(rating) / 5.0 if rating else 0.0
    return EVENT_WEIGHTS.get(event, 0.0)


def _build_matrix(rows: Iterable[tuple]):
    users = sorted({r[0] for r in rows})
    items = sorted({r[1] for r in rows})
    u_idx = {u: i for i, u in enumerate(users)}
    i_idx = {it: i for i, it in enumerate(items)}

    cells: dict[tuple[int, int], float] = defaultdict(float)
    for r in rows:
        cells[(u_idx[r[0]], i_idx[r[1]])] += _row_weight(r)

    data, ridx, cidx = [], [], []
    for (u, i), w in cells.items():
        if w <= 0.0:
            continue
        data.append(min(w, 1.0))
        ridx.append(u)
        cidx.append(i)

    matrix = csr_matrix(
        (np.array(data, dtype=np.float32), (ridx, cidx)),
        shape=(len(users), len(items)),
    )
    return matrix, users, items


def recompute_cf(k_neighbours: int = 50) -> dict:
    """Recomputes item-item cosine similarity and writes per-item slabs."""
    rows = fetch_recent_interactions(WINDOW_DAYS)
    if not rows:
        return {"status": "SKIPPED", "reason": "no interactions"}

    matrix, users, items = _build_matrix(rows)
    if not items:
        return {"status": "SKIPPED", "reason": "no positive interactions"}

    model = CosineRecommender(K=k_neighbours)
    # implicit expects an item-user matrix.
    model.fit(matrix.T)

    client = cache()
    for it_i, it_id in enumerate(items):
        ids, scores = model.similar_items(it_i, N=k_neighbours + 1)
        neighbours = [
            {"contentId": items[j], "similarity": float(s)}
            for j, s in zip(ids, scores)
            if items[j] != it_id and s > MIN_SIMILARITY
        ][:k_neighbours]
        client.set(
            f"sim:item:{it_id}",
            json.dumps(neighbours),
            ex=TTL_SECONDS,
        )

    return {
        "status": "OK",
        "items": len(items),
        "users": len(users),
    }
