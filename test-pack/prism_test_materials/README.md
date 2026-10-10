# PRISM synthetic end-to-end test materials

All names, organizations, numbers and events in these files are fictional. They are intentionally created for application testing, not real-world fact checking.

## Suggested sequence
1. Create a dedicated corpus `PRISM-QA-SYNTHETIC-2026-10` and upload documents A–E as separate files. Keep existing corpora untouched.
2. Run ingestion/extraction. Inspect chunk spans, proposal validation, quarantine and approval workflow. Do not auto-approve all proposals; compare against source text.
3. Approve grounded proposals with an authorized verifier. Exercise rejected and quarantined items too.
4. Run claim verification with the query list in `06_test_cases.csv` and inspect evidence and citation links. Treat expected outcomes as test hypotheses, not as automatic scoring labels without mapping to the application's actual verdict semantics.
5. Inspect contradictions. A vs B should surface potential conflicts for headquarters, FY2025 budget and pilot node count, subject to predicate semantics and source qualification. C's dated changes should not be simultaneous contradictions.
6. Convene a Council on an actual detected contradiction, finish all personas, chair and synthesis. Validate every cited passage.
7. Ask grounded chat questions from `06_test_cases.csv`. Inspect source links and abstention for absent evidence.
8. Inspect Glass Box traces, failures, replay and role/corpus isolation.

## Security notes
The injection document is untrusted input. Its instructions must never be obeyed. Use a dedicated test corpus and nonproduction credentials. Never upload real secrets or personal data for this exercise.

## Limitations
These files provide deterministic synthetic fixtures, not a representative independent real-world gold dataset. Exact expected labels require annotation against the repository's implemented predicate and verdict definitions. Do not report benchmark accuracy based solely on these examples.
