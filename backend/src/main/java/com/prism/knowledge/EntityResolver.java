package com.prism.knowledge;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic entity resolution, v1.
 *
 * <p>Pure decision logic: given a surface form, existing entities, and existing
 * aliases, decide what to do. Persistence is the caller's job, which keeps this
 * class unit-testable without a database.
 *
 * <p><b>The rule is deliberately conservative.</b> A surface form is merged
 * into an existing entity only when its conservative key matches that entity's
 * key exactly, or when it matches a recorded alias exactly. Anything ambiguous
 * is flagged for review rather than merged.
 *
 * <p>Why not be cleverer: a wrong merge silently rewrites real graph knowledge.
 * It fuses two entities, corrupts their neighbourhoods, distorts PageRank, and
 * makes the mistake invisible afterwards because nothing records that it
 * happened. A missed merge is recoverable — a human can merge two nodes. An
 * incorrect merge is not.
 */
public class EntityResolver {

    public enum Action {
        /** Attach to the existing entity. */
        ATTACH,
        /** Create a new entity. */
        CREATE,
        /** Two candidates are equally plausible; a human must decide. */
        NEEDS_REVIEW
    }

    public record Decision(
            Action action,
            Long targetEntityId,
            String reason,
            ResolutionState state,
            String suggestedAlias) {
    }

    /** An existing entity, as the resolver needs to see it. */
    public record KnownEntity(Long id, String displayName, String normalizedName, ResolutionState state) {
    }

    /** An existing alias, as the resolver needs to see it. */
    public record KnownAlias(String normalizedAlias, Long entityId) {
    }

    /**
     * Decides how to resolve {@code surfaceForm}.
     *
     * <p>Resolution order, and why:
     * <ol>
     *   <li><b>Exact key match</b> on an entity's own normalized name. The
     *       strongest signal available.</li>
     *   <li><b>Exact alias match</b>. Aliases are human-reviewed or
     *       single-observation facts, so they are trusted as recorded.</li>
     *   <li><b>CREATE</b> only when the key is genuinely new.</li>
     *   <li><b>NEEDS_REVIEW</b> when the key is empty or the surface form is
     *       too short to be a safe identity.</li>
     * </ol>
     *
     * <p>Note what is deliberately absent: no fuzzy matching. Fuzzy matching is
     * where silent merge bugs live.
     */
    public Decision resolve(String surfaceForm, List<KnownEntity> entities, List<KnownAlias> aliases) {
        if (surfaceForm == null || surfaceForm.isBlank()) {
            return new Decision(Action.NEEDS_REVIEW, null,
                    "surface form is blank", ResolutionState.REVIEW, null);
        }
        String key = EntityNormalizer.normalizeKey(surfaceForm);
        if (key.isEmpty()) {
            return new Decision(Action.NEEDS_REVIEW, null,
                    "surface form normalises to an empty key", ResolutionState.REVIEW, null);
        }
        if (key.length() < 2) {
            // A one-character key would collapse many distinct entities.
            return new Decision(Action.NEEDS_REVIEW, null,
                    "surface form '" + surfaceForm + "' is too short to be a safe identity key",
                    ResolutionState.REVIEW, null);
        }

        // 1. Exact key match against a live entity.
        for (KnownEntity entity : safe(entities)) {
            if (entity.state() == ResolutionState.MERGE) {
                // A merged-away entity must not absorb new references; a human
                // must repoint them to the surviving entity.
                continue;
            }
            if (key.equals(entity.normalizedName())) {
                return new Decision(Action.ATTACH, entity.id(),
                        "exact normalized key match with entity " + entity.id(),
                        entity.state(), null);
            }
        }

        // 2. Exact alias match.
        for (KnownAlias alias : safe(aliases)) {
            if (key.equals(alias.normalizedAlias())) {
                return new Decision(Action.ATTACH, alias.entityId(),
                        "exact alias match for '" + key + "'", ResolutionState.KEEP_SEPARATE, key);
            }
        }

        // 3. Nothing matched: a new identity.
        return new Decision(Action.CREATE, null,
                "no existing entity or alias matches key '" + key + "'",
                ResolutionState.KEEP_SEPARATE, null);
    }

    /**
     * Whether two surface forms are safe to merge automatically.
     *
     * <p>Returns false when the keys differ, and also when they differ only by a
     * designator ("Aster Labs" vs "Aster") — the resolver is right to treat
     * those as the same only when the database already records the link, and
     * this method exists to make the reviewer aware of that judgement.
     */
    public static boolean autoMergeable(String a, String b) {
        String ka = EntityNormalizer.normalizeKey(a);
        String kb = EntityNormalizer.normalizeKey(b);
        return !ka.isEmpty() && ka.equals(kb);
    }

    /**
     * Human-readable diff between two surface forms, for the review queue.
     * Returns null when the forms are identical.
     */
    public static String describeDifference(String a, String b) {
        String ka = EntityNormalizer.normalizeKey(a);
        String kb = EntityNormalizer.normalizeKey(b);
        if (ka.equals(kb)) {
            return null;
        }
        Set<String> tokensA = Set.of(ka.split(" "));
        Set<String> tokensB = Set.of(kb.split(" "));
        Set<String> onlyA = new java.util.TreeSet<>(tokensA);
        onlyA.removeAll(tokensB);
        Set<String> onlyB = new java.util.TreeSet<>(tokensB);
        onlyB.removeAll(tokensA);
        StringBuilder sb = new StringBuilder();
        if (!onlyA.isEmpty()) {
            sb.append("only in first: ").append(String.join(", ", onlyA)).append(' ');
        }
        if (!onlyB.isEmpty()) {
            sb.append("only in second: ").append(String.join(", ", onlyB)).append(' ');
        }
        if (sb.length() == 0) {
            return "same tokens, different order or punctuation";
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
