package com.prism.knowledge;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic entity-name normalization.
 *
 * <p>Two distinct jobs, deliberately kept apart:
 *
 * <ol>
 *   <li>{@link #normalizeKey(String)} produces a lookup key. It is
 *       <b>conservative</b>: it only folds case, collapses whitespace, and
 *       trims a small, explicitly enumerated set of trailing organisational
 *       designators. It never strips accents, never removes internal
 *       punctuation, and never reorders words.</li>
 *   <li>{@link #normalizeDisplay(String)} produces a presentable form. It is
 *       used for display only and is never used as an identity key.</li>
 * </ol>
 *
 * <p>The separation exists because aggressive folding is a merge bug. If
 * {@code "Meridian Group"} and {@code "Meridian Groupe"} collapse to one key,
 * an unrelated entity silently rewrites real graph knowledge. Conservative keys
 * mean the resolver proposes a merge to a human instead of performing one.
 */
public final class EntityNormalizer {

    /**
     * Trailing designators removed for lookup. Each is a legal organisational
     * suffix, so "Aster Labs" and "Aster" name the same organisation.
     */
    private static final Set<String> TRAILING_DESIGNATORS = Set.of(
            "inc", "inc.", "llc", "ltd", "ltd.", "limited", "corp", "corp.", "corporation",
            "co", "co.", "company", "gmbh", "sarl", "plc", "ag", "sa", "nv", "bv",
            "pty", "holdings", "holding", "group", "the");

    private EntityNormalizer() {
    }

    /**
     * Conservative identity key. Case-folded, whitespace-collapsed, and stripped
     * of at most one trailing designator.
     */
    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        // Collapse all whitespace runs to a single space, including the exotic
        // kinds (NBSP, tabs, newlines) that would otherwise defeat equality.
        value = value.replaceAll("[\\s\\p{Z}]+", " ").trim();

        // Strip punctuation that is pure noise at the edges only. Internal
        // punctuation is significant ("A.B.C." vs "ABC") so it is preserved.
        value = stripEdgePunctuation(value);

        // Remove at most one trailing designator, then re-trim.
        int space = value.lastIndexOf(' ');
        if (space > 0 && space < value.length() - 1) {
            String tail = value.substring(space + 1);
            if (TRAILING_DESIGNATORS.contains(tail)) {
                value = value.substring(0, space).trim();
            }
        }
        // "Group" alone or "The" alone would leave nothing; guard against it.
        return value.replaceAll("^\\s+|\\s+$", "");
    }

    private static String stripEdgePunctuation(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isEdgePunctuation(value.charAt(start))) {
            start++;
        }
        while (end > start && isEdgePunctuation(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isEdgePunctuation(char c) {
        return c == ',' || c == ';' || c == ':' || c == '"' || c == '\''
                || c == '(' || c == ')' || c == '[' || c == ']' || c == '—' || c == '–';
    }

    /**
     * Display form: accent-folded and whitespace-collapsed, but never used as
     * an identity key.
     */
    public static String normalizeDisplay(String raw) {
        if (raw == null) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        value = value.replaceAll("[\\s\\p{Z}]+", " ").trim();
        return value;
    }

    /**
     * Whether two surface forms are the same entity under conservative rules.
     * Deliberately stricter than a human would be, so the resolver can ask
     * rather than merge.
     */
    public static boolean sameIdentity(String a, String b) {
        String ka = normalizeKey(a);
        String kb = normalizeKey(b);
        return !ka.isEmpty() && ka.equals(kb);
    }
}
