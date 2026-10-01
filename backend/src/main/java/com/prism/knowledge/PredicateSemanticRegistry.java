package com.prism.knowledge;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.prism.knowledge.Cardinality.BOUNDED;
import static com.prism.knowledge.Cardinality.MULTI;
import static com.prism.knowledge.Cardinality.SINGLE;

/**
 * The corpus-wide predicate semantics registry.
 *
 * <p>This is the single place that decides whether two facts about the same
 * relation can coexist. Contradiction detection consults it; nothing else is
 * allowed to hardcode "same subject + same predicate + different object means
 * contradiction", because for most relations that is simply false.
 *
 * <p>Unknown predicates are treated as {@link Cardinality#MULTI} and therefore
 * never produce a relation conflict. The system fails toward "no contradiction"
 * rather than inventing one — a false contradiction sends a real dispute into a
 * debate that no evidence supports.
 */
@Component
public class PredicateSemanticRegistry {

    private final Map<String, PredicateDefinition> byName = new LinkedHashMap<>();

    public PredicateSemanticRegistry() {
        // --- exactly one value: a second value is a genuine conflict ----------
        register(new PredicateDefinition("reports_to", SINGLE, 0, false, true, true, false));
        register(new PredicateDefinition("headquartered_in", SINGLE, 0, false, false, true, true));
        register(new PredicateDefinition("parent_organization", SINGLE, 0, false, true, true, false));
        register(new PredicateDefinition("subsidiary_of", SINGLE, 0, false, true, true, false));
        register(new PredicateDefinition("founded_by", SINGLE, 0, false, false, true, false));
        register(new PredicateDefinition("chief_executive", SINGLE, 0, false, false, true, true));
        register(new PredicateDefinition("located_in", SINGLE, 0, false, true, true, true));
        register(new PredicateDefinition("part_of", SINGLE, 0, false, true, true, true));

        // --- many values: a second value is normal, not a conflict --------------
        register(new PredicateDefinition("controls", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("funds", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("owns", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("allied_with", MULTI, 0, true, false, false, true));
        register(new PredicateDefinition("supplies", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("works_for", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("operates_in", MULTI, 0, false, false, false, false));
        register(new PredicateDefinition("collaborates_with", MULTI, 0, true, false, false, true));
        register(new PredicateDefinition("acquires", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("competes_with", MULTI, 0, true, false, false, true));
        register(new PredicateDefinition("member_of", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("exports_to", MULTI, 0, false, false, false, false));
        register(new PredicateDefinition("licenses_to", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("invests_in", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("advises", MULTI, 0, false, false, false, true));
        register(new PredicateDefinition("succeeded_by", SINGLE, 0, false, true, true, true));
        register(new PredicateDefinition("preceded_by", SINGLE, 0, false, true, true, true));

        // --- a small, bounded number of values --------------------------------
        register(new PredicateDefinition("sources_from", BOUNDED, 3, false, false, false, true));
        register(new PredicateDefinition("operated_by", BOUNDED, 2, false, false, false, true));
        register(new PredicateDefinition("regulated_by", BOUNDED, 4, false, false, false, true));
    }

    private void register(PredicateDefinition definition) {
        byName.put(definition.name(), definition);
    }

    /** Unknown predicates resolve to a permissive MULTI definition, never to a conflict. */
    public PredicateDefinition lookup(String name) {
        if (name == null) {
            return PredicateDefinition.unknown();
        }
        return byName.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), PredicateDefinition.unknown());
    }

    public boolean isKnown(String name) {
        return name != null && byName.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }

    public Set<String> knownPredicateNames() {
        return Set.copyOf(byName.keySet());
    }

    public List<PredicateDefinition> all() {
        return List.copyOf(byName.values());
    }
}
