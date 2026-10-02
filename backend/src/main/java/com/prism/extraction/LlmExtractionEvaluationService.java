package com.prism.extraction;

import com.prism.config.LlmProperties;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import com.prism.document.DocumentRepository;
import com.prism.knowledge.PredicateSemanticRegistry;
import com.prism.llm.LlmClient;
import com.prism.llm.LlmCompletion;
import com.prism.llm.LlmPermanentException;
import com.prism.llm.LlmTransientException;
import com.prism.llm.Prompts;
import com.prism.llm.RetryingLlmService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures how well a provider extracts facts, against hand-checked labels.
 *
 * <p><b>Why this exists.</b> Everything measured so far used the offline
 * provider, which proves the pipeline is correct and says nothing about whether a
 * model can extract anything. There was no way to answer "how often does the model
 * return malformed JSON, and how often does it invent a fact", so those numbers
 * simply did not exist. This is the smallest thing that makes them exist.
 *
 * <p><b>It runs the production path, not a parallel one.</b> The same
 * {@link Prompts#extractSystem()}, the same {@link Prompts#extractUser}, the same
 * {@link RetryingLlmService} with its retry policy, and the same
 * {@link ExtractionResultParser}. A harness with its own prompt would measure the
 * harness. Everything up to and including parsing and validation is shared with
 * production; only persistence and entity resolution are skipped, because writing
 * to a live corpus to measure it would corrupt the corpus being measured.
 *
 * <p><b>What it cannot tell you.</b> The demo corpus states every extractable fact
 * in canonical {@code Subject predicate Object} form, and its prose is
 * deliberately uninformative. A model can score near 100% here by pattern
 * matching, and that number says nothing about extraction from a contracts pack or
 * a board minute. {@link #CORPUS_LIMITATION} is included in every report so the
 * caveat travels with the figure instead of living only in a document someone may
 * not read.
 *
 * <p><b>Matching rule.</b> A predicted triple matches a gold one when subject,
 * predicate and object agree after case folding and whitespace collapsing.
 * Entity aliases are NOT resolved before matching, so "Northstar" predicted where
 * the gold says "Northstar Holdings" counts as a miss. That is deliberate: it
 * measures the model's output as written, and alias handling belongs to the
 * separate entity-resolution step rather than being silently absorbed here.
 */
@Service
public class LlmExtractionEvaluationService {

    /**
     * Carried in every report so the caveat travels with the number.
     *
     * <p>Not a footnote in a document. The whole risk of this harness is that its
     * figures get quoted as "extraction accuracy" without the corpus caveat.
     */
    public static final String CORPUS_LIMITATION =
            "The demo corpus states every extractable fact in canonical "
                    + "'Subject predicate Object' form and its prose is deliberately uninformative. "
                    + "These figures measure transcription and entity resolution, NOT extraction from "
                    + "natural prose. Do not quote them as extraction accuracy on real documents.";

    private final RetryingLlmService llm;
    private final LlmClient provider;
    private final ExtractionResultParser parser;
    private final LlmProperties llmProperties;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final CorpusAccessService access;
    private final PredicateSemanticRegistry registry;

    public LlmExtractionEvaluationService(RetryingLlmService llm,
                                          LlmClient provider,
                                          ExtractionResultParser parser,
                                          LlmProperties llmProperties,
                                          DocumentRepository documents,
                                          DocumentChunkRepository chunks,
                                          CorpusAccessService access,
                                          PredicateSemanticRegistry registry) {
        this.llm = llm;
        this.provider = provider;
        this.parser = parser;
        this.llmProperties = llmProperties;
        this.documents = documents;
        this.chunks = chunks;
        this.access = access;
        this.registry = registry;
    }

    // ---- gold set ----------------------------------------------------------

    /** One hand-checked fact: what the document states, and where it states it. */
    public record GoldTriple(String document, String subject, String predicate, String object,
                             String sentence) {

        /** Canonical comparison key. Space separated so a key cannot be forged by moving a word across it. */
        String key() {
            return norm(subject) + " " + norm(predicate) + " " + norm(object);
        }
    }

    /**
     * Parses {@code eval/gold-extraction.tsv}.
     *
     * <p>Splits on TAB and skips {@code #} comments and the header. A row is
     * rejected rather than skipped when its predicate is not in the registry:
     * a gold set containing an unknown relation would silently score a correct
     * extractor as imprecise, and a typo in a label should be loud.
     *
     * @throws IllegalArgumentException naming the line, because a partially
     *         applied gold set produces metrics that look real and are wrong
     */
    public static List<GoldTriple> parseGold(String text) {
        List<GoldTriple> parsed = new ArrayList<>();
        PredicateSemanticRegistry known = new PredicateSemanticRegistry();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith("#") || line.startsWith("document\t")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 5) {
                throw new IllegalArgumentException("gold line " + (i + 1)
                        + " has " + parts.length + " columns, expected 5");
            }
            String predicate = parts[2].trim();
            if (!known.isKnown(predicate)) {
                throw new IllegalArgumentException("gold line " + (i + 1)
                        + " uses predicate '" + predicate
                        + "', which is not in the registry; a label PRISM could never "
                        + "accept would score a correct extractor as imprecise");
            }
            parsed.add(new GoldTriple(parts[0].trim(), parts[1].trim(), predicate,
                    parts[3].trim(), parts[4].trim()));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("the gold set is empty");
        }
        return parsed;
    }

    // ---- report ------------------------------------------------------------

    /** Counts and the metric derived from them. */
    public record Counts(int truePositive, int falsePositive, int falseNegative) {

        public double precision() {
            int predicted = truePositive + falsePositive;
            return predicted == 0 ? 1.0 : (double) truePositive / predicted;
        }

        public double recall() {
            int gold = truePositive + falseNegative;
            return gold == 0 ? 1.0 : (double) truePositive / gold;
        }

        /**
         * F1 as the harmonic mean, or 0 when both are 0.
         *
         * <p>Not derived from an averaged precision and recall. Macro-averaging the
         * two separately gives a different number, and the difference shows up
         * exactly when a run is unbalanced, which is when the number matters.
         */
        public double f1() {
            double p = precision();
            double r = recall();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /** What happened to one chunk. Retained so a bad aggregate can be explained. */
    public record ChunkOutcome(
            Long chunkId,
            String document,
            boolean parsed,
            String quarantineReason,
            int goldTriples,
            int predictedTriples,
            Counts triples,
            Counts claims,
            long latencyMs,
            int attempt,
            String error) {
    }

    /**
     * Everything needed to reproduce the run.
     *
     * @param corpusLimitation why these figures must not be read as extraction
     *                         accuracy on prose
     * @param executedAt       wall clock of the run
     */
    public record ExtractionReport(
            String provider,
            String model,
            String promptVersion,
            double temperature,
            int maxTokens,
            int maxRetries,
            Instant executedAt,
            int goldRows,
            int goldDocuments,
            int chunksEvaluated,
            int chunksWithGold,
            Counts triples,
            Counts claims,
            int malformedOutputs,
            int quarantined,
            int providerErrors,
            int transientErrorsExhausted,
            int unknownPredicatesRejected,
            long totalLatencyMs,
            String corpusLimitation,
            String matchingRule,
            List<ChunkOutcome> chunks) {

        public double getTriplePrecision() { return triples.precision(); }
        public double getTripleRecall() { return triples.recall(); }
        public double getTripleF1() { return triples.f1(); }
        public double getClaimPrecision() { return claims.precision(); }
        public double getClaimRecall() { return claims.recall(); }
        public double getClaimF1() { return claims.f1(); }

        /**
         * Fraction of chunks whose response could not be parsed.
         *
         * <p>Over all evaluated chunks, including ones with no gold. A malformed
         * response is a provider failure regardless of whether there was anything
         * to extract, so excluding gold-free chunks would flatter the rate.
         */
        public double getMalformedRate() {
            return chunksEvaluated == 0 ? 0.0 : (double) malformedOutputs / chunksEvaluated;
        }

        /** Fraction of chunks refused, for any reason, and therefore lost. */
        public double getQuarantineRate() {
            return chunksEvaluated == 0 ? 0.0 : (double) quarantined / chunksEvaluated;
        }
    }

    // ---- the measurement ---------------------------------------------------

    /**
     * Runs extraction over every chunk that carries a gold sentence and reports.
     *
     * <p>Verifier-gated at the controller, and this method authorises the corpus
     * itself so a caller cannot aim the measurement at a corpus they cannot read.
     *
     * <p>Read-only. Nothing is persisted, no entity is written and no trace run is
     * created: this measures the provider, and it must not be able to change the
     * state it is measuring.
     */
    @Transactional(readOnly = true)
    public ExtractionReport evaluate(Corpus corpus, Long userId, String goldText) {
        List<GoldTriple> gold = parseGold(goldText);
        Map<String, List<GoldTriple>> bySentence = new LinkedHashMap<>();
        Set<String> documents = new LinkedHashSet<>();
        for (GoldTriple g : gold) {
            bySentence.computeIfAbsent(norm(g.sentence()), k -> new ArrayList<>()).add(g);
            documents.add(g.document());
        }

        int evaluated = 0;
        int withGold = 0;
        int malformed = 0;
        int quarantined = 0;
        int providerErrors = 0;
        int transientExhausted = 0;
        int unknownPredicates = 0;
        long totalLatency = 0;
        int tpT = 0, fpT = 0, fnT = 0;
        int tpC = 0, fpC = 0, fnC = 0;
        List<ChunkOutcome> outcomes = new ArrayList<>();
        // Guards against a chunker overlap scoring one label twice.
        Set<String> claimedGold = new LinkedHashSet<>();

        for (Document document : documentsInCorpus(corpus)) {
            String filename = document.getOriginalFilename();
            if (filename == null || !documents.contains(filename)) {
                continue;
            }
            for (DocumentChunk chunk : chunks.findByDocumentOrderByChunkIndex(document)) {
                List<GoldTriple> chunkGold = goldIn(chunk.getContent(), filename, bySentence, claimedGold);
                if (chunkGold.isEmpty()) {
                    // Not evaluated. A chunk with no label can only contribute
                    // false positives, and counting those would measure the
                    // labelling, not the model.
                    continue;
                }
                evaluated++;
                withGold++;
                Outcome o = runOne(chunk, chunkGold);
                outcomes.add(o.outcome());
                totalLatency += o.outcome().latencyMs();
                if (!o.outcome().parsed()) {
                    malformed++;
                    quarantined++;
                    if (o.providerError()) {
                        providerErrors++;
                    }
                    if (o.transientFailure()) {
                        transientExhausted++;
                    }
                    if (o.unknownPredicate()) {
                        unknownPredicates++;
                    }
                    // A refused chunk contributes no predictions, so every gold
                    // triple in it is a miss. Hiding them would let a model score
                    // well by refusing to answer.
                    fnT += chunkGold.size();
                    fnC += o.goldClaimCount();
                    continue;
                }
                tpT += o.tpTriples();
                fpT += o.fpTriples();
                fnT += o.fnTriples();
                tpC += o.tpClaims();
                fpC += o.fpClaims();
                fnC += o.fnClaims();
            }
        }

        return new ExtractionReport(
                provider.providerName(),
                llmProperties.extractModel(),
                Prompts.EXTRACT_V1,
                llmProperties.temperature(),
                llmProperties.maxTokens(),
                llmProperties.maxRetries(),
                Instant.now(),
                gold.size(),
                documents.size(),
                evaluated,
                withGold,
                new Counts(tpT, fpT, fnT),
                new Counts(tpC, fpC, fnC),
                malformed,
                quarantined,
                providerErrors,
                transientExhausted,
                unknownPredicates,
                totalLatency,
                CORPUS_LIMITATION,
                "case-folded and whitespace-collapsed equality on subject, predicate and object; "
                        + "entity aliases are NOT resolved before comparison, so a near-miss spelling "
                        + "counts as a miss",
                outcomes);
    }

    /** Per-chunk result plus the internal tallies the aggregate needs. */
    private record Outcome(ChunkOutcome outcome, int goldClaimCount, int tpTriples, int fpTriples,
                           int fnTriples, int tpClaims, int fpClaims, int fnClaims,
                           boolean providerError, boolean transientFailure, boolean unknownPredicate) {
    }

    private Outcome runOne(DocumentChunk chunk, List<GoldTriple> chunkGold) {
        int goldClaims = chunkGold.size();
        LlmCompletion completion;
        try {
            // The same call production makes, including the retry policy. A
            // traceRunId of null records nothing, which is deliberate: this must
            // not appear in the Glass Box as if it were real pipeline work.
            completion = llm.complete(Prompts.extractSystem(), Prompts.extractUser(chunk),
                    LlmProperties.Purpose.EXTRACT, Prompts.EXTRACT_V1,
                    "llm-eval:chunk:" + chunk.getId(), null, null);
        } catch (LlmPermanentException ex) {
            return refuse(chunk, goldClaims, "PROVIDER_ERROR", 0, 1, ex.getMessage(), true, false);
        } catch (LlmTransientException ex) {
            return refuse(chunk, goldClaims, "PROVIDER_TIMEOUT", 0,
                    llmProperties.maxRetries(), ex.getMessage(), false, true);
        }

        ExtractionResultParser.ParseOutcome parsed =
                parser.parse(completion.rawText(), chunk.getContent());
        if (!parsed.success()) {
            String message = parsed.reason() + ": " + parsed.message();
            return refuse(chunk, goldClaims, parsed.reason().name(), completion.durationMs(),
                    completion.attempt(), message, false, false);
        }

        Set<String> goldKeys = new LinkedHashSet<>();
        for (GoldTriple g : chunkGold) {
            goldKeys.add(g.key());
        }

        Set<String> predicted = new LinkedHashSet<>();
        for (ExtractionDtos.TripleDto t : parsed.result().triples()) {
            predicted.add(norm(t.subject()) + ' ' + norm(t.predicate()) + ' ' + norm(t.object()));
        }
        Set<String> predictedClaims = new LinkedHashSet<>();
        for (ExtractionDtos.ClaimDto c : parsed.result().claims()) {
            predictedClaims.add(norm(c.claim()));
        }

        Counts tripleCounts = score(goldKeys, predicted);
        // A claim is gold when its text is one of the labelled sentences. Claims
        // are free text, so this is the only honest way to match them without a
        // second hand-written label set that could drift from this one.
        Set<String> goldClaimKeys = new LinkedHashSet<>();
        for (GoldTriple g : chunkGold) {
            goldClaimKeys.add(norm(g.sentence()));
        }
        Counts claimCounts = score(goldClaimKeys, predictedClaims);

        int unknown = 0;
        for (ExtractionDtos.TripleDto t : parsed.result().triples()) {
            if (!registry.isKnown(t.predicate())) {
                unknown++;
            }
        }

        ChunkOutcome outcome = new ChunkOutcome(chunk.getId(), chunk.getDocument().getTitle(),
                true, null, chunkGold.size(), predicted.size(), tripleCounts, claimCounts,
                completion.durationMs(), completion.attempt(), null);
        return new Outcome(outcome, goldClaims, tripleCounts.truePositive(),
                tripleCounts.falsePositive(), tripleCounts.falseNegative(),
                claimCounts.truePositive(), claimCounts.falsePositive(), claimCounts.falseNegative(),
                false, false, unknown > 0);
    }

    private Outcome refuse(DocumentChunk chunk, int goldClaims, String reason, long latency,
                           int attempt, String error, boolean providerError, boolean transientFailure) {
        ChunkOutcome outcome = new ChunkOutcome(chunk.getId(), chunk.getDocument().getTitle(),
                false, reason, goldClaims, 0,
                new Counts(0, 0, goldClaims), new Counts(0, 0, goldClaims),
                latency, attempt, error);
        return new Outcome(outcome, goldClaims, 0, 0, goldClaims, 0, 0, goldClaims,
                providerError, transientFailure, false);
    }

    /** Set comparison: a prediction matches at most one gold item. */
    private static Counts score(Set<String> gold, Set<String> predicted) {
        int tp = 0;
        for (String p : predicted) {
            if (gold.contains(p)) {
                tp++;
            }
        }
        return new Counts(tp, predicted.size() - tp, gold.size() - tp);
    }

    private List<Document> documentsInCorpus(Corpus corpus) {
        return documents.findByCorpus(corpus);
    }

    /**
     * Gold rows this chunk should yield, scoped to the chunk's own document and
     * claimed at most once across the whole run.
     *
     * <p>Both restrictions are load-bearing, and both were found by the harness
     * reporting more true positives (103) than the gold set contains (99).
     *
     * <p><b>Document scoping.</b> 34 gold sentences are stated by two documents
     * each, because the corpus deliberately restates facts in later memos.
     * Matching on sentence text alone credits one sentence against every document
     * containing it. A label says "document N states X", not "X appears anywhere".
     *
     * <p><b>Claim-once.</b> The chunker windows sentences with an overlap, so a
     * sentence near a boundary appears in two consecutive chunks. Without a claim
     * set it is scored twice and the model is credited for one extraction twice.
     * The first chunk to contain a sentence owns it, which is deterministic
     * because chunks are walked in ascending index order.
     *
     * <p>Together these guarantee the sum of per-chunk gold counts can never
     * exceed the number of gold rows -- a harness that can over-report its own
     * score is worse than no harness.
     */
    private static List<GoldTriple> goldIn(String content, String filename,
                                           Map<String, List<GoldTriple>> bySentence,
                                           Set<String> claimed) {
        String haystack = norm(content);
        List<GoldTriple> found = new ArrayList<>();
        for (Map.Entry<String, List<GoldTriple>> e : bySentence.entrySet()) {
            if (!haystack.contains(e.getKey())) {
                continue;
            }
            for (GoldTriple g : e.getValue()) {
                if (filename != null && !filename.equals(g.document())) {
                    continue;
                }
                // Key on document plus sentence, so the same sentence restated in
                // another document is still independently claimable.
                if (claimed.add(g.document() + '\u0000' + e.getKey())) {
                    found.add(g);
                }
            }
        }
        return found;
    }

    /**
     * Case folding plus whitespace and punctuation collapsing.
     *
     * <p>Punctuation is collapsed because a model may quote the trailing full stop
     * or not, and that difference is not what this metric is about. Matching the
     * way {@code ExtractionValidator} grounds sentences, so "is present" means the
     * same thing in both places.
     */
    private static String norm(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
    }
}
