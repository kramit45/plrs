# PLRS — IGNOU MCSP-232 Submission Package

This document is the index for the IGNOU evaluator. Everything required
to evaluate the project lives at one of the pointers below.

## Project metadata

- **Title:** PLRS — Personalized Learning Recommendation System
- **Course:** IGNOU MCA (MCAOL) MCSP-232
- **Enrolment:** 2452345135
- **Guide:** Himanshu Katiyar
- **Regional Centre:** RC Ranchi
- **Synopsis approved:** 30-Nov-2025
- **Submission date:** 30-Apr-2026
- **Release tag:** `v1.0.0`

## Submission contents

| Artefact                                  | Where                                                                                  |
| ----------------------------------------- | -------------------------------------------------------------------------------------- |
| Source code (tagged release)              | https://github.com/kramit45/plrs/releases/tag/v1.0.0                                   |
| Source archive (offline)                  | `submission/plrs-v1.0.0-source.tar.gz` (built by `scripts/build_submission.sh`)        |
| Design report (240-page PDF)              | `report.pdf` at the repo root                                                          |
| Live demo instructions                    | `docs/DEPLOYMENT.md` (under-15-min from clean clone)                                   |
| Demo walkthrough script                   | `DEMO_SCRIPT.md`                                                                       |
| Architecture overview                     | `docs/ARCHITECTURE.md` (Mermaid diagrams render on GitHub)                             |
| FR / NFR / EIR coverage matrix            | `docs/PROJECT_STATUS.md`                                                               |
| Operator runbooks                         | `docs/runbooks/RB-{1,2,3}.md`                                                          |
| Aggregated Javadoc                        | `docs/javadoc/index.html` (regenerate: `mvn javadoc:aggregate`)                        |
| Python pdoc                               | `docs/python/plrs-ml/index.html`, `docs/python/plrs-etl-worker/index.html`             |
| Live OpenAPI spec / Swagger UI            | `http://localhost:8080/v3/api-docs` and `/swagger-ui.html` (after `mvn spring-boot:run`) |
| Test reports — JaCoCo                     | `**/target/site/jacoco/`                                                               |
| Test reports — Newman regression          | `test/newman/run-full.sh` produces a CLI report; CI uploads HTML on failure            |
| Playwright traces (CI)                    | `playwright-traces/` artifact uploaded by `playwright-full-regression` job             |
| JMeter perf plan                          | `test/jmeter/recommendations-load.jmx` + observed baseline in commit message of `4fbf4ce` |
| Chaos scripts (NFR-9 + NFR-11)            | `test/chaos/*.sh` + `test/chaos/README.md`                                             |
| Backup + restore-verify                   | `scripts/backup.sh`, `scripts/restore_verify.sh`                                       |

## How to evaluate

### Read-only path (no environment required)

1. Open the GitHub release at the tag link above.
2. Read `docs/PROJECT_STATUS.md` for the full FR/NFR/EIR coverage matrix
   (42/45 FR + 32/35 NFR + 9/13 EIR implemented; remainder explicitly
   deferred to v1.1 or out of scope).
3. Browse `docs/ARCHITECTURE.md` and the design report `report.pdf`.

### Live demo path (15 minutes from clean clone)

Follow `docs/DEPLOYMENT.md` end-to-end. The smoke at the bottom
(`bash test/newman/run-full.sh`) exercises every iter's happy-path
flow against the running stack.

### Test verification path

```bash
mvn -B verify                  # ~280 unit + integration tests, JaCoCo report
cd plrs-ml && poetry install && poetry run pytest        # ML service unit tests
cd ../plrs-etl-worker && poetry install && poetry run pytest
cd ../test/newman && bash run-full.sh                    # Newman regression
```

The Playwright suite (`E2E=true mvn -pl plrs-web verify -Dtest='FullRegressionE2E'`)
runs in CI on push-to-main; macOS Chromium has a known Gatekeeper
issue, so the Linux CI runner is the canonical target.

## Reproducible submission package

`scripts/build_submission.sh` produces a single zip
(`submission/plrs-submission-v1.0.0.zip`) containing:

- `plrs-v1.0.0-source.tar.gz` (git archive of the tagged commit)
- `report.pdf`
- `javadoc/` (mvn javadoc:aggregate output)
- `python-docs/` (pdoc HTML)
- `plrs.jar` (executable Spring Boot jar)
- `test-reports-newman/` (if a Newman run produced one)

Run after tagging:
```bash
git tag -a v1.0.0 -m "PLRS v1.0.0 — IGNOU MCSP-232 Submission Release"
git push --tags
bash scripts/build_submission.sh
```

The zip is the artefact uploaded to the IGNOU LMS alongside `report.pdf`.

## Contact

- Repo issues: https://github.com/kramit45/plrs/issues
- Author email: krp.amit@gmail.com
