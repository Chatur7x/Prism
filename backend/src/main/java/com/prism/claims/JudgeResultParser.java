package com.prism.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.CitationValidator;
import com.prism.extraction.QuarantineReason;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Parses and validates the judge's structured output.
 *
 * <p>Same discipline as extraction: schema, then ranges, then citation
 * resolution. Nothing is trusted because the model produced it in a field named
 * "verdict".
 */
@Component
public class JudgeResultParser {

    private final ObjectMapper objectMapper;

    public JudgeResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param verdictType      the machine's stated verdict, or null when invalid
     * @param confidence       in [0, 1], or null when absent
     * @param reasoning        the judge's concise explanation
     * @param validPassageIds  citations that resolved to supplied evidence
     * @param rejectedCitations citations that did not, with the reason
     * @param valid            false when the response could not be used at all
     * @param error            why it was rejected
     */
    public record JudgeResult(
            VerdictType verdictType,
            Double confidence,
            String reasoning,
            List<Long> validPassageIds,
            List<CitationValidator.Rejection> rejectedCitations,
            boolean valid,
            QuarantineReason reason,
            String error) {

        static JudgeResult invalid(QuarantineReason reason, String error) {
            return new JudgeResult(null, null, null, List.of(), List.of(), false, reason, error);
        }
    }

    /**
     * @param rawText       the model's response
     * @param allowedChunks the chunk ids actually supplied as evidence
     */
    public JudgeResult parse(String rawText, Set<Long> allowedChunks) {
        if (rawText == null || rawText.isBlank()) {
            return JudgeResult.invalid(QuarantineReason.MALFORMED_JSON, "judge returned an empty response");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(rawText));
        } catch (Exception ex) {
            return JudgeResult.invalid(QuarantineReason.MALFORMED_JSON,
                    "judge response was not valid JSON: " + ex.getMessage());
        }
        if (root == null || !root.isObject()) {
            return JudgeResult.invalid(QuarantineReason.MALFORMED_JSON,
                    "judge response was not a JSON object");
        }

        // ---- verdict enum ----
        JsonNode verdictNode = root.path("verdict");
        if (verdictNode.isMissingNode() || verdictNode.isNull() || verdictNode.asText().isBlank()) {
            return JudgeResult.invalid(QuarantineReason.SCHEMA_VALIDATION_FAILED,
                    "judge response has no 'verdict' field");
        }
        VerdictType verdictType;
        try {
            verdictType = VerdictType.valueOf(verdictNode.asText().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return JudgeResult.invalid(QuarantineReason.SCHEMA_VALIDATION_FAILED,
                    "unknown verdict '" + verdictNode.asText() + "'; expected one of "
                            + java.util.Arrays.toString(VerdictType.values()));
        }

        // ---- confidence range ----
        Double confidence = null;
        JsonNode confidenceNode = root.path("confidence");
        if (confidenceNode.isNumber()) {
            double raw = confidenceNode.asDouble();
            if (raw < 0.0 || raw > 1.0) {
                return JudgeResult.invalid(QuarantineReason.SCHEMA_VALIDATION_FAILED,
                        "confidence " + raw + " is outside the valid range [0, 1]");
            }
            confidence = raw;
        } else if (!confidenceNode.isMissingNode() && !confidenceNode.isNull()) {
            return JudgeResult.invalid(QuarantineReason.SCHEMA_VALIDATION_FAILED,
                    "confidence was not a number");
        }

        // ---- reasoning ----
        String reasoning = root.path("reasoning").asText("");
        if (reasoning.length() > 4000) {
            reasoning = reasoning.substring(0, 4000);
        }

        // ---- citations ----
        List<Long> cited = new java.util.ArrayList<>();
        JsonNode passageIds = root.path("passage_ids");
        if (passageIds.isArray()) {
            for (JsonNode id : passageIds) {
                cited.add(id.isNumber() ? id.asLong() : null);
            }
        } else if (passageIds.isTextual()) {
            // Some models return a comma-separated string. Accept the shape but
            // still validate every id.
            for (String part : passageIds.asText().split(",")) {
                try {
                    cited.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    cited.add(null);
                }
            }
        }

        CitationValidator.Result<Long> citations = CitationValidator.validateChunkIds(cited, allowedChunks);

        // A citation the model invented invalidates the response: it proves the
        // model is fabricating references, which makes its verdict untrustworthy
        // even if the verdict enum itself parsed.
        if (citations.hasHallucinatedCitations()) {
            return new JudgeResult(null, confidence, reasoning, citations.valid(),
                    citations.rejected(), false, QuarantineReason.SEMANTIC_VALIDATION_FAILED,
                    "judge cited passages that were not supplied: "
                            + citations.rejected().stream()
                                    .map(CitationValidator.Rejection::id).toList());
        }

        // A verdict that asserts evidence must actually cite some.
        if (verdictType.requiresEvidence() && allowedChunks != null && !allowedChunks.isEmpty()
                && citations.valid().isEmpty()) {
            return JudgeResult.invalid(QuarantineReason.SEMANTIC_VALIDATION_FAILED,
                    "verdict " + verdictType + " requires cited evidence, but no valid passage was cited");
        }

        return new JudgeResult(verdictType, confidence, reasoning, citations.valid(),
                citations.rejected(), true, null, null);
    }

    private static String stripFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
