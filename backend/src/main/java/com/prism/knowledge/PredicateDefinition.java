package com.prism.knowledge;

/**
 * Deterministic semantics for one predicate.
 *
 * @param name              snake_case relation key.
 * @param cardinality       how many objects may coexist.
 * @param boundedMax        upper limit when cardinality is BOUNDED.
 * @param symmetric         true when the relation reads the same in both
 *                          directions, e.g. allied_with.
 * @param transitive        true when A→B and B→C implies A→C.
 * @param mutuallyExclusive true when two different objects for the same subject
 *                          cannot both be true.
 * @param temporal          true when the relation can hold at different times,
 *                          which means two values may be sequential rather than
 *                          contradictory. Without this, "was based in A, now
 *                          based in B" would be flagged as a conflict.
 */
public record PredicateDefinition(
        String name,
        Cardinality cardinality,
        int boundedMax,
        boolean symmetric,
        boolean transitive,
        boolean mutuallyExclusive,
        boolean temporal) {

    public PredicateDefinition {
        if (cardinality == Cardinality.BOUNDED && boundedMax < 2) {
            throw new IllegalArgumentException("BOUNDED predicate '" + name + "' needs boundedMax >= 2");
        }
    }

    public boolean isKnown() {
        return !UNKNOWN.equals(name);
    }

    static final String UNKNOWN = "__unknown__";

    public static PredicateDefinition unknown() {
        return new PredicateDefinition(UNKNOWN, Cardinality.MULTI, 0, false, false, false, false);
    }

    /**
     * Whether this predicate can legitimately hold only one object at a time.
     *
     * <p>Temporal predicates such as {@code located_in} also satisfy this: they
     * hold one value per instant. Whether two asserted values actually conflict
     * then depends on their effective times, which only the caller can decide.
     */
    public boolean canHoldOneValue() {
        return cardinality == Cardinality.SINGLE && mutuallyExclusive;
    }

    /**
     * Whether two different objects for the same subject constitute a conflict
     * purely on relation semantics, ignoring any effective-time distinction.
     *
     * <p>False for temporal predicates, because a change over time is a
     * legitimate reason to see two values.
     */
    public boolean conflictsOnDifferentObjects() {
        return canHoldOneValue() && !temporal;
    }
}
