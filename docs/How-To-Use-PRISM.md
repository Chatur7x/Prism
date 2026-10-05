# How to Use OPERATION PRISM

A practical, click-by-click guide to running PRISM on your own machine and walking the
verification pipeline end to end.

> **Status: NOT RELEASE CANDIDATE.** The deterministic verification engine runs and is
> covered by 256 passing tests. **No real LLM has ever been executed against the frozen
> gold set** — that run is blocked behind credentials you must supply yourself
> (see [`evaluation.md`](evaluation.md)). Everything the model "extracts" in
> this guide comes from the local `fake` provider, which emits fixed, pre-scripted
> outputs so the workflow can be demonstrated offline. Treat extracted content as
> **synthetic demo data**, never as findings about Meridian or anyone else.

---

## 1. What you need

| Requirement | Why |
|---|---|
| **Java 21** | Backend (Spring Boot 3) |
| **Maven 3.9+** | Build/run the backend |
| **Node.js 20+** | Frontend (React + Vite) |
| **Docker Desktop** | MySQL runs in the `prism-dev` container |
| **PowerShell 5.1+** | Helper scripts |
| Optional: `python` + `reportlab` | Only to regenerate the demo PDF corpus |

No vector database, no Kafka, no Redis, no agent framework is required or used.

---

## 2. Start the stack (in this order)

Open three terminals in the repo root.

**Terminal 1 — MySQL (Docker)**

```powershell
# Start Docker Desktop if it is not already running, then:
docker start prism-dev
```

MySQL listens on **localhost:3307**, database `prism`, user/password `prism`/`prism`.

**Terminal 2 — Backend**

```powershell
cd backend
$env:DB_URL="jdbc:mysql://localhost:3307/prism?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
$env:DB_USERNAME="prism"; $env:DB_PASSWORD="prism"
$env:JWT_SECRET="prism-dev-secret-key-that-is-definitely-long-enough-32b"
$env:LLM_PROVIDER="fake"
$env:CORS_ALLOWED_ORIGINS="http://localhost:5173,http://192.168.1.35:5173"
$env:PORT="8080"
mvn spring-boot:run
```

Backend is up when `http://localhost:8080/actuator/health` returns `{"status":"UP"}`.

> If the backend fails to start with a missing-class error, stale incremental build
> output is the usual cause. Stop java, then rebuild from clean:
> `mvn clean package "-DskipTests" "-Dmaven.test.skip=true"` (keep the quotes —
> PowerShell splits an unquoted `-DskipTests`).

**Terminal 3 — Frontend**

```powershell
cd frontend
npm install     # first time only
npm run dev
```

Frontend is up at **http://localhost:5173**.

---

## 3. Sign in

Open <http://localhost:5173> and sign in with a seeded account:

| Role | Username | Password |
|---|---|---|
| **Verifier** (recommended — can approve, verify, and issue verdicts) | `demo_operator_1790944169073` | `DemoOperator!2026x` |
| Admin | `admin` | `ChangeMe!123` |

Notes:

- The sign-in request body field is **`accessToken`** (not `token`).
- Auth endpoints are rate-limited to **10 requests/minute** — wait 60 seconds if you
  are locked out.
- The demo operator token expires after **3600 seconds**; sign in again if a write
  returns 401.

---

## 4. Take the five-minute tour

Each stop below is a real page in the app and a real stage of the pipeline.

1. **Documents** (`/documents`) — the corpus. Each document is an ingestible memo or
   PDF; its state (`RAW → CHUNKED → EXTRACTED → …`) and row counts are shown per row.
2. **Approval Queue** (`/approval`) — extracted triples and claims land here as
   `PENDING`/`PROPOSED`. Approve the ones you accept; reject the ones you do not.
   Nothing becomes visible to verification until a human approves it.
3. **Verification** — approved claims are verified against the deterministic engine:
   each claim gets a status (`SUPPORTED`, `CONTRADICTED`, `INSUFFICIENT_EVIDENCE`,
   `EXAGGERATED`, `SOURCE_MISSING`, …) with the
   evidence that produced it.
4. **Contradictions** — conflicting approved triples are surfaced as contradiction
   records for a human to adjudicate.
5. **Council** — the multi-candidate view: competing model outputs are compared side
   by side before a human picks one.
6. **Glass Box** — the audit trail. Every decision shows *why*: which rule fired,
   which evidence was used, and who signed off.

### Run the demo corpus

To load the 10-document PDF corpus (10 PDFs → 50 chunks → triples + claims):

```powershell
cd scripts
.\seed-pdf-demo.ps1                # upload + wait for extraction
.\seed-pdf-demo.ps1 -ApproveAll -Verifications 60   # approve + verify
```

Then open the Contradictions page and run a scan.

---

## 5. Running the tests

```powershell
cd backend
mvn test
```

Expect **259 tests, 0 failures, 0 skipped**. Testcontainers needs Docker running;
if container starts fail, pin the API version with `-Ddocker.api.version=1.44`.

---

## 6. Switching to a real LLM

Set `LLM_PROVIDER` to your provider (see `docs/evaluation.md`) and supply the
matching API key via environment variable. The backend **refuses to silently fall
back** to `fake` — if the real provider is unreachable, requests fail loudly rather
than returning fabricated output.

To run the frozen gold-set evaluation:

```powershell
.\scripts\llm-eval.ps1 -Username '<user>' -Password '<pass>' -CorpusId <id> -RequireRealModel
```

Without real credentials this exits with code 4 and `REAL_MODEL_EXECUTION_REQUIRED`.

---

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| Backend won't start, missing class | `mvn clean package "-DskipTests" "-Dmaven.test.skip=true"` (quotes required) |
| `mvn clean` fails: "Failed to delete …prism-backend-1.0.0.jar" | The running backend locks its own jar on Windows — `taskkill /F /IM java.exe` first, then rebuild |
| `jwtService` / `rateLimit` bean `NullPointerException` on `java -jar` | Config never reached the process (env vars don't propagate to detached launches) — pass `--prism.security.jwt-secret=… --spring.datasource.url=… …` as explicit `java -jar` args |
| `GET /api/corpora` 500s with `LazyInitializationException` on `User` | Fixed in code (owner is now `join fetch`ed); rebuild the jar and restart — stale jars still carry the bug |
| Connection refused on 3307 | `docker start prism-dev` |
| 401 on writes | Token expired (3600s) — sign in again |
| 429 on auth | Rate limit 10/min — wait 60s |
| Frontend can't reach API | Check `CORS_ALLOWED_ORIGINS` includes `http://localhost:5173` |
| Pages site shows 404 | Static build only — the API is not hosted there (see `docs/limitations.md` §11) |