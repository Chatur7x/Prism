package com.prism.contradiction;

import com.prism.claims.ClaimPolarity;
import com.prism.knowledge.PredicateDefinition;
import com.prism.knowledge.PredicateSemanticRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic contradiction detection.
 *
 * <p>Pure function of its inputs. No model, no I/O, no clock. The same approved
 * knowledge always produces the same findings in the same order, which is what
 * lets a verifier audit them and lets the Skeptic quote them.
 *
 * <p><b>Why predicate semantics matter.</b> The naive rule
 * "same subject + same predicate + different object means contradiction" is
 * wrong for most relations. "Meridian funds Aster Labs" and "Meridian funds
 * Orion Systems" are perfectly compatible. Flagging that as a contradiction
 * would manufacture disputes no evidence supports and flood the debate queue.
 * A conflict is asserted only when {@link PredicateDefinition} says the
 * relation can hold for at most one object at a time.
 *
 * <p>Three detectors run, in this order:
 * <ol>
 *   <li>{@link #detectRelationConflicts} — same subject and predicate, different
 *       object, on a single-valued predicate.</li>
 *   <li>{@link #detectPolarityConflicts} — same subject and predicate, opposite
 *       polarity, on a mutually exclusive relation.</li>
 *   <li>{@link #detectVerdictConflicts} — an approved triple directly refuted by
 *       a SUPPORTED claim that denies it.</li>
 * </ol>
 */
public class ContradictionDetector {

    public static final String RULE_VERSION = "CONTRA_V1";
    public static final String RULE_RELATION_SINGLE_VALUE = "RELATION_SINGLE_VALUE";
    public static final String RULE_RELATION_BOUNDED_EXCEEDED = "RELATION_BOUNDED_EXCEEDED";
    public static final String RULE_POLARITY_OPPOSITE = "POLARITY_OPPOSITE";
    public static final String RULE_VERDICT_REFUTES_TRIPLE = "VERDICT_REFUTES_TRIPLE";

    private final PredicateSemanticRegistry predicates;

    public ContradictionDetector(PredicateSemanticRegistry predicates) {
        this.predicates = predicates;
    }

    /** An approved triple, as the detector needs to see it. */
    public record ApprovedTriple(
            Long id,
            String subjectText,
            String predicate,
            String objectText,
            String subjectKey,
            String effectiveTime,
            String sourceDescription) {
    }

    /** An approved claim, as the detector needs to see it. */
    public record ApprovedClaim(
            Long id,
            String subjectText,
            String subjectKey,
            String predicate,
            String objectText,
            ClaimPolarity polarity,
            String claimText,
            String effectiveTime,
            String sourceDescription) {
    }

    /** A machine verdict that can refute an approved triple. */
    public record VerdictRecord(
            Long claimId,
            String subjectText,
            String predicate,
            String objectText,
            ClaimPolarity polarity,
            boolean isContradicted,
            String verdictReason) {
    }

    // ---------------------------------------------------------------------
    // 1. Relation conflicts
    // ---------------------------------------------------------------------

    /**
     * Finds approved triples that cannot both hold.
     *
     * <p>Only fires for predicates whose definition declares a single value.
     * Multi-valued and bounded predicates are skipped unless the bound is
     * actually exceeded.
     */
    public List<ContradictionFinding> detectRelationConflicts(List<ApprovedTriple> triples) {
        List<ContradictionFinding> findings = new ArrayList<>();
        if (triples == null || triples.size() < 2) {
            return findings;
        }

        // Group by (subject, predicate): the pair that could conflict.
        record GroupKey(String subjectKey, String predicate) {
        }
        java.util.Map<GroupKey, List<ApprovedTriple>> groups = new java.util.LinkedHashMap<>();
        for (ApprovedTriple t : triples) {
            if (t.predicate() == null || t.subjectKey() == null) {
                continue;
            }
            groups.computeIfAbsent(new GroupKey(t.subjectKey(), t.predicate().toLowerCase(Locale.ROOT)),
                    k -> new ArrayList<>()).add(t);
        }

        for (var entry : groups.entrySet()) {
            List<ApprovedTriple> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            PredicateDefinition def = predicates.lookup(entry.getKey().predicate());

            if (def.canHoldOneValue()) {
                // Single-valued: two different objects conflict. A temporal
                // predicate is exempt when the effective times differ, which
                // emitRelationFindings checks per pair.
                emitRelationFindings(findings, group, def, RULE_RELATION_SINGLE_VALUE);
            } else if (def.cardinality() == com.prism.knowledge.Cardinality.BOUNDED
                    && group.size() > def.boundedMax()) {
                // Pairwise emission keeps every conflicting record identifiable
                // rather than reporting one opaque group-level conflict.
                emitRelationFindings(findings, group, def, RULE_RELATION_BOUNDED_EXCEEDED);
            }
            // MULTI and unknown predicates: multiple values are legitimate. No finding.
        }

        return findings;
    }

    private void emitRelationFindings(List<ContradictionFinding> findings, List<ApprovedTriple> group,
                                       PredicateDefinition def, String ruleCode) {
        // Deterministic order: by object text, then by id.
        List<ApprovedTriple> ordered = new ArrayList<>(group);
        ordered.sort((a, b) -> {
            int cmp = String.valueOf(a.objectText()).compareTo(String.valueOf(b.objectText()));
            return cmp != 0 ? cmp : Long.compare(nz(a.id()), nz(b.id()));
        });

        for (int i = 0; i < ordered.size() - 1; i++) {
            ApprovedTriple left = ordered.get(i);
            ApprovedTriple right = ordered.get(i + 1);
            if (Objects.equals(normObj(left.objectText()), normObj(right.objectText()))) {
                // Same object: agreement, not conflict.
                continue;
            }
            // A temporal predicate with different effective times describes a
            // change over time rather than a simultaneous contradiction.
            if (def.temporal() && bothTimesPresentAndDiffer(left.effectiveTime(), right.effectiveTime())) {
                continue;
            }

            findings.add(new ContradictionFinding(
                    ContradictionFinding.TYPE_RELATION,
                    left.subjectText(),
                    left.predicate(),
                    describe(left),
                    describe(right),
                    ruleCode,
                    RULE_VERSION,
                    "Predicate '" + left.predicate() + "' is declared single-valued (cardinality="
                            + def.cardinality() + ", mutuallyExclusive=" + def.mutuallyExclusive()
                            + "), but the approved knowledge asserts two different objects: '"
                            + left.objectText() + "' and '" + right.objectText() + "'. "
                            + (def.temporal()
                            ? "The effective times do not differ, so this is a simultaneous conflict."
                            : "The predicate is not temporal, so a change over time cannot explain it."),
                    left.id(),
                    right.id(),
                    null,
                    null,
                    ContradictionFinding.buildHash(ContradictionFinding.TYPE_RELATION,
                            left.subjectText(), left.predicate(), left.id(), right.id())));
        }
    }

    private static boolean bothTimesPresentAndDiffer(String a, String b) {
        return a != null && !a.isBlank() && b != null && !b.isBlank() && !a.equalsIgnoreCase(b);
    }

    // ---------------------------------------------------------------------
    // 2. Polarity conflicts
    // ---------------------------------------------------------------------

    /**
     * Finds claims that assert and deny the same relation.
     *
     * <p>Grouping is by (subject, predicate, <b>object</b>). Because the object
     * is part of the key, a match means the two claims are about the very same
     * fact, so opposite polarity is always contradictory — even when the
     * predicate is multi-valued. "Aster Labs acquired Orion Systems" and
     * "Aster Labs did not acquire Orion Systems" cannot both hold regardless of
     * how many other targets {@code acquires} may have.
     *
     * <p>A predicate is still required: without one there is no reliable way to
     * decide that two differently-worded sentences concern the same fact, so
     * nothing is asserted.
     */
    public List<ContradictionFinding> detectPolarityConflicts(List<ApprovedClaim> claims) {
        List<ContradictionFinding> findings = new ArrayList<>();
        if (claims == null || claims.size() < 2) {
            return findings;
        }

        record GroupKey(String subjectKey, String predicate, String objectKey) {
        }
        java.util.Map<GroupKey, List<ApprovedClaim>> groups = new java.util.LinkedHashMap<>();
        for (ApprovedClaim c : claims) {
            if (c.predicate() == null || c.subjectKey() == null || c.polarity() == null) {
                continue;
            }
            groups.computeIfAbsent(
                    new GroupKey(c.subjectKey(), c.predicate().toLowerCase(Locale.ROOT), normObj(c.objectText())),
                    k -> new ArrayList<>()).add(c);
        }

        for (var entry : groups.entrySet()) {
            List<ApprovedClaim> group = entry.getValue();
            boolean hasPositive = group.stream().anyMatch(c -> c.polarity() == ClaimPolarity.POSITIVE);
            boolean hasNegative = group.stream().anyMatch(c -> c.polarity() == ClaimPolarity.NEGATIVE);
            if (!hasPositive || !hasNegative) {
                continue;
            }
            ApprovedClaim positive = group.stream()
                    .filter(c -> c.polarity() == ClaimPolarity.POSITIVE).min((a, b) -> Long.compare(nz(a.id()), nz(b.id())))
                    .orElseThrow();
            ApprovedClaim negative = group.stream()
                    .filter(c -> c.polarity() == ClaimPolarity.NEGATIVE).min((a, b) -> Long.compare(nz(a.id()), nz(b.id())))
                    .orElseThrow();

            findings.add(new ContradictionFinding(
                    ContradictionFinding.TYPE_POLARITY,
                    positive.subjectText(),
                    positive.predicate(),
                    "POSITIVE: " + positive.claimText() + " (" + positive.sourceDescription() + ")",
                    "NEGATIVE: " + negative.claimText() + " (" + negative.sourceDescription() + ")",
                    RULE_POLARITY_OPPOSITE,
                    RULE_VERSION,
                    "Two approved claims about '" + positive.subjectText() + "' assert and deny the same "
                            + "relation '" + positive.predicate() + "' for object '"
                            + positive.objectText() + "'. Polarity is part of the proposition, so both "
                            + "cannot be true.",
                    null, null, positive.id(), negative.id(),
                    ContradictionFinding.buildHash(ContradictionFinding.TYPE_POLARITY,
                            positive.subjectText(), positive.predicate(), positive.id(), negative.id())));
        }

        return findings;
    }

    // ---------------------------------------------------------------------
    // 3. Verdict conflicts
    // ---------------------------------------------------------------------

    /**
     * Finds approved triples that a CONTRADICTED machine verdict directly denies.
     *
     * <p>This requires human review rather than an automatic contradiction
     * record: the verdict is itself a machine output, so the finding is framed as
     * a conflict for adjudication, not as settled fact.
     */
    public List<ContradictionFinding> detectVerdictConflicts(List<ApprovedTriple> triples,
                                                            List<VerdictRecord> verdicts) {
        List<ContradictionFinding> findings = new ArrayList<>();
        if (triples == null || verdicts == null || triples.isEmpty() || verdicts.isEmpty()) {
            return findings;
        }
        Set<String> emitted = new HashSet<>();

        for (ApprovedTriple triple : triples) {
            for (VerdictRecord verdict : verdicts) {
                if (!verdict.isContradicted()) {
                    continue;
                }
                if (!Objects.equals(norm(triple.subjectText()), norm(verdict.subjectText()))) {
                    continue;
                }
                if (triple.predicate() == null || verdict.predicate() == null
                        || !norm(triple.predicate()).equals(norm(verdict.predicate()))) {
                    continue;
                }
                if (!Objects.equals(normObj(triple.objectText()), normObj(verdict.objectText()))) {
                    continue;
                }
                String hash = ContradictionFinding.buildHash(ContradictionFinding.TYPE_VERDICT,
                        triple.subjectText(), triple.predicate(), triple.id(), verdict.claimId());
                if (!emitted.add(hash)) {
                    continue;
                }
                findings.add(new ContradictionFinding(
                        ContradictionFinding.TYPE_VERDICT,
                        triple.subjectText(),
                        triple.predicate(),
                        "Approved triple: " + triple.subjectText() + " " + triple.predicate()
                                + " " + triple.objectText() + " (" + triple.sourceDescription() + ")",
                        "Verification of claim " + verdict.claimId() + " returned CONTRADICTED: "
                                + truncate(verdict.verdictReason()),
                        RULE_VERDICT_REFUTES_TRIPLE,
                        RULE_VERSION,
                        "An approved triple asserts this relation, but the machine verdict on the "
                                + "corresponding claim was CONTRADICTED. This is a conflict between "
                                + "approved knowledge and the verification record, and it requires "
                                + "human adjudication rather than automatic resolution.",
                        triple.id(), null, verdict.claimId(), null,
                        hash));
            }
        }
        return findings;
    }

    /** Runs every detector, in a fixed order, and returns the combined findings. */
    public List<ContradictionFinding> detectAll(List<ApprovedTriple> triples,
                                               List<ApprovedClaim> claims,
                                               List<VerdictRecord> verdicts) {
        List<ContradictionFinding> all = new ArrayList<>();
        all.addAll(detectRelationConflicts(triples));
        all.addAll(detectPolarityConflicts(claims));
        all.addAll(detectVerdictConflicts(triples, verdicts));
        return all;
    }

    private static String describe(ApprovedTriple t) {
        return t.subjectText() + " " + t.predicate() + " " + t.objectText()
                + " (" + t.sourceDescription() + ")";
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normObj(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "no reason recorded";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
