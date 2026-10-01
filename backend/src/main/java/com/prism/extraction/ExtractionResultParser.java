package com.prism.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.extraction.ExtractionDtos.ExtractionResultDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The strict gate every model response passes before it can influence state.
 *
 * <p>Pipeline: raw text → JSON extraction → Jackson parse → bean validation →
 * semantic validation. A failure at any stage is reported, never silently
 * repaired. The only automatic repair permitted is a single JSON-shape fix
 * (stripping markdown fences), because that is a formatting artefact rather
 * than a change of meaning.
 */
@Component
public class ExtractionResultParser {

    private static final Logger log = LoggerFactory.getLogger(ExtractionResultParser.class);

    /**
     * Keys the extraction schema permits. An unexpected top-level key means the
     * model did not follow the contract; accepting it would let a model smuggle
     * instructions or extra payload into a trusted path.
     */
    private static final Set<String> ALLOWED_ROOT_KEYS = Set.of("triples", "claims");

    /** Strips a ```json ... ``` fence if the model wrapped its output in one. */
    private static final Pattern FENCE = Pattern.compile("(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ExtractionValidator semanticValidator;

    public ExtractionResultParser(ObjectMapper objectMapper, Validator validator,
                                  ExtractionValidator semanticValidator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.semanticValidator = semanticValidator;
    }

    /** Outcome of parsing. Exactly one of {@code result} / {@code reason} is set. */
    public record ParseOutcome(
            boolean success,
            ExtractionResultDto result,
            QuarantineReason reason,
            String message,
            int tripleCount,
            int claimCount) {

        static ParseOutcome ok(ExtractionResultDto dto) {
            return new ParseOutcome(true, dto, null, null,
                    dto.triples().size(), dto.claims().size());
        }

        static ParseOutcome fail(QuarantineReason reason, String message) {
            return new ParseOutcome(false, null, reason, message, 0, 0);
        }
    }

    /**
     * Runs the full validation chain.
     *
     * @param rawText    verbatim provider output
     * @param chunkText  the source the model was shown, for provenance checking
     */
    public ParseOutcome parse(String rawText, String chunkText) {
        if (rawText == null || rawText.isBlank()) {
            return ParseOutcome.fail(QuarantineReason.MALFORMED_JSON, "model returned an empty response");
        }

        String candidate = extractJsonObject(rawText);
        if (candidate == null) {
            return ParseOutcome.fail(QuarantineReason.MALFORMED_JSON,
                    "no JSON object found in the response body");
        }

        ExtractionResultDto dto;
        try {
            dto = objectMapper.readValue(candidate, ExtractionResultDto.class);
        } catch (JsonProcessingException ex) {
            return ParseOutcome.fail(QuarantineReason.MALFORMED_JSON,
                    "JSON parse error: " + ex.getOriginalMessage());
        }

        Set<String> unexpected = findUnexpectedRootKeys(candidate);
        if (!unexpected.isEmpty()) {
            return ParseOutcome.fail(QuarantineReason.SCHEMA_VALIDATION_FAILED,
                    "response contains keys outside the schema: " + unexpected);
        }

        List<String> beanErrors = validateBean(dto);
        if (!beanErrors.isEmpty()) {
            return ParseOutcome.fail(QuarantineReason.SCHEMA_VALIDATION_FAILED,
                    "schema validation failed: " + String.join("; ", beanErrors));
        }

        ExtractionValidator.ValidationOutcome semantic = semanticValidator.validate(dto, chunkText);
        if (!semantic.valid()) {
            QuarantineReason reason = semantic.errors().stream()
                    .anyMatch(e -> e.contains("limit is"))
                    ? QuarantineReason.OBJECT_LIMIT_EXCEEDED
                    : QuarantineReason.SEMANTIC_VALIDATION_FAILED;
            return ParseOutcome.fail(reason, String.join("; ", semantic.errors()));
        }

        return ParseOutcome.ok(semantic.sanitized());
    }

    private List<String> validateBean(ExtractionResultDto dto) {
        var violations = validator.validate(dto);
        return violations.stream()
                .map(this::describe)
                .sorted()
                .collect(Collectors.toList());
    }

    private String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    /**
     * Isolates the outermost JSON object. Tolerates a markdown fence and leading
     * prose, which small models emit frequently.
     *
     * @return the candidate JSON text, or null if none is present
     */
    String extractJsonObject(String raw) {
        String text = raw.trim();
        var fenceMatcher = FENCE.matcher(text);
        if (fenceMatcher.matches()) {
            text = fenceMatcher.group(1).trim();
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        // Walk forward tracking brace depth, respecting string literals so a
        // brace inside a quoted value does not end the object early.
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private Set<String> findUnexpectedRootKeys(String candidateJson) {
        try {
            JsonNode root = objectMapper.readTree(candidateJson);
            Set<String> unexpected = new java.util.TreeSet<>();
            root.fieldNames().forEachRemaining(name -> {
                if (!ALLOWED_ROOT_KEYS.contains(name)) {
                    unexpected.add(name);
                }
            });
            return unexpected;
        } catch (JsonProcessingException ex) {
            return Set.of();
        }
    }
}
