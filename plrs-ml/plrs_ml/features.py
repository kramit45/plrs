"""TF-IDF rebuild over the content catalogue.

Reads ``(content_id, topic_id, title, description, tags)`` rows from
the ops DB, fits a sublinear-TF / L2-normalised TF-IDF model, and
writes a sparse JSON payload to Redis under ``tfidf:matrix`` (24 h
TTL). The Java side reads the same key from
``RecommendationService``'s CB scorer and from the ``/cb/similar``
endpoint added in step 128.
"""

import json
from datetime import datetime, timezone

from sklearn.feature_extraction.text import TfidfVectorizer

from .cache import cache
from .db import fetch_all_content

REDIS_KEY = "tfidf:matrix"
TTL_SECONDS = 24 * 60 * 60
MIN_ITEMS = 5


def rebuild_tfidf() -> dict:
    """Recomputes TF-IDF and writes the sparse matrix to Redis.

    Returns a status dict — ``OK`` with item/vocab counts on success,
    ``SKIPPED`` if there are too few items to train a useful vectoriser.
    """
    rows = fetch_all_content()
    if len(rows) < MIN_ITEMS:
        return {
            "status": "SKIPPED",
            "reason": "too few items",
            "items": len(rows),
        }

    ids = [r[0] for r in rows]
    docs = [
        f"{r[2] or ''} {r[3] or ''} {' '.join(r[4] or [])}".strip()
        for r in rows
    ]

    vec = TfidfVectorizer(
        lowercase=True,
        stop_words="english",
        min_df=2,
        max_df=0.6,
        max_features=10000,
        sublinear_tf=True,
        norm="l2",
    )
    matrix = vec.fit_transform(docs)
    vocab = vec.get_feature_names_out().tolist()

    rows_payload = []
    for i, cid in enumerate(ids):
        row = matrix.getrow(i)
        terms = [
            {"idx": int(j), "w": float(v)}
            for j, v in zip(row.indices, row.data)
        ]
        rows_payload.append({"id": cid, "terms": terms})

    payload = {
        "vocab": vocab,
        "rows": rows_payload,
        "computed_at": datetime.now(timezone.utc).isoformat(),
    }
    cache().set(REDIS_KEY, json.dumps(payload), ex=TTL_SECONDS)
    return {
        "status": "OK",
        "items": len(rows),
        "vocab_size": len(vocab),
    }
