# PRISM Showcase Guide

Live demonstration runbook. Every ID below is real, generated against the
running local stack — not a placeholder. If any step fails, run
`scripts/showcase-healthcheck.ps1` first; it reports READY / PARTIAL /
BLOCKED with a recovery command per check.

## Startup (cold start, ~3 minutes)

```powershell
# 1. Docker (2 min to healthy)
Start-Process "C:\Users\chatu\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"
cd D:\Projects\Prism
docker compose up -d

# 2. Ollama on the pinned port with the pinned model dir (30s)
$env:OLLAMA_MODELS='C:\Users\chatu\.ollama\models'
$env:OLLAMA_HOST='127.0.0.1:11435'
Start-Process -FilePath 'C:\Users\chatu\AppData\Local\Programs\Ollama\ollama.exe' -ArgumentList 'serve'

# 3. Backend on the real provider (env first, then recreate; ~90s to healthy)
$env:LLM_PROVIDER='openai'
$env:LLM_BASE_URL='http://host.docker.internal:11435/v1'
$env:LLM_API_KEY='ollama'
$env:LLM_MODEL='qwen2.5:7b'
$env:LLM_TIMEOUT_SECONDS='900'
docker compose up -d backend
```

Verify: `docker logs prism-backend | Select-String 'LLM provider'` must print
`openai-compatible (real inference)`. If it prints `FAKE / TEST MODE`, the
`docker compose up -d` ran without the env vars in scope — set them and repeat.

```powershell
# 4. Pre-show health check (2 min, no inference)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\showcase-healthcheck.ps1 `
  -Username '<verifier>' -Password '<password>'
```

## The 12 demo steps (warm showcase, ~10 minutes, no waiting on inference)

| # | Step | Where | Real artifact |
|---|---|---|---|
| 1 | Login | http://localhost:5173 | VERIFIER account (ask operator for credentials; never printed here) |
| 2 | Select corpus | Corpora list | **PRISM-QA-SYNTHETIC-2026-10 (id=20)** — 5 test-pack docs |
| 3 | Inspect documents | Corpus 20 → Documents | doc 51 `01_meridian_consistent` … doc 55 `05_unsupported_and_negation`, all AWAITING_APPROVAL |
| 4 | View extracted knowledge | Approval queue / Claims | 7 approved claims (ids 306–312); note 0 triples — the real CPU model emitted claims only for these docs |
| 5 | Approve/reject | Approval queue (corpus 1 has 36 PROPOSED) | Approve one live on stage; rejection is permanent and recorded |
| 6 | Explore graph | Graph page, corpus 1, ALL_APPROVED | **27 nodes / 46 edges** (fallback: corpus 20 graph is empty — 0 triples) |
| 7 | Inspect verified claim | Claims, corpus 1 | claim 60 `Saltmarsh Freight operates_in Halden.` — status VERIFIED, verdict SUPPORTED, qwen2.5:7b |
| 8 | View contradiction | Contradictions, corpus 1 | 8 OPEN findings, e.g. `reports_to` Delacroix→Aurelia vs →Brightwater |
| 9 | Open completed Council | Debates → debate 4 | **COMPLETED** — 3 rounds; round 1: HAWK named timeout failure (recorded, no crash) + genuine DOVE/SKEPTIC; rounds 2–3: 6/6 genuine persona arguments with citations |
| 10 | View synthesis report | Debate 4 → Report | **report 1** — model qwen2.5:7b, SYNTHESIS_V1, 2 blocks, 5/5 citations valid; conclusion: subsidiary relationship unresolved on conflicting evidence |
| 11 | Grounded chat | Chat, corpus 20 | saved session 8: "Who directs Meridian Research Institute in September 2026?" → "Dr. Asha Rao" with chunk 207 + 210 citations |
| 12 | Glass Box replay | Traces | debate-4 run steps: ENGINE transitions + LLM persona calls + HUMAN chair weights; Play/Pause/Next |

## Timings to quote on stage (measured, CPU-bound, single laptop)

| Operation | Measured |
|---|---|
| Cold start (Docker + Ollama + backend) | ~3 min |
| One extraction call (qwen2.5:7b, CPU) | 24–46 s |
| One verification verdict | ~115–180 s |
| One Council persona | ~2–7 min (budget 180 s + 30 s grace; HAWK timed out once, recorded as named failure) |
| Full 3-round debate (debate 4) | ~14 min wall (rounds 1–2 + pre-existing round 3) |
| One synthesis report | ~7.6 min |
| One grounded chat answer | ~3 min |

Never present these as scalability figures. They are single-sample CPU timings.

## Fallbacks (when live inference is too slow for the room)

- Council + synthesis: open **debate 4 / report 1** — persisted real artifacts. Say so explicitly: "generated at <timestamp>, replayed now."
- Chat: open **session 8** transcript. Label it historical.
- Verification: quote the run record `eval/runs/verification-verification-gold-v1-20261010-103803.json` (accuracy 2/4, 6 skips with reasons).
- Graph: corpus 1 (27/46), never corpus 20 (empty).

## Recovery

| Symptom | Command |
|---|---|
| Backend on FAKE | set the 5 LLM env vars, `docker compose up -d backend` |
| Auth 429 | wait 70 s (10/min per-IP bucket), retry once |
| Ollama model missing | `ollama pull qwen2.5:7b` (4.7 GB) |
| Two Ollama servers fighting | kill extras; serve once with `OLLAMA_HOST=127.0.0.1:11435` + pinned `OLLAMA_MODELS` |
| Debate stuck | check `GET /api/debates/{id}` state; weight unweighted args, then advance |

## What is deliberately NOT claimed

- No extraction P/R/F1 from the real model (41-chunk eval timed out twice on CPU).
- Verification is 2/4 on fresh items + 4/4 persisted = 8 items total, not a benchmark.
- Corpus-20 graph is empty; the graph demo uses corpus 1.
- VERIFIED_ONLY graph is 0/0: correct — verified *triples* don't exist yet.
