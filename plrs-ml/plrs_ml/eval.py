"""Minimum offline-evaluation harness — leave-last-out
precision@k / nDCG@k / coverage on the recent interaction history.

The eval here is a **smoke check on the data path**, not a faithful
reproduction of the production recommender. It generates each user's
top-k purely from their pre-split interaction popularity (no CF, no
TF-IDF, no MMR), so the metrics tell you whether the harness is
plumbed correctly — not how good the real Java pipeline is. The full
recommender lives in plrs-application; offline eval against that
shape lands in Iter 4 when the eval harness can call back into the
Java service.

Step 136 will add a {@code fact_eval_run} table; once that's in
place the JSON returned here will also be persisted as a row.
"""

import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from typing import Any

from .db import fetch_recent_interactions, ops_conn

WINDOW_DAYS = 180
MIN_HISTORY = 5
TRAIN_FRACTION = 0.9


def run_eval(variant: str = "hybrid_v1", k: int = 10) -> dict[str, Any]:
    rows = fetch_recent_interactions(WINDOW_DAYS)
    if not rows:
        return {"status": "SKIPPED", "reason": "no interactions"}

    history: dict[Any, list[tuple]] = defaultdict(list)
    for r in rows:
        history[r[0]].append(r)

    precision_sum, ndcg_sum, n_eval = 0.0, 0.0, 0
    all_recs: set[Any] = set()

    for hist in history.values():
        if len(hist) < MIN_HISTORY:
            continue
        split = max(1, int(len(hist) * TRAIN_FRACTION))
        past, future = hist[:split], hist[split:]
        future_completes = {r[1] for r in future if r[2] == "COMPLETE"}
        if not future_completes:
            continue

        topk = _topk_popularity(past, k)
        hits = set(topk) & future_completes
        precision_sum += len(hits) / k
        ndcg_sum += _ndcg(topk, future_completes, k)

        n_eval += 1
        all_recs.update(topk)

    catalog_size = _catalog_size()
    return {
        "status": "OK",
        "variant": variant,
        "k": k,
        "precision_at_k": precision_sum / n_eval if n_eval else 0.0,
        "ndcg_at_k": ndcg_sum / n_eval if n_eval else 0.0,
        "coverage": (
            len(all_recs) / catalog_size if catalog_size else 0.0
        ),
        "n_users": n_eval,
        "ran_at": datetime.now(timezone.utc).isoformat(),
    }


def _topk_popularity(past: list[tuple], k: int) -> list[Any]:
    """Top-k content ids by positive-event count in the past slice."""
    positives = [r[1] for r in past if r[2] in ("COMPLETE", "LIKE")]
    return [c for c, _ in Counter(positives).most_common(k)]


def _ndcg(topk: list[Any], relevant: set[Any], k: int) -> float:
    dcg = sum(
        1.0 / math.log2(i + 2) for i, c in enumerate(topk) if c in relevant
    )
    ideal_hits = min(len(relevant), k)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(ideal_hits))
    return (dcg / idcg) if idcg > 0 else 0.0


def _catalog_size() -> int:
    with ops_conn() as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM plrs_ops.content")
        return int(cur.fetchone()[0])
