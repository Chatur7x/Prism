package com.prism.claims;

import com.prism.config.PrismTuningProperties;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Corpus-scoped evidence retrieval over MySQL FULLTEXT.
 *
 * <p><b>Corpus isolation is enforced in SQL, not in Java.</b> Every query joins
 * to {@code documents} and filters {@code d.corpus_id = :corpusId}. Filtering
 * after the fact would be a bug waiting to happen: the index returns whatever
 * matches, and any mistake in a post-filter leaks another corpus's text.
 *
 * <p>MySQL FULLTEXT in natural-language mode ignores very short and very common
 * terms, so the query is normalised into a keyword bag and the result is
 * re-ranked deterministically. A query with no usable keywords returns nothing
 * rather than falling back to "return everything", because returning the whole
 * corpus to a judge invites it to find a spurious citation.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /**
     * Terms too common to discriminate. Removed from queries because MySQL's
     * 50%-threshold natural-language mode already ignores them, and removing
     * them client-side keeps the intent explicit.
     */
    private static final Set<String> STOP_TERMS = Set.of(
            "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with",
            "is", "are", "was", "were", "be", "been", "by", "from", "as", "that", "this", "these",
            "those", "it", "its", "he", "she", "they", "them", "his", "her", "their", "what",
            "which", "who", "whom", "how", "when", "where", "why", "does", "did", "do", "not",
            "no", "yes", "if", "then", "than", "so", "such", "have", "has", "had", "will",
            "would", "can", "could", "should", "may", "might", "must", "there", "here");

    /** Below this length a term is not discriminative enough to search on. */
    private static final int MIN_TERM_LENGTH = 3;

    /** Cap on keywords sent to MySQL, to keep the query plan bounded. */
    private static final int MAX_TERMS = 12;

    private final DocumentChunkRepository chunks;
    private final int topK;
    private final double minScore;

    public RetrievalService(DocumentChunkRepository chunks, PrismTuningProperties tuning) {
        this.chunks = chunks;
        this.topK = tuning.retrieval().topK();
        this.minScore = tuning.retrieval().minScore();
    }

    /**
     * One retrieved passage with the provenance the verdict needs to cite it.
     */
    public record RetrievedPassage(
            Long chunkId,
            Long documentId,
            String documentTitle,
            String text,
            double score,
            int rank) {
    }

    public record RetrievalResult(List<RetrievedPassage> passages, String normalizedQuery,
                                  boolean empty, int candidateCount) {

        public boolean hasEvidence() {
            return !passages.isEmpty();
        }
    }

    /**
     * Retrieves the top passages for a query, restricted to one corpus.
     *
     * @param corpusId the ONLY corpus whose chunks may be returned
     */
    @Transactional(readOnly = true)
    public RetrievalResult retrieve(Long corpusId, String rawQuery) {
        String normalized = buildFulltextQuery(rawQuery);
        if (normalized.isBlank()) {
            return new RetrievalResult(List.of(), "", true, 0);
        }

        // Over-fetch a little so the min-score filter has material to work on.
        int fetchLimit = Math.max(topK * 3, topK + 5);
        List<Object[]> rows = chunks.fulltextIdsWithScore(corpusId, normalized, fetchLimit);

        // The native query returns (id, relevance) pairs. Load the chunks with
        // their documents in one follow-up query rather than per chunk, and
        // index by id so relevance order is preserved.
        Map<Long, Double> relevanceByChunkId = new LinkedHashMap<>();
        List<Long> candidateIds = new ArrayList<>();
        for (Object[] row : rows) {
            if (row.length < 2 || !(row[0] instanceof Number id) || row[1] == null) {
                // A malformed row is skipped rather than allowed to fail the whole
                // retrieval: one bad score must not deny the caller every result.
                log.warn("Skipping a FULLTEXT row with an unexpected shape: {}", java.util.Arrays.toString(row));
                continue;
            }
            candidateIds.add(id.longValue());
            relevanceByChunkId.put(id.longValue(), toDouble(row[1]));
        }

        Map<Long, DocumentChunk> byId = new HashMap<>();
        if (!candidateIds.isEmpty()) {
            for (DocumentChunk chunk : chunks.findAllWithDocumentByIdIn(candidateIds)) {
                byId.put(chunk.getId(), chunk);
            }
        }

        List<RetrievedPassage> passages = new ArrayList<>();
        for (Long chunkId : candidateIds) {
            DocumentChunk chunk = byId.get(chunkId);
            if (chunk == null) {
                // The chunk was deleted between the two queries. Dropping it is
                // correct: a citation must point at a chunk that exists now.
                continue;
            }
            Double scoreBox = relevanceByChunkId.get(chunkId);
            double score = scoreBox == null ? 0.0 : scoreBox;
            if (score < minScore) {
                continue;
            }
            passages.add(new RetrievedPassage(
                    chunk.getId(),
                    chunk.getDocument().getId(),
                    chunk.getDocument().getTitle(),
                    chunk.getContent(),
                    score,
                    passages.size() + 1));
            if (passages.size() >= topK) {
                break;
            }
        }
        return new RetrievalResult(List.copyOf(passages), normalized, passages.isEmpty(), rows.size());
    }

    /**
     * Builds a MySQL fulltext query string from free text.
     *
     * <p>Each keyword is emitted as {@code +word} so all terms are required
     * (boolean AND). Using OR would rank a passage matching one common word
     * above a passage matching all of them.
     *
     * <p>MySQL fulltext has a hard minimum token length (default 3 for InnoDB
     * with {@code innodb_ft_min_token_size=3}); shorter terms are dropped here
     * rather than being silently ignored at query time, which would make the
     * result look arbitrary.
     */
    public static String buildFulltextQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        String lowered = rawQuery.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        StringBuilder sb = new StringBuilder();
        for (String token : lowered.split(" ")) {
            if (token.length() < MIN_TERM_LENGTH) {
                continue;
            }
            if (STOP_TERMS.contains(token)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('+').append(token);
            if (countTerms(sb) >= MAX_TERMS) {
                break;
            }
        }
        return sb.toString();
    }

    private static int countTerms(StringBuilder sb) {
        int count = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '+') {
                count++;
            }
        }
        return count;
    }

    /** Retrieval diagnostics, for the trace and the evaluation harness. */
    public record RetrievalDiagnostics(String rawQuery, String fulltextQuery, int candidatesReturned,
                                       int passagesKept, List<Long> chunkIds, String[] rejectedReasons) {
    }

    @Transactional(readOnly = true)
    public RetrievalDiagnostics diagnose(Long corpusId, String rawQuery) {
        String normalized = buildFulltextQuery(rawQuery);
        if (normalized.isBlank()) {
            return new RetrievalDiagnostics(rawQuery, "", 0, 0, List.of(),
                    new String[]{"query contained no searchable keywords after normalisation"});
        }
        List<Object[]> rows = chunks.fulltextIdsWithScore(corpusId, normalized,
                Math.max(topK * 3, topK + 5));
        RetrievalResult result = retrieve(corpusId, rawQuery);
        return new RetrievalDiagnostics(rawQuery, normalized, rows.size(), result.passages().size(),
                result.passages().stream().map(RetrievedPassage::chunkId).toList(),
                rows.isEmpty() ? new String[]{"no chunk in this corpus matched the query terms"} : new String[0]);
    }

    private static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
