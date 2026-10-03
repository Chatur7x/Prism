package com.prism.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.config.LlmProperties;
import com.prism.corpus.Corpus;
import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import com.prism.document.DocumentRepository;
import com.prism.knowledge.PredicateSemanticRegistry;
import com.prism.llm.LlmClient;
import com.prism.llm.LlmPermanentException;
import com.prism.llm.LlmTransientException;
import com.prism.llm.Prompts;
import com.prism.llm.RetryingLlmService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Measures extraction from natural prose, against hand-written labels.
 *
 * <p>This exists because the canonical demo corpus cannot answer the question. It
 * states every fact as {@code Subject predicate Object}, so a provider scores near
 * 100% by pattern matching and the number says nothing about extraction from a
 * board minute or a contract summary. {@code eval/prose-gold-v1.json} is prose,
 * and this is the harness for it.
 *
 * <p>Deliberately a separate service rather than a flag on the canonical one. The
 * two differ in the way that matters most: this set has <b>negatives</b> -- 59% of
 * its labelled sentences state that nothing should be extracted -- and precision
 * is only observable there. One implementation with a branch would grow a code
 * path only one dataset ever exercises, and that path is where the metrics would
 * quietly go wrong.
 *
 * <p><b>Expectations are claimed, not broadcast.</b> An expectation belongs to a
 * sentence, and a sentence lives in one chunk, so each is handed to exactly one
 * chunk. Scattering a document's whole gold set across all of its chunks counted
 * one expected triple once per chunk, turning a 40-triple gold set into 156
 * expected misses -- a harness reporting a recall it had manufactured.
 *
 * <p><b>No provider confidence is recorded as a calibrated probability.</b> The
 * extraction DTOs have no such field, which is why the report has nowhere to put
 * one.
 */
@Service
public class ProseExtractionEvaluationService {

    /** Carried in every report so the caveat travels with the figures. */
    public static final String DATASET_LIMITATION =
            "This dataset is fictional prose written for the purpose. It exercises pronouns, "
                    + "multi-clause sentences, passive voice, negation, hedging, temporal statements, "
                    + "several relations per sentence, and instruction-shaped text. It is NOT a "
                    + "statistically representative sample of real documents and must not be "
                    + "reported as one. It contains no OCR noise, tables, homonyms, aliases or "
                    + "transliteration variants, all of which make real extraction harder, so "
                    + "these figures are an upper bound rather than a forecast.";

    private final RetryingLlmService llm;
    private final LlmClient provider;
    private final ExtractionResultParser parser;
    private final LlmProperties llmProperties;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final PredicateSemanticRegistry registry;
    private final ObjectMapper objectMapper;

    public ProseExtractionEvaluationService(RetryingLlmService llm, LlmClient provider,
                                            ExtractionResultParser parser,
                                            LlmProperties llmProperties,
                                            DocumentRepository documents,
                                            DocumentChunkRepository chunks,
                                            PredicateSemanticRegistry registry,
                                            ObjectMapper objectMapper) {
        this.llm = llm;
        this.provider = provider;
        this.parser = parser;
        this.llmProperties = llmProperties;
        this.documents = documents;
        this.chunks = chunks;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // ---- the gold set --------------------------------------------------------

    /** One hand-written expected triple. */
    public record ExpectedTriple(String document, String subject, String predicate, String object) {

        String key() {
            return ProseExtractionEvaluationService.norm(subject) + ' '
                    + ProseExtractionEvaluationService.norm(predicate) + ' '
                    + ProseExtractionEvaluationService.norm(object);
        }
    }

    /** One hand-written expected claim. Polarity is part of the expectation. */
    public record ExpectedClaim(String document, String subject, String claim, String polarity) {

        String key() {
            return ProseExtractionEvaluationService.norm(subject) + '|'
                    + ProseExtractionEvaluationService.norm(polarity) + '|'
                    + ProseExtractionEvaluationService.norm(claim);
        }
    }

    /** The parsed dataset. */
    public record ProseGold(String datasetVersion, int documents, int labelledSentences,
                            int negativeSentences, Map<String, Set<ExpectedTriple>> triplesByDocument,
                            Map<String, Set<ExpectedClaim>> claimsByDocument) {

        public int expectedTriples() {
            return triplesByDocument.values().stream().mapToInt(Set::size).sum();
        }

        public int expectedClaims() {
            return claimsByDocument.values().stream().mapToInt(Set::size).sum();
        }
    }

    /**
     * Parses {@code eval/prose-gold-v1.json}.
     *
     * @throws IllegalArgumentException naming the problem, because a partially
     *         applied gold set produces metrics that look real and are wrong
     */
    public ProseGold parseGold(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "the prose gold set is not valid JSON: " + ex.getMessage());
        }
        if (!root.hasNonNull("datasetVersion")) {
            throw new IllegalArgumentException("the prose gold set has no datasetVersion");
        }

        Map<String, Set<ExpectedTriple>> triples = new TreeMap<>();
        Map<String, Set<ExpectedClaim>> claims = new TreeMap<>();
        int labelled = 0;
        int negatives = 0;
        int documents = 0;

        for (JsonNode doc : root.path("documents")) {
            documents++;
            String fileName = doc.path("fileName").asText();
            for (JsonNode sentence : doc.path("sentences")) {
                labelled++;
                if (sentence.path("expectNothing").asBoolean(false)) {
                    negatives++;
                }
                for (JsonNode t : sentence.path("triples")) {
                    String predicate = t.path("predicate").asText();
                    if (!registry.isKnown(predicate)) {
                        throw new IllegalArgumentException("gold triple uses unregistered predicate '"
                                + predicate + "' in " + fileName
                                + "; a label PRISM could never emit would score a correct "
                                + "extractor as imprecise");
                    }
                    triples.computeIfAbsent(fileName, k -> new LinkedHashSet<>())
                            .add(new ExpectedTriple(fileName, t.path("subject").asText(), predicate,
                                    t.path("object").asText()));
                }
                for (JsonNode c : sentence.path("claims")) {
                    String polarity = c.path("polarity").asText();
                    if (!Set.of("POSITIVE", "NEGATIVE", "NEUTRAL").contains(polarity)) {
                        throw new IllegalArgumentException(
                                "unknown claim polarity '" + polarity + "' in " + fileName);
                    }
                    claims.computeIfAbsent(fileName, k -> new LinkedHashSet<>())
                            .add(new ExpectedClaim(fileName, c.path("subject").asText(),
                                    c.path("claim").asText(), polarity));
                }
            }
        }
        if (labelled == 0) {
            throw new IllegalArgumentException("the prose gold set is empty");
        }
        return new ProseGold(root.path("datasetVersion").asText(), documents, labelled, negatives,
                triples, claims);
    }

    // ---- the report ----------------------------------------------------------

    /** Totals, because precision alone does not say how much was asked for. */
    public record Totals(int expected, int predicted, int correct, int missed, int incorrect) {

        public static Totals of(Counts c) {
            return new Totals(c.truePositive() + c.falseNegative(),
                    c.truePositive() + c.falsePositive(), c.truePositive(),
                    c.falseNegative(), c.falsePositive());
        }
    }

    /** Counts and the metrics derived from them. */
    public record Counts(int truePositive, int falsePositive, int falseNegative) {

        public double precision() {
            int predicted = truePositive + falsePositive;
            return predicted == 0 ? 1.0 : (double) truePositive / predicted;
        }

        public double recall() {
            int gold = truePositive + falseNegative;
            return gold == 0 ? 1.0 : (double) truePositive / gold;
        }

        /** Harmonic mean, not an average of the two: the difference shows when unbalanced. */
        public double f1() {
            double p = precision();
            double r = recall();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /** What happened to one chunk. Kept so a bad aggregate can be explained. */
    public record ChunkOutcome(Long chunkId, String document, boolean parsed, String quarantineReason,
                               boolean ungrounded, int expectedTriples, int predictedTriples,
                               Counts triples, Counts claims, long latencyMs, int attempt,
                               String error) {
    }

    public record ProseReport(String provider, String model, String promptVersion, double temperature,
                              int maxTokens, int maxRetries, Instant executedAt, String datasetVersion,
                              String corpusName, Long corpusId, int goldDocuments, int labelledSentences,
                              int negativeSentences, int chunksEvaluated, int chunksWithoutExpectation,
                              int expectationsPlaced, Counts triples, Counts claims,
                              Totals tripleTotals, Totals claimTotals, int malformedOutputs,
                              int quarantined, int ungroundedQuarantines, int providerErrors,
                              int transientErrorsExhausted, long totalLatencyMs, boolean testMode,
                              String datasetLimitation, String matchingRule, List<ChunkOutcome> chunks) {

        public double getTriplePrecision() { return triples.precision(); }
        public double getTripleRecall() { return triples.recall(); }
        public double getTripleF1() { return triples.f1(); }
        public double getClaimPrecision() { return claims.precision(); }
        public double getClaimRecall() { return claims.recall(); }
        public double getClaimF1() { return claims.f1(); }
        public double getMalformedRate() {
            return chunksEvaluated == 0 ? 0.0 : (double) malformedOutputs / chunksEvaluated;
        }
        public double getQuarantineRate() {
            return chunksEvaluated == 0 ? 0.0 : (double) quarantined / chunksEvaluated;
        }

        /**
         * Fraction of chunks refused because the model cited a sentence absent from
         * the source.
         *
         * <p>Reported apart from the overall quarantine rate on purpose: quarantining
         * for malformed JSON is a transport or prompt fault, while quarantining for a
         * fabricated citation is a grounding fault, and an operator responds to them
         * differently.
         */
        public double getUngroundedRate() {
            return chunksEvaluated == 0 ? 0.0 : (double) ungroundedQuarantines / chunksEvaluated;
        }
    }

    /** Thrown when fixture output would be presented as though it were a model result. */
    public static class RealModelExecutionRequired extends RuntimeException {

        public static final String TOKEN = "REAL_MODEL_EXECUTION_REQUIRED";

        public RealModelExecutionRequired(String message) {
            super(TOKEN + ": " + message);
        }
    }

    // ---- the measurement -----------------------------------------------------

    /**
     * Runs extraction over every chunk of the labelled prose documents.
     *
     * <p>Read-only: persists nothing, writes no entities and creates no trace run,
     * so it cannot change the state it measures.
     *
     * @param requireRealModel when true, refuses to return a report at all while the
     *                         offline fixture is active, so a CI job can demand
     *                         real-model numbers and fail clearly rather than
     *                         quietly succeed on fixture data
     * @throws RealModelExecutionRequired if a real model was required and is absent
     */
    @Transactional(readOnly = true)
    public ProseReport evaluate(Corpus corpus, Long userId, String goldJson, boolean requireRealModel) {
        boolean testMode = "fake".equalsIgnoreCase(String.valueOf(llmProperties.provider()));
        if (requireRealModel && testMode) {
            throw new RealModelExecutionRequired(
                    "the prose gold set was not measured because the offline fixture is active. "
                            + "Set LLM_PROVIDER=openai with LLM_BASE_URL, LLM_API_KEY and "
                            + "LLM_EXTRACT_MODEL before starting the backend, then re-run. "
                            + "Fixture output describes the pipeline and the fixture, and nothing "
                            + "about any model, so it is not reported in place of a result.");
        }

        ProseGold gold = parseGold(goldJson);
        Set<String> goldFiles = new LinkedHashSet<>(gold.triplesByDocument().keySet());
        goldFiles.addAll(gold.claimsByDocument().keySet());

        int evaluated = 0;
        int withoutExpectation = 0;
        int placed = 0;
        int malformed = 0;
        int quarantined = 0;
        int ungrounded = 0;
        int providerErrors = 0;
        int transientExhausted = 0;
        int tpT = 0, fpT = 0, fnT = 0;
        int tpC = 0, fpC = 0, fnC = 0;
        long totalLatency = 0;
        List<ChunkOutcome> outcomes = new ArrayList<>();

        for (Document document : documents.findByCorpus(corpus)) {
            String filename = document.getOriginalFilename();
            if (filename == null || !goldFiles.contains(filename)) {
                continue;
            }
            // Unclaimed expectations for this document. Each is handed to exactly one
            // chunk, because an expectation belongs to a sentence and a sentence lives
            // in one chunk.
            Set<ExpectedTriple> unclaimedTriples = new LinkedHashSet<>(
                    gold.triplesByDocument().getOrDefault(filename, Set.of()));
            Set<ExpectedClaim> unclaimedClaims = new LinkedHashSet<>(
                    gold.claimsByDocument().getOrDefault(filename, Set.of()));

            for (DocumentChunk chunk : chunks.findByDocumentOrderByChunkIndex(document)) {
                Set<ExpectedTriple> expectedTriples = new LinkedHashSet<>();
                Set<ExpectedClaim> expectedClaims = new LinkedHashSet<>();
                String haystack = norm(chunk.getContent());

                for (ExpectedTriple t : unclaimedTriples) {
                    // An expectation is placed on the chunk holding the entity it is
                    // about. A subject is the most reliable anchor available: object
                    // strings are often short and appear in many chunks.
                    if (haystack.contains(norm(t.subject()))) {
                        expectedTriples.add(t);
                    }
                }
                for (ExpectedClaim c : unclaimedClaims) {
                    if (haystack.contains(norm(c.claim())) || haystack.contains(norm(c.subject()))) {
                        expectedClaims.add(c);
                    }
                }
                unclaimedTriples.removeAll(expectedTriples);
                unclaimedClaims.removeAll(expectedClaims);
                placed += expectedTriples.size() + expectedClaims.size();

                if (expectedTriples.isEmpty() && expectedClaims.isEmpty()) {
                    // Nothing expected here, so anything predicted is a false positive.
                    // These chunks are where precision is actually observed.
                    withoutExpectation++;
                }

                evaluated++;
                ChunkOutcome outcome = runOne(chunk, expectedTriples, expectedClaims);
                outcomes.add(outcome);
                totalLatency += outcome.latencyMs();
                if (!outcome.parsed()) {
                    malformed++;
                    quarantined++;
                    if (outcome.ungrounded()) {
                        ungrounded++;
                    }
                    if ("PROVIDER_ERROR".equals(outcome.quarantineReason())) {
                        providerErrors++;
                    }
                    if ("PROVIDER_TIMEOUT".equals(outcome.quarantineReason())) {
                        transientExhausted++;
                    }
                    // A refused chunk contributes no predictions, so everything expected
                    // from it is a miss. Hiding that would let a provider score well by
                    // refusing to answer.
                    fnT += outcome.triples().falseNegative();
                    fnC += outcome.claims().falseNegative();
                    continue;
                }
                tpT += outcome.triples().truePositive();
                fpT += outcome.triples().falsePositive();
                fnT += outcome.triples().falseNegative();
                tpC += outcome.claims().truePositive();
                fpC += outcome.claims().falsePositive();
                fnC += outcome.claims().falseNegative();
            }

            if (!unclaimedTriples.isEmpty() || !unclaimedClaims.isEmpty()) {
                // Left unplaced means the label names something the chunker never
                // produced. That is a dataset or chunking defect, and silently
                // dropping it would understate the denominator.
                throw new IllegalStateException("the prose gold set expects "
                        + unclaimedTriples.size() + " triple(s) and " + unclaimedClaims.size()
                        + " claim(s) in " + filename
                        + " that no chunk contains. Either the sentence was not chunked or the "
                        + "label names an entity the document does not mention.");
            }
        }

        Counts tripleCounts = new Counts(tpT, fpT, fnT);
        Counts claimCounts = new Counts(tpC, fpC, fnC);

        return new ProseReport(
                provider.providerName(),
                llmProperties.extractModel(),
                Prompts.EXTRACT_V1,
                llmProperties.temperature(),
                llmProperties.maxTokens(),
                llmProperties.maxRetries(),
                Instant.now(),
                gold.datasetVersion(),
                corpus.getName(),
                corpus.getId(),
                gold.documents(),
                gold.labelledSentences(),
                gold.negativeSentences(),
                evaluated,
                withoutExpectation,
                placed,
                tripleCounts,
                claimCounts,
                Totals.of(tripleCounts),
                Totals.of(claimCounts),
                malformed,
                quarantined,
                ungrounded,
                providerErrors,
                transientExhausted,
                totalLatency,
                testMode,
                DATASET_LIMITATION,
                "case-folded and whitespace-collapsed equality on subject, predicate and object; "
                        + "claims additionally require matching polarity. Entity aliases are NOT "
                        + "resolved before comparison, so a near-miss spelling counts as a miss",
                outcomes);
    }

    private ChunkOutcome runOne(DocumentChunk chunk, Set<ExpectedTriple> expectedTriples,
                                Set<ExpectedClaim> expectedClaims) {
        com.prism.llm.LlmCompletion completionValue;
        try {
            completionValue = llm.complete(Prompts.extractSystem(), Prompts.extractUser(chunk),
                    LlmProperties.Purpose.EXTRACT, Prompts.EXTRACT_V1,
                    "prose-eval:chunk:" + chunk.getId(), null, null);
        } catch (LlmPermanentException ex) {
            return refuse(chunk, expectedTriples.size(), expectedClaims.size(), "PROVIDER_ERROR",
                    0, 1, ex.getMessage(), false);
        } catch (LlmTransientException ex) {
            return refuse(chunk, expectedTriples.size(), expectedClaims.size(), "PROVIDER_TIMEOUT",
                    0, llmProperties.maxRetries(), ex.getMessage(), false);
        }

        ExtractionResultParser.ParseOutcome parsed =
                parser.parse(completionValue.rawText(), chunk.getContent());
        if (!parsed.success()) {
            String message = parsed.reason().name() + ": " + parsed.message();
            // The grounding gate is the anti-hallucination check. Detected by message
            // because the semantic validator reports several kinds of failure through
            // one enum, and only this one means "the model cited text it was not shown".
            boolean grounded = message.contains("not present in the chunk");
            return refuse(chunk, expectedTriples.size(), expectedClaims.size(), parsed.reason().name(),
                    completionValue.durationMs(), completionValue.attempt(), message, grounded);
        }

        Set<String> predictedTriples = new LinkedHashSet<>();
        for (ExtractionDtos.TripleDto t : parsed.result().triples()) {
            predictedTriples.add(norm(t.subject()) + ' ' + norm(t.predicate()) + ' ' + norm(t.object()));
        }
        Set<String> predictedClaims = new LinkedHashSet<>();
        for (ExtractionDtos.ClaimDto c : parsed.result().claims()) {
            predictedClaims.add(norm(c.subject()) + '|' + norm(c.polarity().name()) + '|' + norm(c.claim()));
        }

        Set<String> goldTripleKeys = new LinkedHashSet<>();
        expectedTriples.forEach(t -> goldTripleKeys.add(t.key()));
        Set<String> goldClaimKeys = new LinkedHashSet<>();
        expectedClaims.forEach(c -> goldClaimKeys.add(c.key()));

        Counts tripleCounts = score(goldTripleKeys, predictedTriples);
        Counts claimCounts = score(goldClaimKeys, predictedClaims);

        return new ChunkOutcome(chunk.getId(), chunk.getDocument().getTitle(), true, null, false,
                expectedTriples.size(), predictedTriples.size(), tripleCounts, claimCounts,
                completionValue.durationMs(), completionValue.attempt(), null);
    }

    private ChunkOutcome refuse(DocumentChunk chunk, int expectedTripleCount, int expectedClaimCount,
                                String reason, long latency, int attempt, String error,
                                boolean ungrounded) {
        return new ChunkOutcome(chunk.getId(), chunk.getDocument().getTitle(), false, reason, ungrounded,
                expectedTripleCount, 0,
                new Counts(0, 0, expectedTripleCount), new Counts(0, 0, expectedClaimCount),
                latency, attempt, error);
    }

    /** A prediction matches at most one expectation. */
    private static Counts score(Set<String> expected, Set<String> predicted) {
        int tp = 0;
        for (String p : predicted) {
            if (expected.contains(p)) {
                tp++;
            }
        }
        return new Counts(tp, predicted.size() - tp, expected.size() - tp);
    }

    /** Case folding plus whitespace and punctuation collapsing. */
    static String norm(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
    }
}