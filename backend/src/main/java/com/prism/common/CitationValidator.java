package com.prism.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates model-produced citation identifiers against the set that was
 * actually supplied.
 *
 * <p>Pure and deterministic, so it can be exhaustively tested and reused by
 * verification, debate, synthesis, and chat without each inventing its own
 * rule.
 *
 * <p><b>Why this matters more than it looks.</b> A citation is the only thing
 * tying a generated statement to real evidence. If a model may cite id 9999
 * because it felt like it, the entire provenance chain becomes decorative: the
 * UI would render a link to a chunk that does not exist, and a reader would
 * reasonably believe the claim was checked. Rejecting is always safer than
 * silently dropping, because a dropped citation is invisible while a wrong one
 * is actively misleading.
 */
public final class CitationValidator {

    private CitationValidator() {
    }

    /** A citation that was rejected, with the reason. Recorded in the trace. */
    public record Rejection(String kind, String id, String reason) {
    }

    public record Result<T>(List<T> valid, List<Rejection> rejected) {

        public boolean isFullyValid() {
            return rejected.isEmpty();
        }

        public int validCount() {
            return valid.size();
        }

        /** True when the model cited at least one thing that was never supplied. */
        public boolean hasHallucinatedCitations() {
            return !rejected.isEmpty();
        }
    }

    /**
     * Filters chunk citations down to those present in {@code allowed}.
     *
     * <p>Accepts {@code List<?>} because model output is not type-safe: an id
     * may arrive as a number, a numeric string, or something else entirely. All
     * three cases are handled explicitly rather than by a blind cast that would
     * throw a ClassCastException deep in the trace writer.
     *
     * @param allowed the chunk ids actually retrieved and offered to the model
     */
    public static Result<Long> validateChunkIds(List<?> cited, Set<Long> allowed) {
        List<Long> valid = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        if (cited == null) {
            return new Result<>(valid, rejected);
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Object raw : cited) {
            Long id = coerce(raw);
            if (id == null) {
                rejected.add(new Rejection("CHUNK", String.valueOf(raw),
                        "citation id is not a number"));
                continue;
            }
            if (allowed == null || !allowed.contains(id)) {
                rejected.add(new Rejection("CHUNK", String.valueOf(id),
                        "cited chunk " + id + " was not part of the retrieved evidence set"));
                continue;
            }
            if (seen.add(id)) {
                valid.add(id);
            }
        }
        return new Result<>(List.copyOf(valid), List.copyOf(rejected));
    }

    /** Same rule for string-keyed identifiers such as machine fact ids. */
    public static Result<String> validateStringIds(List<?> cited, Set<String> allowed) {
        List<String> valid = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        if (cited == null) {
            return new Result<>(valid, rejected);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object raw : cited) {
            if (raw == null) {
                rejected.add(new Rejection("ID", "null", "citation id is blank"));
                continue;
            }
            String trimmed = raw.toString().trim();
            if (trimmed.isEmpty()) {
                rejected.add(new Rejection("ID", "", "citation id is blank"));
                continue;
            }
            if (allowed == null || !allowed.contains(trimmed)) {
                rejected.add(new Rejection("ID", trimmed,
                        "cited id '" + trimmed + "' was not supplied to the model"));
                continue;
            }
            if (seen.add(trimmed)) {
                valid.add(trimmed);
            }
        }
        return new Result<>(List.copyOf(valid), List.copyOf(rejected));
    }

    private static Long coerce(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(raw.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
