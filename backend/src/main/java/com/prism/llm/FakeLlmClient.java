package com.prism.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.LlmProperties;
import com.prism.document.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic offline provider for tests and demo fallback.
 *
 * <p><b>This is not a production code path.</b> It is only active when
 * {@code LLM_PROVIDER=fake}, which must be set explicitly. There is no
 * "auto-detect and degrade" behaviour: a real deployment that loses its provider
 * fails loudly with a 503 rather than silently answering from canned strings,
 * which would be indistinguishable from a working system and far more dangerous.
 *
 * <p>Output is a pure function of the prompt, so an integration test asserting
 * on a verdict, debate, or chat result is reproducible run to run.
 */
@Component
@ConditionalOnProperty(name = "prism.llm.provider", havingValue = "fake")
public class FakeLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FakeLlmClient.class);
    private static final AtomicInteger CALL_COUNTER = new AtomicInteger();

    private final ObjectMapper objectMapper;

    public FakeLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "fake";
    }

    @Override
    public Map<String, String> describe() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("provider", "fake");
        info.put("warning", "OFFLINE TEST MODE: responses are deterministic fixtures, not a model");
        return info;
    }

    @Override
    public LlmCompletion complete(String system, String user, LlmProperties.Purpose purpose,
                                  Double temperatureOverride) {
        long start = System.nanoTime();
        int call = CALL_COUNTER.incrementAndGet();
        String response = switch (purpose) {
            case EXTRACT -> fakeExtraction(user);
            case JUDGE -> fakeJudge(user);
            case DEBATE -> fakeDebate(user, system);
            case SYNTHESIS -> fakeSynthesis(user);
            case CHAT -> fakeChat(user);
        };
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        log.debug("FakeLlmClient call #{} purpose={} -> {} chars", call, purpose, response.length());
        return new LlmCompletion(response, "fake-model", elapsed, 1, false);
    }

    // ---- fixtures ----------------------------------------------------------

    /**
     * Deterministic extraction.
     *
     * <p>Finds sentences in the supplied DATA block that match a
     * "Subject predicate Object." shape, then re-quotes each one verbatim. The
     * quotation is real, so the downstream provenance check in
     * {@code ExtractionValidator} genuinely passes and the offline demo
     * exercises the same code path a real model would.
     */
    private String fakeExtraction(String user) {
        String data = extractDataBlock(user);
        if (data.isBlank()) {
            return "{\"triples\":[],\"claims\":[]}";
        }
        StringBuilder triples = new StringBuilder();
        StringBuilder claims = new StringBuilder();
        int tripleCount = 0;
        int claimCount = 0;

        for (String sentence : splitSentences(data)) {
            if (tripleCount >= 8) {
                break;
            }
            String[] parts = sentence.split("\\s+");
            // A minimal, honest heuristic: a capitalised subject, a known
            // predicate token, and an object, in that order.
            for (int i = 1; i < parts.length - 1 && tripleCount < 8; i++) {
                String predicate = parts[i].toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z_]", "");
                if (!KNOWN_PREDICATES.contains(predicate)) {
                    continue;
                }
                String subject = parts[0];
                StringBuilder object = new StringBuilder();
                for (int j = i + 1; j < parts.length; j++) {
                    if (j > i + 1) {
                        object.append(' ');
                    }
                    object.append(parts[j]);
                }
                String objectText = object.toString().strip();
                // Trim a trailing period and any clause after it.
                int period = objectText.indexOf('.');
                if (period > 0) {
                    objectText = objectText.substring(0, period);
                }
                if (objectText.isBlank()) {
                    break;
                }
                if (tripleCount > 0) {
                    triples.append(',');
                }
                triples.append("{\"subject\":").append(quote(subject))
                        .append(",\"predicate\":").append(quote(predicate))
                        .append(",\"object\":").append(quote(objectText))
                        .append(",\"sentence\":").append(quote(sentence)).append('}');
                tripleCount++;
                break;
            }
            if (claimCount < 8 && sentence.length() > 25) {
                if (claimCount > 0) {
                    claims.append(',');
                }
                String subject = parts.length > 0 ? parts[0] : "Unknown";
                claims.append("{\"subject\":").append(quote(subject))
                        .append(",\"claim\":").append(quote(sentence.strip()))
                        .append(",\"polarity\":\"NEUTRAL\"")
                        .append(",\"sentence\":").append(quote(sentence)).append('}');
                claimCount++;
            }
        }
        return "{\"triples\":[" + triples + "],\"claims\":[" + claims + "]}";
    }

    /**
     * Predicates this offline provider will emit.
     *
     * <p>Kept in step with {@link com.prism.knowledge.PredicateSemanticRegistry}.
     * The two lists drifting apart is not a cosmetic problem: a predicate the
     * registry knows but this list omits is silently never extracted, so a fact
     * that exists in the corpus never becomes a triple, and any contradiction it
     * would have revealed is unfindable. That is precisely how {@code
     * chief_executive} came to be in the demo corpus, in the registry, and in no
     * extracted triple at all.
     *
     * <p>The registry is the authority; this list is a strict subset of it. A
     * predicate the registry has never heard of would have its cardinality fall
     * back to MULTI, which silently disables contradiction detection for that
     * relation rather than failing loudly. {@code PredicateVocabularyConsistencyTest}
     * enforces the subset property, and it caught this list claiming four
     * predicates the registry does not define.
     *
     * <p>This is a fixture, not a stub. The provider performs real parsing,
     * validation, quarantine, and persistence; it simply restricts itself to
     * sentences of the shape {@code Subject predicate Object}, which is what
     * makes the end-to-end tests deterministic.
     */
private static final java.util.Set<String> KNOWN_PREDICATES = java.util.Set.of(
            "acquires", "advises", "allied_with", "chief_executive", "collaborates_with",
            "competes_with", "controls", "exports_to", "founded_by", "funds", "headquartered_in",
            "invests_in", "licenses_to", "located_in", "member_of", "operated_by", "operates_in",
            "owns", "parent_organization", "part_of", "preceded_by", "regulated_by", "reports_to",
            "sources_from", "subsidiary_of", "succeeded_by", "supplies", "works_for");

    private String extractDataBlock(String user) {
        var matcher = java.util.regex.Pattern.compile(
                "(?s)<DATA id=\"chunk-\\d+\"[^>]*>(.*?)</DATA>").matcher(user);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private List<String> splitSentences(String text) {
        List<String> out = new java.util.ArrayList<>();
        for (String part : text.split("(?<=[.!?])\\s+")) {
            String trimmed = part.strip();
            if (trimmed.length() > 15) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private String quote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
    }

    private String fakeJudge(String user) {
        long cited = extractCitedPassageIds(user);
        boolean hasEvidence = cited > 0;
        return """
                {
                  "verdict": "%s",
                  "confidence": 0.55,
                  "reasoning": "Offline test mode: deterministic fixture verdict.",
                  "passage_ids": [%s]
                }
                """.formatted(hasEvidence ? "INSUFFICIENT_EVIDENCE" : "SOURCE_MISSING",
                citeAll(user));
    }

    private String fakeDebate(String user, String system) {
        String persona = system.contains("SKEPTIC") ? "SKEPTIC"
                : system.contains("DOVE") ? "DOVE" : "HAWK";
        // Cite the first supplied passage so the citation path is exercised.
        String citation = firstPassageId(user);
        return """
                {
                  "argument": "Offline test mode: the %s position over the supplied evidence.",
                  "passage_ids": [%s],
                  "stance": "deterministic-fixture"
                }
                """.formatted(persona, citation);
    }

    private String fakeSynthesis(String user) {
        List<String> argumentIds = extractBracketedIds(user, "argument ");
        return """
                {
                  "blocks": [
                    {
                      "block_type": "MACHINE_RECORD",
                      "text": "Offline test mode: deterministic fixture block.",
                      "argument_ids": [%s],
                      "machine_fact_ids": [],
                      "passage_ids": []
                    }
                  ],
                  "conclusion": "Offline test mode: deterministic fixture conclusion."
                }
                """.formatted(join(argumentIds));
    }

    private String fakeChat(String user) {
        String passage = firstPassageId(user);
        return """
                {
                  "answer": "Offline test mode: the answer derived from the supplied passages.",
                  "passage_ids": [%s],
                  "graph_fact_ids": [],
                  "sufficient_evidence": %s
                }
                """.formatted(passage, passage.isBlank() ? "false" : "true");
    }

    // ---- helpers -----------------------------------------------------------

    private String firstPassageId(String user) {
        var matcher = java.util.regex.Pattern.compile("<PASSAGE id=\"(\\d+)\"").matcher(user);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String citeAll(String user) {
        var matcher = java.util.regex.Pattern.compile("<PASSAGE id=\"(\\d+)\"").matcher(user);
        java.util.List<String> ids = new java.util.ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return join(ids);
    }

    private long extractCitedPassageIds(String user) {
        return citeAll(user).split(",").length;
    }

    private List<String> extractBracketedIds(String user, String marker) {
        var matcher = java.util.regex.Pattern.compile("\\[argument (\\d+)\\]").matcher(user);
        List<String> ids = new java.util.ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private String join(List<String> values) {
        return String.join(", ", values);
    }
}
