"""Minimum offline-evaluation harness — leave-last-out
precision@k / nDCG@k / coverage / diversity / novelty on the recent
interaction history.

The eval here is a **smoke check on the data path**, not a faithful
reproduction of the production recommender. It generates each user's
top-k purely from their pre-split interaction popularity (no CF, no
TF-IDF blend, no MMR), so the metrics tell you whether the harness is
plumbed correctly — not how good the real Java pipeline is. The full
recommender lives in plrs-application; offline eval against that
shape lands when the eval harness can call back into the Java service.

Diversity + novelty (NFR-35)
- diversity = mean intra-list pairwise (1 - cosine TF-IDF) over the
  top-k. TF-IDF is fit once over all catalog titles (warmed lazily).
- novelty   = mean -log2(popularity / n_users) over recommended items,
  where popularity is the count of distinct users with a positive
  event on that item in the eval window. Items unseen in the window
  get the maximum novelty (-log2(1 / n_users)).
"""

import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from typing import Any

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

from .db import fetch_all_content, fetch_recent_interactions, ops_conn

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

    # Popularity over the eval window: count distinct users per content
    # that produced a positive event. Used by the novelty metric.
    n_users_window = len(history)
    item_pop: Counter[Any] = Counter()
    for uid, hist in history.items():
        positive_items = {r[1] for r in hist if r[2] in ("COMPLETE", "LIKE")}
        for cid in positive_items:
            item_pop[cid] += 1

    # TF-IDF for diversity. Fit once over the whole catalog so vectors
    # are comparable across users. If too few items to vectorise we
    # skip diversity (return 0.0) rather than fail the harness.
    tfidf_index = _build_tfidf_index()

    precision_sum, ndcg_sum = 0.0, 0.0
    diversity_sum, novelty_sum = 0.0, 0.0
    diversity_n, novelty_n, n_eval = 0, 0, 0
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

        if tfidf_index is not None:
            d = _list_diversity(topk, tfidf_index)
            if d is not None:
                diversity_sum += d
                diversity_n += 1
        nv = _list_novelty(topk, item_pop, n_users_window)
        if nv is not None:
            novelty_sum += nv
            novelty_n += 1

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
        "diversity": diversity_sum / diversity_n if diversity_n else 0.0,
        "novelty": novelty_sum / novelty_n if novelty_n else 0.0,
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


def _build_tfidf_index() -> dict | None:
    """Returns ``{content_id: row_index, "matrix": csr}`` or ``None`` when
    the catalog is too small to vectorise."""
    rows = fetch_all_content()
    if len(rows) < 2:
        return None
    docs = [
        f"{r[2] or ''} {r[3] or ''} {' '.join(r[4] or [])}".strip()
        for r in rows
    ]
    if not any(docs):
        return None
    vec = TfidfVectorizer(
        lowercase=True,
        stop_words="english",
        max_features=5000,
        sublinear_tf=True,
        norm="l2",
    )
    matrix = vec.fit_transform(docs)
    return {"index": {r[0]: i for i, r in enumerate(rows)}, "matrix": matrix}


def _list_diversity(topk: list[Any], tfidf: dict) -> float | None:
    """Mean pairwise (1 - cosine) over rows of ``topk`` in the TF-IDF
    matrix. L2-normalised vectors mean dot product == cosine, so
    ``M @ M.T`` gives the similarity matrix in one BLAS call. Returns
    ``None`` if fewer than 2 items intersect the index."""
    idx = tfidf["index"]
    rows = [idx[c] for c in topk if c in idx]
    if len(rows) < 2:
        return None
    sub = tfidf["matrix"][rows]
    sims = (sub @ sub.T).toarray()
    n = sims.shape[0]
    # Average over the strict upper triangle (i < j) — n*(n-1)/2 pairs.
    iu = np.triu_indices(n, k=1)
    mean_sim = float(sims[iu].mean())
    return max(0.0, min(1.0, 1.0 - mean_sim))


def _list_novelty(topk: list[Any], item_pop: Counter, n_users: int) -> float | None:
    """Mean ``-log2(p / n_users)`` over recommended items, where ``p`` is
    the number of distinct users with a positive event on that item in
    the eval window. Items absent from the window get ``p=1`` (i.e. the
    maximum-novelty bucket; one phantom user)."""
    if not topk or n_users <= 0:
        return None
    novelties = []
    for c in topk:
        p = item_pop.get(c, 0) or 1
        novelties.append(-math.log2(p / n_users))
    return float(np.mean(novelties))


def _catalog_size() -> int:
    with ops_conn() as conn, conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM plrs_ops.content")
        return int(cur.fetchone()[0])
