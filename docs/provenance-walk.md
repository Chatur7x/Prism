# Provenance audit — Phase 12 (live read-only walk)

Date (UTC): 2026-10-03
Backend: `http://localhost:8080`, `LLM_PROVIDER=fake`
Auth: single `POST /api/auth/login` as `demo_operator_1790944169073` (role `VERIFIER`, user id 2); Bearer token reused for all GETs. No second login (rate limit 10/min respected).
Mode: READ-ONLY. Only `GET` endpoints used. No approve/reject/convene/adjudicate/synthesize/ask calls.
Endpoint catalogue cross-checked against `frontend/src/api/endpoints.ts`, `frontend/src/api/types.ts`, backend `*Controller.java`, `docs/openapi.json`, `docs/api.md`.

Corpora live:
- Corpus 1 `Meridian Group demo corpus` (id 1, owner 2, ACTIVE)
- Corpus 8 `Prose evaluation corpus` (id 8, owner 2, ACTIVE)
- Corpus 1 stats: documents 25 (all `AWAITING_APPROVAL`), triples 58 (all `APPROVED`), claims 208 (`PROPOSED` 108 / `APPROVED` 94 / `VERIFIED` 6 / `ADJUDICATED` 0), verdicts 6 (all `INSUFFICIENT_EVIDENCE`), `adjudicated` counter 6, contradictions 10 (`OPEN` 8 / `RESOLVED` 2), debates 2, quarantined 0.
- Corpus 8 stats: documents 11 (all `AWAITING_APPROVAL`), triples 12 (all `PENDING`), claims 141 (all `PROPOSED`), verdicts 0, contradictions 0, debates 0, quarantined 0.

---

## Walk A — success path (corpus 1, APPROVED triple 57)

Chosen triple: the `Aster Labs parent_organization Northstar Holdings` proposal, because its sentence is shared verbatim by a sibling claim (triple 57 / claim 159), so Document→Chunk→Triple→Claim→Entity→Graph can all be shown on one chunk.

Full ID chain (each arrow names the linking field actually read from the response):

1. Corpus 1 — `GET /api/corpora` → `{id: 1, name: "Meridian Group demo corpus"}`.
2. Document 18 — `GET /api/documents?corpusId=1&size=200` contains `{id: 18, corpusId: 1, title: "Aster budget authority", originalFilename: "18-aster-budget-authority.md", mimeType: "text/markdown", contentLength: 814, status: "AWAITING_APPROVAL"}`; confirmed via `GET /api/documents/18`. Link up: `Document.corpusId = 1`.
3. Chunk 61 — `GET /api/documents/18/chunks` returns 4 chunks; row `{id: 61, chunkIndex: 0, startOffset: 0, endOffset: 814, tokenEstimate: 204, content: "# Aster Labs — budget authority question …\nAster Labs parent_organization Northstar Holdings.\n…"}`. Link up: chunk list is scoped by document id in path (`/api/documents/18/chunks`); no `documentId` field inside the row — document membership is path-implied only (manual invariant §M1).
4. Extraction run (proposal context) — `GET /api/documents/18/progress` → `{documentId: 18, status: "AWAITING_APPROVAL", chunkCount: 4, extractionRunId: 18, runStatus: "SUCCEEDED", processedChunks: 4, totalChunks: 4, triplesFound: 3, claimsFound: 13, quarantinedCount: 0, model: "qwen2.5:7b"}`. Link up: `progress.documentId = 18`. Note: `extractionRunId: 18` is an `ExtractionRun` id, **not** a `TraceRun` id — `GET /api/traces/18` returns `{run: {id: 18, documentId: 9, operationKey: "extract:document:9"}}`, i.e. a different document (manual invariant §M2). Approval-side traces for this document do exist as `ADMIN` runs: `GET /api/traces?corpusId=1` contains ids 154–161 with `documentId: 18`, e.g. id 156 `{operationType: "ADMIN", operationKey: "approve:claim:159"}`.
5. Triple 57 — `GET /api/triples?corpusId=1&status=APPROVED` contains and `GET /api/triples/57` confirms `{id: 57, corpusId: 1, subject: "Aster Labs", predicate: "parent_organization", object: "Northstar Holdings", sourceSentence: "Aster Labs parent_organization Northstar Holdings.", sourceChunkId: 61, sourceDocumentTitle: "Aster budget authority", status: "APPROVED", decidedBy: "demo_operator_1790944169073", decidedAt: "2026-10-02T12:30:09.143784Z", decisionNote: "demo: approved as self-consistent", evidenceChunkCount: 2, createdAt: "2026-10-02T12:29:45.782136Z"}`. Links: `Triple.sourceChunkId (61)` → Chunk.id; `Triple.sourceDocumentTitle` ("Aster budget authority") matches Document 18 title by string equality only (no `sourceDocumentId` field — manual invariant §M3); `Triple.corpusId (1)` → Corpus 1. Approval hop: `status PENDING→APPROVED` + `decidedBy/decidedAt/decisionNote` (human step recorded on the row itself; fuller weight-style audit history exists only for debate weights, not for approvals).
6. Sibling claim 159 (same sentence/chunk, shows extraction produced both proposal types) — `GET /api/claims/159` → `{id: 159, corpusId: 1, subject: "Aster", claimText: "Aster Labs parent_organization Northstar Holdings.", sourceSentence: same, sourceChunkId: 61, sourceDocumentTitle: "Aster budget authority", status: "APPROVED", decidedBy: "demo_operator_1790944169073", decidedAt: "2026-10-02T12:31:33.350530Z"}`. Link: `Claim.sourceChunkId (61)` = `Triple.sourceChunkId (61)`. There is no `claimId↔tripleId` FK; co-derivation is established manually by shared chunk + sentence (manual invariant §M4).
7. Entity 7 — `GET /api/entities?corpusId=1` contains `{id: 7, displayName: "Aster Labs", normalizedName: "aster labs", type: "UNSPECIFIED", supportCount: 23, firstSeenChunkId: 1, resolutionState: "KEEP_SEPARATE"}`; `GET /api/entities/7` adds `approvedRelations: [… {tripleId: 57, predicate: "parent_organization", object: "Northstar Holdings"} …]` (plus triples 11, 12, 44, 52, 56). Links: `EntityResponse.firstSeenChunkId (1)` ≠ triple chunk (61) — first-seen is creation provenance, not this mention (manual invariant §M5); the entity→triple hop uses `approvedRelations[].tripleId = 57`, and subject match is by display-name string (`"Aster Labs"` = `Triple.subject`), not by id (manual invariant §M6).
8. Counterparty entity 1 — `GET /api/entities/1` → `{id: 1, displayName: "Northstar Holdings", normalizedName: "northstar", supportCount: 44, firstSeenChunkId: 5}`. Link: name string equals `Triple.object`.
9. Graph — `GET /api/graph/1?scope=ALL_APPROVED` → `{scope: "ALL_APPROVED", stats: {nodeCount: 18, edgeCount: 58, density: 0.1895…, communityCount: 18, maxInDegree: 6, maxOutDegree: 9}}` with edge `{from: 7, to: 1, predicate: "parent_organization", label: "Aster Labs parent_organization Northstar Holdings"}`. Links: `Edge.from (7)` = Entity 7 id, `Edge.to (1)` = Entity 1 id, `Edge.predicate` = `Triple.predicate`, `Edge.label` restates the triple. Caveat (broken-link §B3): node rows are anonymized — `{id: 7, name: "node-7", inDegree: 4, outDegree: 4, pagerank: 0.064388847}`, `{id: 1, name: "node-1", …}` — so node identity is recoverable only via edge labels + entity API, not from the node row itself. `GET /api/graph/1/pagerank` mirrors this (`entityId: 7, displayName: "node-7"`), and `GET /api/graph/1/communities` puts entityIds 1,7 together in community 0 (size 12).

Walk A verdict: unbroken. Every hop resolves live, subject to manual string/path joins §M1, §M3–§M6 and the run-id caveat §M2.

---

## Walk B — claim path (corpus 1, VERIFIED claim 114 → verdict → trace → contradiction → debate → report → chat)

Two sub-chains are joined manually because contradictions reference triples, never claims (§M7). Both halves are fully traced live; the join itself is the finding.

### B1. Claim → retrieval evidence → verdict (machine fields) → trace

1. Claim 114 — `GET /api/claims/114` → `{id: 114, corpusId: 1, subject: "The", claimText: "The restated\nposition is now the single authoritative record.", sourceSentence: same, sourceChunkId: 41, sourceDocumentTitle: "Vantage ownership restatement", status: "VERIFIED", decidedBy: "demo_operator_1790944169073", decidedAt: "2026-10-02T12:33:44.309229Z"}`. Upstream doc: `GET /api/documents/13` → `{id: 13, corpusId: 1, title: "Vantage ownership restatement", originalFilename: "13-vantage-ownership-restatement.md"}`; `GET /api/documents/13/progress` → `{documentId: 13, extractionRunId: 13, runStatus: "SUCCEEDED", triplesFound: 3, claimsFound: 13, quarantinedCount: 0, model: "qwen2.5:7b"}`. Link: `Claim.sourceChunkId (41)` → chunk 41 of document 13 (chunk list not re-pulled for doc 13; same pattern as Walk A chunk hop).
2. Verdict (list projection — detail endpoint is down, §B1) — `GET /api/verdicts?corpusId=1` row `{id: 6, claimId: 114, corpusId: 1, verdictType: "INSUFFICIENT_EVIDENCE", machineVerdictType: "INSUFFICIENT_EVIDENCE", humanVerdictType: absent, adjudicationState: "MACHINE_ONLY", overridden: false, traceRunId: 212, evidence: []}`. Links: `Verdict.claimId (114)` → Claim.id; `Verdict.traceRunId (212)` → TraceRun.id; `Verdict.corpusId (1)` → Corpus 1. Machine fields present in the row: `machineVerdictType`, `llmScore`/`rulePenalty`/`fusedScore` (null in list projection), `evidenceStatus` (present in detail shape; list rows here carry `verdictType` + `machineVerdictType` + `evidence: []` because `withEvidence=false`), `model`/`promptVersion`/`retrievalQuery`/`ruleVersion` (detail-only fields, unreadable live due to §B1 — verified against `VerificationController.VerdictResponse` + `types.ts Verdict` instead).
3. Trace 212 — `GET /api/traces/212` → run `{id: 212, operationType: "VERIFICATION", corpusId: 1, documentId: 13, status: "SUCCEEDED", operationKey: "verify:claim:114", stepCount: 7}`. Link: `operationKey` embeds the claim id (`verify:claim:114` = `Verdict.claimId`), and `run.documentId (13)` = claim's document. Steps (linking fields `parentStepId`, `inputReferenceIds`/`outputReferenceIds` as comma-separated id strings, `ruleVersion`/`promptVersion`/`model`):
   - 561 `ENGINE RETRIEVAL_STARTED` "Verification started" (children 562–567).
   - 562 `ENGINE RULE_ANALYSIS` `ruleVersion: "RULE_V1"`, output "penalty=0.000".
   - 563 `ENGINE RETRIEVAL_STARTED` "Evidence retrieval", `outputReferenceIds: "[41,26,81,9,70]"`, output "5 passages retrieved".
   - 564 `LLM LLM_REQUEST` "Judge request", `promptVersion: "JUDGE_V1"`, `model: "qwen2.5:7b"`, `inputReferenceIds: "[81,70,41,9,26]"`.
   - 565 `LLM LLM_RESPONSE` "judge:claim:114", `model: "fake-model"`.
   - 566 `ENGINE CITATION_VALIDATED`, `outputReferenceIds: "[41,26,81,9,70]"`, "5 citations accepted".
   - 567 `ENGINE VERDICT_CREATED`, output "verdict=INSUFFICIENT_EVIDENCE evidence=EVIDENCE_FOUND fusedScore=0.55 adjudication=MACHINE_ONLY", `outputReferenceIds: "[6]"` (= `Verdict.id`).
   `GET /api/traces/212/xray` regroups the same 7 steps into `engine/llm/human` (human: empty — correct per docs: the human step lives in a separate weighting/adjudication run, not in the verification run).

### B2. Contradiction → debate → argument → chair weight → synthesis/report → chat citation

Same-chunk neighbourhood for claim 114: triples 54/55 (`sourceChunkId: 41`, doc "Vantage ownership restatement", per Walk-A-style triple rows) participate in contradictions 2 (`leftTripleId: 31, rightTripleId: 54`) and 1 (`32 vs 55`), both `OPEN` with `debateId: null` — i.e. the claim's own neighbourhood has no debate/report/trace yet. The completed debate chain is therefore demonstrated on contradiction 9 (same corpus, same `RELATION_SINGLE_VALUE/CONTRA_V1` machinery, entity overlap with Walk A via Aster Labs). The B1→B2 join is manual (no FK).

1. Contradiction 9 — `GET /api/contradictions/9` → `{id: 9, corpusId: 1, contradictionType: "RELATION_CONFLICT", subjectText: "Aster Labs", predicate: "reports_to", leftDescription: "Aster Labs reports_to Northstar Holdings (document \"Aster reporting line correction\", chunk 0)", rightDescription: "Aster Labs reports_to Orion Systems (document \"Aster research governance\", chunk 0)", ruleCode: "RELATION_SINGLE_VALUE", ruleVersion: "CONTRA_V1", status: "RESOLVED", leftTripleId: 52, rightTripleId: 11, leftClaimId: absent, rightClaimId: absent, debateId: 2}`. Triple checks: `GET /api/triples/52` → `{subject: "Aster Labs", predicate: "reports_to", object: "Northstar Holdings", sourceChunkId: 32, status: "APPROVED"}`; `GET /api/triples/11` → `{subject: "Aster Labs", predicate: "reports_to", object: "Orion Systems", sourceChunkId: 12, status: "APPROVED"}`. Links: `leftTripleId/rightTripleId` → Triple.id; same subject+predicate with different objects is the conflict (checked by eye; rule text in `explanation`). All 10 contradictions have `leftClaimId/rightClaimId` null — claim↔contradiction has no FK (§M7).
2. Debate 2 — `GET /api/debates/2` → `{id: 2, contradictionId: 9, corpusId: 1, state: "COMPLETED", stateDescription: "Finished; a report is available", currentRound: 3, maxRounds: 3, topic: "Which value is correct for Aster Labs reports_to?", chair: "demo_operator_1790944169073"}`. Link back: `Debate.contradictionId (9)` = `Contradiction.id`; forward: `Contradiction.debateId (2)` = `Debate.id` (round-trip verified). Rounds `4,5,6` (roundNumbers 1,2,3), each with `HAWK/DOVE/SKEPTIC` arguments ids 10–18; e.g. argument 12 `{id: 12, round: 1, persona: "SKEPTIC", model: "fake-model", promptVersion: "SKEPTIC_V1", failed: false, stance: "deterministic-fixture", chairWeight: 5, weightedBy: "demo_operator_1790944169073", citations: [{id: 12, kind: "CHUNK", chunkId: 144, excerpt: "Meridian Group reports_to Northstar Holdings. …"}]}`; argument 10 `{id: 10, persona: "HAWK", chairWeight: 1, weightedBy: same, citations: [{kind: "CHUNK", chunkId: 144}]}`. Links: `Argument.citations[].chunkId (144)` → Chunk.id (chunk 144 not dereferenced; all nine arguments cite the same chunk 144, whose excerpt names Meridian Group rather than the debated Aster Labs subject — fixture artefact, noted as-is); `rounds[].arguments[].id` → weight target.
3. Chair weight — recorded inline as `chairWeight` + `weightedBy` on each argument (latest weight only). The append-only weight audit rows (`WeightResult {weightId, argumentId, weight, verifier, note, createdAt}` from `POST /api/debates/{id}/arguments/{argumentId}/weight`) have **no GET endpoint** — full weight history is verifiable only inside debate responses/traces (manual invariant §M8). Live trace evidence: `GET /api/traces?corpusId=1` contains `DEBATE` runs `debate:weight:13…20` and `debate:advance:2` / `synthesis:debate:2`, confirming each weighting/advance/synthesis is its own run (per `docs/api.md`, the debate story is the union).
4. Synthesis/report — `GET /api/debates/2/report` → `{reportId: 2, debateId: 2, corpusId: 1, debateState: "COMPLETED", conclusion: "Offline test mode: deterministic fixture conclusion.", model: "fake-model", promptVersion: "SYNTHESIS_V1", traceRunId: 352, blockCount: 1, citationCount: 9, blocks: [{id: 2, sequenceNo: 0, blockType: "MACHINE_RECORD", text: "…", citations: [{kind: "ARGUMENT", argumentId: 10} … {argumentId: 18}]}]}`. Links: `Report.debateId (2)` → Debate.id; `Report.traceRunId (352)` → TraceRun.id; `block.citations[].argumentId (10–18)` → Argument.id. (ReportBlockCitation also supports `CHUNK/TRIPLE/CLAIM/VERDICT` targets per `SynthesisReportResponse`; live report 2 uses only `ARGUMENT`.) `GET /api/traces/352` confirms run `{id: 352, operationType: "SYNTHESIS", status: "SUCCEEDED", operationKey: "synthesis:debate:2"}` stepCount 6 (incl. `SYNTHESIS_STARTED`, `SKEPTIC_BRIEF_CREATED` "24 machine facts … Subject: Aster Labs reports_to … rule RELATION_SINGLE_VALUE (CONTRA_V1)"). Debate 1 / report 1 (`contradictionId: 10`, `traceRunId: 250`) is the parallel completed instance.
5. Chat citation — `GET /api/chat/sessions` → `[]`. No session, no message, no citation exists live for this operator; creating one (`POST /api/chat/sessions` + `/messages`) is a mutation and was not performed. The citation shape (`ChatCitation {kind, chunkId, documentId, documentTitle, chunkIndex, excerpt}`, `ChatMessage {grounded, insufficientEvidence, retrievalCount, traceRunId, model}` per `ChatController.MessageResponse` + `types.ts`) is therefore verified against code only, not live data. This is the one hop in Walk B with no live specimen — stated explicitly.

Walk B verdict: Claim→Verdict→Trace is fully linked (modulo detail-500 forcing reliance on the list projection + trace output refs); Verdict→Contradiction has no FK and was joined manually; Contradiction→Debate→Argument→Weight→Report→Trace is fully linked; Report→Chat has no live endpoint-to-endpoint link (no sessions).

---

## Walk C — failure path (corpus 8, quarantine + progress)

Representative document 37 (`p10 extraction audit memo`), with full-corpus sweep:

- `GET /api/documents/37/progress` → `{documentId: 37, status: "AWAITING_APPROVAL", chunkCount: 3, extractionRunId: 37, runStatus: "SUCCEEDED", processedChunks: 3, totalChunks: 3, triplesFound: 0, claimsFound: 13, quarantinedCount: 0, model: "qwen2.5:7b"}`.
- `GET /api/documents/37/quarantine` → `{content: [], page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false}`.
- Sweep: docs 28–37 progress all `runStatus: "SUCCEEDED"`, `quarantinedCount: 0` (doc 28: 3 chunks / 1 triple / 17 claims; doc 33: 6 chunks / 0 triples / 23 claims; doc 36: 4 chunks / 4 triples / 18 claims; full table in audit notes); `GET /api/documents/{id}/quarantine` for each of 28–37 → all `totalElements: 0`. Cross-check `GET /api/documents/{id}/quarantine` for ids 1–38 (corpus 1 range) → likewise all empty. Corpus stats agree: corpus 8 `quarantined: 0`, corpus 1 `quarantined: 0`; corpus 8 `contradictions: 0, verdicts: 0`.
- Trace side: `GET /api/traces?corpusId=8` shows `EXTRACTION` runs (e.g. id 312 `{documentId: 37, operationKey: "extract:document:37", status: "SUCCEEDED"}`) alongside `DOCUMENT_INGESTION` runs — i.e. `progress.extractionRunId (37)` ≠ `TraceRun.id (312)` for the same document (§M2 again).

Result: **no quarantined item exists live**, so no `errorType`/`validationMessage`/`rawResponse`/`attempt`/`chunkId`/`chunkIndex` row and no quarantine→trace link can be recorded. The `QuarantineRow` contract (`{id, chunkId, chunkIndex, errorType, validationMessage, model, promptVersion, attempt, createdAt, rawResponse}` per `DocumentController.QuarantineRow`, mirrored in `types.ts QuarantineRow`) is verified against code only. Stated explicitly per brief (same treatment as Walk D when empty).

---

## Walk D — human override (corpus 1 verdicts)

- `GET /api/verdicts?corpusId=1&page=0&size=50` → 6 rows, ids 1–6 mapping claimIds 109–114 one-to-one (1→109 … 6→114), every row `{verdictType: "INSUFFICIENT_EVIDENCE", machineVerdictType: "INSUFFICIENT_EVIDENCE", humanVerdictType: absent, adjudicationState: "MACHINE_ONLY", adjudicator: absent, adjudicatedAt: absent, adjudicationNote: absent, overridden: false, traceRunId: 207…212, evidence: []}` (evidence empty by list-projection design).
- `GET /api/verdicts/{id}/history` (checked id 1) → `[]` (no superseded machine verdicts).
- No row has `adjudicationState: "HUMAN_DECISION"` (or `"CONTESTED"`), no `humanVerdictType`, no `adjudicator`. **No HUMAN_DECISION/adjudicated verdict exists live — stated explicitly.**
- Consequently the preservation property (`VerificationController.adjudicate`: sets `humanVerdictType`/`adjudicator`/`adjudicatedAt`/`adjudicationNote` while keeping `machineVerdictType` untouched; `types.ts` documents `machineVerdictType` + optional `humanVerdictType`) cannot be verified live; it is verified against code only. The list rows do confirm the machine half is always present (`machineVerdictType` on all 6).
- Naming caveat: corpus statistics report `adjudicated: 6` while `claimsByStatus.ADJUDICATED = 0` and zero verdicts carry a human decision; the 6 equals the `VERIFIED` claim count (109–114). The counter appears to mirror verification completion, not human adjudication — flagged as a naming/manual-check item, not asserted as a bug.

---

## Broken links found (read-only GETs only)

- **B1 (highest severity). `GET /api/verdicts/{id}` → 500 for all six ids (1–6).** `GET /api/verdicts?corpusId=1` succeeds; every detail fetch returns `{"status":500,"error":"INTERNAL_ERROR",…}` (e.g. id 1 `traceId 45b9442c…`, id 2 `8b970869…`; re-verified 1–6 via `urllib`, all 500). Per `docs/api.md` a 500 is always a defect. Impact: the Claim→Verdict→Evidence hop (passages with `chunkId/documentId/retrievalRank/retrievalScore`, plus `llmScore/rulePenalty/fusedScore/evidenceStatus/model/promptVersion/retrievalQuery`) is unreachable; the audit had to substitute the evidence-free list projection plus trace 207/212 `outputReferenceIds`. Frontend `verificationApi.verdict(id)` + `history(id)` callers are affected.
- **B2. `GET /api/debates?corpusId=1&size=50` (the exact path in `frontend/src/api/endpoints.ts` `contradictionApi.debates`) → 404.** No list endpoint exists in `DebateController.java`, `ContradictionController.java`, or `docs/openapi.json` (which lists only `/api/debates/{id}`, `/{id}/report`, `/fsm`, etc.). Debates are reachable only by id via `Contradiction.debateId` (9→2, 10→1). The frontend debates list is dead code paths against this backend.
- **B3. Graph node anonymization.** `GET /api/graph/1` and `/pagerank` return `displayName: "node-{id}"` for most entities (1,3,4,6,7,…) while edges carry real labels (`"Aster Labs parent_organization Northstar Holdings"`). The node row alone cannot be joined to `Entity.displayName` without the edge label or a separate entity lookup. Whether redaction-by-design or fixture artefact could not be determined read-only; either way node→entity resolution is currently manual.
- No other GET failed. `GET /api/admin/system/status` → 403 is expected (caller is `VERIFIER`, endpoint is `ADMIN`-only) and is not counted as broken.

---

## Invariants currently verified only manually (no automated check found)

- M1. Chunk→document membership is path-implied (`GET /api/documents/{id}/chunks`); `DocumentChunk` carries no `documentId`. A chunk row copied across documents is undetectable by schema.
- M2. `progress.extractionRunId` (ExtractionRun) and `TraceRun.id` are different id spaces (doc 18: run 18 vs trace 18→doc 9; doc 37: run 37 vs trace 312). No field links them; correlation is manual via `documentId` + `operationKey`.
- M3. `Triple.sourceDocumentTitle` / `Claim.sourceDocumentTitle` are display strings; the FK is `sourceChunkId` only. A renamed document breaks the title join silently.
- M4. Triple↔claim co-derivation (triple 57 ↔ claim 159, same chunk 61 + sentence) has no `tripleId↔claimId` FK.
- M5/M6. `Entity.firstSeenChunkId` is creation provenance, not mention provenance; entity↔triple join is name-string match (`displayName` vs `subject/object`) plus `approvedRelations[].tripleId`.
- M7. `Contradiction.leftClaimId/rightClaimId` are null on all 10 live rows; claim↔contradiction is manual (shared chunk/subject/predicate).
- M8. Debate chair-weight history is append-only server-side but has no GET; only the latest `chairWeight/weightedBy` is visible.
- M9. `Triple.evidenceChunkCount (2)` exceeds the single exposed `sourceChunkId`; the other evidence chunk(s) are not dereferenceable.
- M10. Quarantine→trace, verdict-detail, report→chat-citation, and human-override preservation have no live specimen (empty tables / 500 / no sessions) and were checked against code only.

---

## The 3 most valuable invariants to automate (highest ROI)

1. **Verdict detail == list projection + evidence (catches B1).** `GET /api/verdicts/{id}` must return 200 and its `id/claimId/corpusId/verdictType/machineVerdictType/adjudicationState/overridden/traceRunId` must equal the corresponding `GET /api/verdicts?corpusId=` row, with `evidence[]` non-divergent from the trace's `CITATION_VALIDATED.outputReferenceIds` for `traceRunId`. Today this test fails with 500 on 6/6 rows; it is the single highest-value regression net because it guards the entire claim-path evidence hop the UI renders.
2. **Approved-triple provenance closure (catches B3/M3/M6 drift).** For every `APPROVED` triple: `sourceChunkId` must resolve via `GET /api/documents/{id}/chunks` to a chunk whose parent document title equals `sourceDocumentTitle`; the graph (`GET /api/graph/{corpusId}?scope=ALL_APPROVED`) must contain exactly one edge with `(from,to,predicate)` matching the subject/object entities' ids and the triple predicate, with label restating subject/predicate/object; and `GET /api/entities/{subjectEntityId}` `approvedRelations` must contain the triple id. Fails today on the node-name half (edge matches, node names do not), which is precisely why it deserves a test: it pins down whether anonymization is intended and stops silent divergence between approval, entity, and graph views.
3. **Contradiction↔debate round-trip + triple standing (catches B2/M7 drift).** For every contradiction: `leftTripleId/rightTripleId` (when set) must resolve to `APPROVED` triples with identical `(subjectText,predicate)` and differing objects; `leftClaimId/rightClaimId` null-ness must be explicit in the assertion (so the day claim-links go live the test forces the join to be exercised); and when `debateId` is set, `GET /api/debates/{debateId}.contradictionId` must equal the contradiction id and vice versa, with `GET /api/debates/{debateId}/report.traceRunId` resolving to a `SUCCEEDED SYNTHESIS` trace whose `operationKey` is `synthesis:debate:{debateId}`. Fails today only on the aspirational claim-link half (documents the gap); passes on 9↔2 / 10↔1 round-trips and would have caught the phantom `GET /api/debates` list path the moment a client relied on it.

---

### Reproduction (read-only, single login)

```powershell
$body = @{ username = "demo_operator_1790944169073"; password = "DemoOperator!2026x" } | ConvertTo-Json
$login = Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method Post -ContentType "application/json" -Body $body
$H = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod -Uri "http://localhost:8080/api/triples?corpusId=1&status=APPROVED&page=0&size=50" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/triples/57" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/18/chunks" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/18/progress" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/entities/7" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/graph/1?scope=ALL_APPROVED" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/claims/114" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/verdicts?corpusId=1&page=0&size=50" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/traces/212" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/contradictions/9" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/debates/2" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/debates/2/report" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/traces/352" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/37/progress" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/37/quarantine" -Headers $H
Invoke-RestMethod -Uri "http://localhost:8080/api/chat/sessions" -Headers $H
```

No `mvn` run, no commit, no file touched except this new `docs/provenance-walk.md`.
