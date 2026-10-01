package com.prism.debate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic machine evidence assembled for the Skeptic.
 *
 * <p>This is the single most important honesty property in PRISM. Every fact in
 * this brief was read from the database at the moment the brief was built, and
 * every one carries the id under which the Skeptic may cite it. There is no
 * hardcoded text: a statement like "claim 3 is unsupported" appears only if
 * claim 3 actually holds a SUPPORTED/SOURCE_MISSING verdict right now.
 *
 * <p>Rendering is a pure function of the fact list, so what the Skeptic reads is
 * exactly what the Glass Box will show the auditor afterwards.
 */
public final class SkepticBrief {

    /** Prefix identifying a PageRank fact, e.g. {@code PR-12}. */
    public static final String PAGERANK_PREFIX = "PR-";
    /** Prefix identifying a community fact, e.g. {@code COM-0}. */
    public static final String COMMUNITY_PREFIX = "COM-";
    /** Prefix identifying a verdict fact, e.g. {@code VD-42}. */
    public static final String VERDICT_PREFIX = "VD-";
    /** Prefix identifying a claim fact, e.g. {@code CL-17}. */
    public static final String CLAIM_PREFIX = "CL-";
    /** Prefix identifying a triple fact, e.g. {@code TR-9}. */
    public static final String TRIPLE_PREFIX = "TR-";
    /** Prefix identifying an absence-of-evidence fact, e.g. {@code GAP-1}. */
    public static final String GAP_PREFIX = "GAP-";

    /**
     * One machine fact. {@code id} is the only handle the Skeptic may cite, and
     * {@code text} is a factual rendering computed here, not by a model.
     */
    public record MachineFact(String id, String category, String text, Long sourceId) {

        public MachineFact {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("machine fact id is required");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("machine fact text is required");
            }
        }
    }

    private final List<MachineFact> facts = new ArrayList<>();
    private final Map<String, String> entityNames = new LinkedHashMap<>();
    private String subjectText = "";

    public List<MachineFact> facts() {
        return List.copyOf(facts);
    }

    /** Ids the Skeptic is permitted to cite. Anything else is rejected. */
    public java.util.Set<String> allowedIds() {
        return facts.stream().map(MachineFact::id).collect(java.util.stream.Collectors.toSet());
    }

    public void setSubject(String subject) {
        this.subjectText = subject == null ? "" : subject;
    }

    public void registerEntity(Long entityId, String name) {
        if (entityId != null && name != null) {
            entityNames.put(String.valueOf(entityId), name);
        }
    }

    // ---- fact builders -----------------------------------------------------

    public MachineFact addVerdict(Long claimId, Long verdictId, String verdictType,
                                  String evidenceStatus, Double fusedScore, String machineNote) {
        String text = "Claim " + claimId + " has verdict " + verdictType
                + " (evidence status " + evidenceStatus
                + (fusedScore == null ? "" : ", fused score " + fusedScore) + ").";
        if (machineNote != null && !machineNote.isBlank()) {
            text = text + " " + abbreviate(machineNote, 400);
        }
        return add(VERDICT_PREFIX + verdictId, "VERDICT", text, claimId);
    }

    public MachineFact addClaimStatus(Long claimId, String status, String subject, String claimText) {
        return add(CLAIM_PREFIX + claimId, "CLAIM",
                "Claim " + claimId + " (" + subject + ") has status " + status
                        + ": \"" + abbreviate(claimText, 300) + "\"", claimId);
    }

    public MachineFact addTriple(Long tripleId, String subject, String predicate, String object,
                                 String status) {
        return add(TRIPLE_PREFIX + tripleId, "TRIPLE",
                "Triple " + tripleId + " asserts " + subject + " " + predicate + " " + object
                        + " with status " + status + ".", tripleId);
    }

    public MachineFact addPageRank(Long entityId, String name, double rank, int position) {
        return add(PAGERANK_PREFIX + entityId, "PAGERANK",
                String.format(java.util.Locale.ROOT,
                        "%s (entity %d) has PageRank %.6f, ranking %d in this corpus. "
                                + "PageRank is a structural centrality metric, not a truth or confidence score.",
                        name, entityId, rank, position),
                entityId);
    }

    public MachineFact addCommunity(Long entityId, String name, int communityId, int size) {
        return add(COMMUNITY_PREFIX + communityId + "-" + entityId, "COMMUNITY",
                name + " (entity " + entityId + ") belongs to community " + communityId
                        + ", which contains " + size + " entities.", entityId);
    }

    /**
     * Records an explicit absence. Naming a gap is machine evidence too: the
     * Skeptic must be able to say "the record is silent here" rather than
     * guessing, and only if the system has actually established the silence.
     */
    public MachineFact addGap(String description) {
        return add(GAP_PREFIX + (facts.size() + 1), "GAP", description, null);
    }

    public MachineFact add(String id, String category, String text, Long sourceId) {
        MachineFact fact = new MachineFact(id, category, text, sourceId);
        facts.add(fact);
        return fact;
    }

    // ---- rendering ---------------------------------------------------------

    /**
     * Renders the brief for the prompt.
     *
     * <p>When there are no facts at all, the rendering says so explicitly rather
     * than presenting an empty section the model might fill in.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Machine evidence for the contradiction under review.\n");
        sb.append("Subject: ").append(subjectText.isBlank() ? "(unspecified)" : subjectText).append('\n');

        if (facts.isEmpty()) {
            sb.append("\nNo machine facts are available. The verification record for this "
                    + "contradiction is empty. Do not assert any machine state that is not "
                    + "listed above; state instead that the record is silent.\n");
            return sb.toString();
        }

        Map<String, List<MachineFact>> byCategory = new LinkedHashMap<>();
        for (MachineFact fact : facts) {
            byCategory.computeIfAbsent(fact.category(), k -> new ArrayList<>()).add(fact);
        }

        for (Map.Entry<String, List<MachineFact>> entry : byCategory.entrySet()) {
            sb.append("\n## ").append(entry.getKey()).append('\n');
            for (MachineFact fact : entry.getValue()) {
                sb.append("- [").append(fact.id()).append("] ").append(fact.text()).append('\n');
            }
        }

        sb.append("\nCite machine facts by their bracketed id exactly as written. "
                + "Do not invent a fact id, and do not restate a figure that is not above.\n");
        return sb.toString();
    }

    public int factCount() {
        return facts.size();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
