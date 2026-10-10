# Demo Recovery Guide

Every entry: symptom → verified command → expected result. All commands
were run against this stack. Replace `<user>`/`<password>` with the
operator's VERIFIER credentials (never printed here).

## Docker / services

| Symptom | Recovery |
|---|---|
| `docker ps` fails (pipe not found) | Start Docker Desktop, wait ~2 min: `Start-Process "C:\Users\chatu\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"` |
| Container not healthy | `docker compose up -d` in `D:\Projects\Prism`, wait 90 s, re-check `docker ps` |
| Backend on FAKE (`LLM PROVIDER = fake` in logs) | Set env (`LLM_PROVIDER=openai`, `LLM_BASE_URL=http://host.docker.internal:11435/v1`, `LLM_API_KEY=ollama`, `LLM_MODEL=qwen2.5:7b`, `LLM_TIMEOUT_SECONDS=900`), then `docker compose up -d backend`, wait 90 s |
| Frontend blank | `docker compose up -d frontend`; check http://localhost:5173 returns 200 |

## Ollama / model

| Symptom | Recovery |
|---|---|
| Port 11435 down | `$env:OLLAMA_MODELS='C:\Users\chatu\.ollama\models'; $env:OLLAMA_HOST='127.0.0.1:11435'; Start-Process ollama serve` |
| `qwen2.5:7b` missing from `/api/tags` | `ollama pull qwen2.5:7b` (4.7 GB). Never substitute another model silently |
| Two Ollama servers fighting (model visible in `ollama list`, 404 from API) | Kill extras, serve once with pinned `OLLAMA_HOST` + `OLLAMA_MODELS` |
| Container can't reach Ollama | `docker exec prism-backend curl -s -m 10 -o /dev/null -w '%{http_code}' http://host.docker.internal:11435/api/tags` must print 200 |

## Auth

| Symptom | Recovery |
|---|---|
| Login 429 RATE_LIMITED | Wait 70 s (10/min per-IP bucket), retry once. Do not raise limits for the demo |
| 401 on every call | Re-login; JWT TTL is 60 min with no refresh |

## Demo data

| Symptom | Recovery |
|---|---|
| Corpus 20 missing | Re-upload the 5 files from `test-pack/prism_test_materials/` via `POST /api/documents` (UTF-8 read required — PS 5.1 mangles non-ASCII otherwise) |
| Graph empty | Expected for corpus 20 (0 triples). Switch scope to corpus 1: 27 nodes / 46 edges |
| No completed debate | Debate 4 (corpus 1) is COMPLETED with report 1. Do not reconvene live on stage (15+ min CPU) |
| Chat slow | Expected: ~1–3 min per answer on CPU. Present saved session 8 and say so |
| Debate stream 401 | Fixed in c17178e (query-param token on `/stream`). Refresh the page |

## Backup

```powershell
docker exec prism-mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" prism' > prism-demo-backup.sql
```

Restore to a *fresh* container, never over the live demo database within an
hour of the presentation. Never run `docker compose down -v` on demo day.
