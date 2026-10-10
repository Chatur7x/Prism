# PRISM Completion Progress

**Started:** 2026-10-09
**Branch:** master
**Latest commit:** 7708d12 (pushed)

## Regression Evidence — 2026-10-10

| Suite | Result | Evidence |
|---|---|---|
| Backend tests | **259 run, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (4m32s) | `mvn clean test` |
| Frontend typecheck | **PASS** (exit 0, no errors) | `npx tsc --noEmit` |
| Frontend production build | **SUCCESS** (9.00s, 75 modules) | `npx vite build`, `dist/` complete |
| Contract check | **CLEAN — 19 endpoints agreed, 4 skipped** | `scripts/contract-check.ps1` |
| Security probe | **PASSED — 28/28 checks** | `scripts/security-probe.ps1` |
| Docker stack | 3/3 healthy, login OK | `docker ps` + admin login |

## Verified State (carried forward)

| Item | Status | Evidence |
|---|---|---|
| Backend tests | 259/259, 0 failures, BUILD SUCCESS | `mvn clean test` |
| Frontend typecheck | clean | `npx tsc --noEmit` |
| Frontend build | success | `npx vite build` (1m 40s) |
| Security probe | 28/28 PASS | `scripts/security-probe.ps1` |
| Contract check | CLEAN, 18 endpoints, 5 skipped | `scripts/contract-check.ps1` |
| Docker stack | 3 containers healthy | `docker ps` |
| Real LLM provider | openai-compatible, qwen2.5.7b | `LlmModeReporter` log |

## Bugs Found & Fixed

| Bug | File | Fix |
|---|---|---|
| HTTP error body laundered as a report | `scripts/llm-eval.ps1:92` | Attach `__status` so `StatusOf()` returns real status |
| LLM_* env vars never reached the container | `docker-compose.yml` | Add pass-through with defaults |
| Backend read timeout = 120s (CPU inference exceeds it) | env / `LLM_TIMEOUT_SECONDS` | Raise to 900s |
| Council 500 on persona timeout (NPE) | `PersonaRunner.java`, `Argument.java` | Indexed loop + null-safe name |
| Smoke test hardcoded 80s settle | `scripts/smoke-test.ps1` | `-SettleTimeoutSec` param |

## Test Pack

Extracted to `test-pack/prism_test_materials/`:
- `01_meridian_consistent.txt` (MER-A) -> doc 51
- `02_meridian_conflicting.txt` (MER-B) -> doc 52
- `03_temporal_qualifiers.txt` (TEMP-C) -> doc 53
- `04_prompt_injection_untrusted.txt` (INJECT-D) -> doc 54
- `05_unsupported_and_negation.txt` (NEG-E) -> doc 55
- `06_test_cases.csv` — 16 test cases (T01-T16)

Corpus: **PRISM-QA-SYNTHETIC-2026-10** (id=20)

## Agents Running — all complete

1. ~~Real extraction eval (corpus 3, qwen2.5:7b)~~ — see Remaining Work (CPU-bound, timed out twice)
2. ~~Verification eval mismatch fix (corpus 1)~~ — root cause found (empty claim `text` on real-model rows), fix pending
3. ~~Council persona timeout fix (corpus 1)~~ — NPE fixed, deployed, committed in 7708d12
4. ~~Grounded chat end-to-end test~~ — blocked on agent rate limits; chat module untested

## Production-Readiness Audit — 2026-10-10

Already safe: healthchecks + restart policies, fail-closed JWT, restrictive CORS,
security headers (nginx + Spring), `denyAll` default, bcrypt-12, unprivileged
container user, complete `.env.example`.

Blocking public deploy (no code changes made for these — they are environment/infra):
1. No HTTPS/TLS anywhere (nginx listens on 80 only).
2. MySQL port published to host (`docker-compose.yml:52-56`).
3. Backend port published, bypassing nginx (`:116-117`).
4. Dev password fallbacks active if env unset (`prism`/`rootpw`/`ChangeMe!123`).
5. No edge rate limiting (only app-level 10/30 per min).
6. Open self-registration = abuse vector on a public instance.
7. No prod runbook (TLS, backups, log rotation, upgrades).

## Pending

- Synthetic test pack full pipeline (upload done, ingest/extract/approve/verify next)
- Real extraction eval result
- Real verification eval result
- Council real persona execution
- Synthesis after Council
- Chat groundedness + citations
- Final regression + report
