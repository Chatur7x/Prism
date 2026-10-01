package com.prism.knowledge;

/**
 * How many objects a predicate may legitimately have at once.
 *
 * <p>This is what stops the contradiction detector from treating ordinary
 * multi-valued relations as conflicts. {@code reports_to} is many-to-one, so a
 * second value is a conflict. {@code allied_with} is many-to-many, so it never is.
 */
public enum Cardinality {
    /** Exactly one object at a time. A second value is a contradiction. */
    SINGLE,
    /** At most a small number. Conflict only above the allowed count. */
    BOUNDED,
    /** Any number of objects. Multiple values are never contradictory. */
    MULTI
}
