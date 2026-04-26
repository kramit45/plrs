# RB-3 — Removing prereq-graph cycles

**Trigger:** the nightly `IntegrityChecksJob` writes a row to
`plrs_ops.integrity_checks` with `check_name = 'prereq_cycles'` and
`status = 'FAIL'`. The admin dashboard shows a red row, and
`detail_json` lists the offending `(root, reached, depth)` triples.

## Why cycles are bad

The prereq graph is the input to `PathPlanner` (algorithm A6 — DAG
topo-sort). A cycle violates the DAG precondition: the planner would
either loop forever or skip nodes silently. The DB has a SERIALIZABLE
write-path guard that *should* reject cycles when prerequisites are
written via the API, but defence in depth lives in this nightly check
in case something bypassed the API (manual SQL, broken migration, bug
in `PrerequisiteService`).

## Step 1 — Read the failing-row payload

```sql
SELECT detail_json
  FROM plrs_ops.integrity_checks
 WHERE check_name = 'prereq_cycles'
   AND status     = 'FAIL'
 ORDER BY run_at DESC
 LIMIT 1;
```

Each entry under `cycles` has the shape:

```json
{ "root": 123, "reached": 123, "depth": 4 }
```

`root == reached` is the smoking gun: starting from content 123 and
walking `prereq → reached` four hops you arrive back at 123.

## Step 2 — Visualize the cycle

```sql
WITH RECURSIVE walk(root, reached, depth, path) AS (
  SELECT content_id, prereq_content_id, 1, ARRAY[content_id]
    FROM plrs_ops.prerequisites
   WHERE content_id = :root      -- substitute the failing root
  UNION ALL
  SELECT w.root, p.prereq_content_id, w.depth + 1, w.path || p.content_id
    FROM walk w
    JOIN plrs_ops.prerequisites p ON p.content_id = w.reached
   WHERE w.depth < 12
)
SELECT path FROM walk WHERE reached = :root;
```

`path` is the ordered list of content IDs in the cycle. Print it; you
will use it to choose which edge to break.

## Step 3 — Decide which edge to break

Cycles are usually two-node bugs (`A → B → A`) introduced by an
admin who flipped a prereq direction by mistake. Open the admin UI
for both content rows, look at the `created_at` of each prereq edge:
the **newer** one is almost always the wrong one.

For larger cycles (3+ nodes), pull the audit log
(`/admin/audit?entity_type=prerequisite&entity_id=...`) for each edge
in `path` to see who added what. The most recently added edge is the
default suspect.

## Step 4 — Delete the offending edge

ALWAYS prefer the API so the audit trail captures the deletion:

```bash
TOKEN=$(./scripts/login-as-admin.sh)
curl -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/prerequisites/<edge_id>
```

If the API is unavailable, do it in SQL but log a manual audit row:

```sql
BEGIN;
INSERT INTO plrs_ops.audit_event(actor, entity_type, entity_id, action, detail_json)
  VALUES ('runbook:RB-3', 'prerequisite', <edge_id>, 'DELETE',
          '{"reason":"cycle removal per RB-3"}');
DELETE FROM plrs_ops.prerequisites WHERE id = <edge_id>;
COMMIT;
```

## Step 5 — Re-run the check on demand

Don't wait for the nightly job:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/admin/integrity/run-now
```

A fresh row with `status = 'OK'` confirms the cycle is gone. If it
still fails, the cycle has more than one back-edge — repeat steps 2–4.

## Step 6 — Postmortem

Note in the ticket which edge was deleted, who created it, and
whether `PrerequisiteService` should have prevented it. If the cycle
was introduced via API (not raw SQL), the SERIALIZABLE guard has a
gap and a bug fix is required.
