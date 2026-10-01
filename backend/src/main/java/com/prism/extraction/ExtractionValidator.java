package com.prism.extraction;

import com.prism.extraction.ExtractionDtos.ClaimDto;
import com.prism.extraction.ExtractionDtos.ExtractionResultDto;
import com.prism.extraction.ExtractionDtos.Polarity;
import com.prism.extraction.ExtractionDtos.TripleDto;
import com.prism.knowledge.PredicateDefinition;
import com.prism.knowledge.PredicateSemanticRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic semantic validation of an extraction result, applied after bean
 * validation and before anything is persisted.
 *
 * <p>Catches the failure modes bean validation cannot:
 * <ul>
 *   <li>the quoted source sentence is not actually present in the chunk
 *       (fabricated provenance),</li>
 *   <li>a predicate the corpus registry has not been taught,</li>
 *   <li>more triples or claims than the configured ceiling (a runaway or
 *       hostile response),</li>
 *   <li>exact duplicates inside one response.</li>
 * </ul>
 */
@Component
public class ExtractionValidator {

    private final PredicateSemanticRegistry predicates;
    private final int maxTriples;
    private final int maxClaims;

    public ExtractionValidator(PredicateSemanticRegistry predicates,
                               com.prism.config.PrismTuningProperties tuning) {
        this.predicates = predicates;
        this.maxTriples = tuning.extraction().maxTriplesPerChunk();
        this.maxClaims = tuning.extraction().maxClaimsPerChunk();
    }

    /** Outcome of semantic validation. Never throws; reports every problem found. */
    public record ValidationOutcome(boolean valid, List<String> errors, ExtractionResultDto sanitized) {

        public static ValidationOutcome ok(ExtractionResultDto dto) {
            return new ValidationOutcome(true, List.of(), dto);
        }

        public static ValidationOutcome failed(List<String> errors) {
            return new ValidationOutcome(false, List.copyOf(errors), null);
        }
    }

    public ValidationOutcome validate(ExtractionResultDto result, String chunkText) {
        List<String> errors = new ArrayList<>();

        if (result == null) {
            return ValidationOutcome.failed(List.of("extraction result was null"));
        }
        if (result.triples().size() > maxTriples) {
            errors.add("extraction returned " + result.triples().size() + " triples, limit is " + maxTriples);
        }
        if (result.claims().size() > maxClaims) {
            errors.add("extraction returned " + result.claims().size() + " claims, limit is " + maxClaims);
        }

        // Deduplicate within the response while preserving first-seen order.
        Set<String> seenTriples = new LinkedHashSet<>();
        List<TripleDto> triples = new ArrayList<>();
        for (TripleDto t : result.triples()) {
            if (!sentenceAppearsIn(chunkText, t.sentence())) {
                errors.add("triple source sentence is not present in the chunk: " + snippet(t.sentence()));
                continue;
            }
            PredicateDefinition def = predicates.lookup(t.predicate());
            if (!def.isKnown()) {
                errors.add("unknown predicate '" + t.predicate() + "'; the registry teaches "
                        + predicates.knownPredicateNames());
                continue;
            }
            String key = norm(t.subject()) + '|' + t.predicate() + '|' + norm(t.object());
            if (!seenTriples.add(key)) {
                // A repeated triple inside one chunk is a duplicate, not new evidence.
                continue;
            }
            triples.add(t);
        }

        Set<String> seenClaims = new LinkedHashSet<>();
        List<ClaimDto> claims = new ArrayList<>();
        for (ClaimDto c : result.claims()) {
            if (!sentenceAppearsIn(chunkText, c.sentence())) {
                errors.add("claim source sentence is not present in the chunk: " + snippet(c.claim()));
                continue;
            }
            if (c.polarity() == null) {
                errors.add("claim polarity is missing");
                continue;
            }
            String key = norm(c.subject()) + '|' + norm(c.claim()) + '|' + c.polarity().name();
            if (!seenClaims.add(key)) {
                continue;
            }
            claims.add(c);
        }

        if (!errors.isEmpty()) {
            return ValidationOutcome.failed(errors);
        }
        return ValidationOutcome.ok(new ExtractionResultDto(List.copyOf(triples), List.copyOf(claims)));
    }

    /**
     * Verifies provenance. The model must have quoted real text; accepting a
     * fabricated quote would let it assert facts with convincing but false
     * citations.
     *
     * <p>Comparison is whitespace- and case-insensitive so that a model that
     * reflows whitespace is not punished, but a genuinely different sentence is.
     */
    boolean sentenceAppearsIn(String chunkText, String sentence) {
        if (chunkText == null || sentence == null || sentence.isBlank()) {
            return false;
        }
        String haystack = collapse(chunkText);
        String needle = collapse(sentence);
        if (needle.isEmpty()) {
            return false;
        }
        return haystack.contains(needle);
    }

    static String collapse(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String snippet(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }
}
