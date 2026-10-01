package com.prism.document;

import com.prism.config.PrismTuning.Chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, sentence-aware chunker.
 *
 * <p>Pure logic: no Spring, no clock, no randomness. The same input always
 * produces the same chunks in the same order, which is what makes a chunk
 * citation reproducible months later.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Normalize line endings to {@code \n}. All reported offsets index into
 *       this normalized text, which is what gets stored on the document.</li>
 *   <li>Split into sentences on terminal punctuation, protecting abbreviations
 *       and decimals so "Dr. Alvarez" and "3.5 percent" do not split.</li>
 *   <li>Accumulate whole sentences until the token target would be exceeded.</li>
 *   <li>Carry the trailing sentence into the next chunk as overlap, then keep
 *       carrying while the overlap budget allows. At least one sentence is
 *       always carried when the chunk holds more than one, because losing the
 *       boundary sentence is exactly what overlap exists to prevent.</li>
 *   <li>Split any single sentence that alone exceeds the target, preferring
 *       clause boundaries and falling back to a hard cut only as a last resort.</li>
 * </ol>
 */
public final class SentenceChunker {

    /** Abbreviations ending in '.' that do not terminate a sentence. */
    private static final Set<String> NON_TERMINATING = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "gen", "col", "lt", "sgt",
            "capt", "rev", "hon", "pres", "gov", "sen", "rep", "inc", "ltd", "co", "corp",
            "dept", "univ", "approx", "est", "fig", "no", "vs", "etc", "al", "cf", "ca", "circa");

    private static final char[] SENTENCE_TERMINATORS = {'.', '!', '?', '。', '！', '？'};

    private final int targetTokens;
    private final int overlapTokens;
    private final int charsPerToken;

    public SentenceChunker(Chunking chunking) {
        this(chunking.targetTokens(), chunking.overlapTokens(), chunking.charsPerToken());
    }

    public SentenceChunker(int targetTokens, int overlapTokens, int charsPerToken) {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException("targetTokens must be positive");
        }
        if (overlapTokens < 0 || overlapTokens >= targetTokens) {
            throw new IllegalArgumentException("overlapTokens must be >= 0 and < targetTokens");
        }
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException("charsPerToken must be positive");
        }
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
        this.charsPerToken = charsPerToken;
    }

    /** One chunk with its character span into the normalized document text. */
    public record Chunk(int chunkIndex, String content, int startOffset, int endOffset, int tokenEstimate) {
    }

    /** A sentence and its exact span in the normalized text. */
    private record Span(int start, int end) {
        String text(String source) {
            return source.substring(start, end);
        }
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return tokensFor(text.length());
    }

    /** @return chunks in document order; empty when the input has no content. */
    public List<Chunk> chunk(String rawText) {
        String text = normalize(rawText);
        if (text.isBlank()) {
            return List.of();
        }

        List<Span> spans = splitSpans(text);
        if (spans.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        int sentIdx = 0;

        while (sentIdx < spans.size()) {
            int startOffset = spans.get(sentIdx).start();
            int lastIncluded = sentIdx;
            int charCount = 0;

            // Greedily include whole sentences while under the token target.
            while (lastIncluded < spans.size()) {
                Span span = spans.get(lastIncluded);
                int sentenceChars = span.end() - span.start();
                int separator = charCount == 0 ? 0 : 1;
                int projectedTokens = tokensFor(charCount + separator + sentenceChars);
                if (charCount > 0 && projectedTokens > targetTokens) {
                    break;
                }
                charCount += separator + sentenceChars;
                lastIncluded++;
            }

            if (lastIncluded == sentIdx) {
                // One sentence larger than the target: split it and continue.
                Span span = spans.get(sentIdx);
                int cut = clauseBoundary(text, span);
                String piece = text.substring(span.start(), cut).strip();
                if (!piece.isEmpty()) {
                    chunks.add(new Chunk(index++, piece, span.start(), cut, estimateTokens(piece)));
                }
                sentIdx++;
                continue;
            }

            int endOffset = spans.get(lastIncluded - 1).end();

            // Trim the span itself, not just the string, so the reported offsets
            // index exactly the text stored on the chunk. A chunk that starts
            // with a space would otherwise carry content[0] != text[startOffset].
            int from = startOffset;
            int to = endOffset;
            while (from < to && Character.isWhitespace(text.charAt(from))) {
                from++;
            }
            while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
                to--;
            }
            if (from >= to) {
                sentIdx = Math.max(lastIncluded, sentIdx + 1);
                continue;
            }

            String content = text.substring(from, to);
            chunks.add(new Chunk(index++, content, from, to, estimateTokens(content)));

            sentIdx = advanceWithOverlap(text, spans, sentIdx, lastIncluded);
        }

        return List.copyOf(chunks);
    }

    /**
     * Chooses the next starting sentence index, carrying trailing sentences as
     * overlap. Guarantees forward progress so the loop always terminates.
     */
    private int advanceWithOverlap(String text, List<Span> spans, int chunkStart, int chunkEnd) {
        if (overlapTokens <= 0 || chunkEnd <= chunkStart) {
            return chunkEnd;
        }
        // Always carry the chunk's final sentence: that boundary context is the
        // entire purpose of overlap, even when the sentence alone exceeds the
        // budget or the chunk holds only one sentence.
        int carryFrom = chunkEnd - 1;
        int budget = overlapTokens - estimateTokens(spans.get(carryFrom).text(text));
        // Carry further back while the budget allows, but never past chunkStart+1:
        // re-emitting the whole previous chunk as the next one would stall progress.
        for (int back = carryFrom - 1; back > chunkStart; back--) {
            int cost = estimateTokens(spans.get(back).text(text));
            if (cost > budget) {
                break;
            }
            budget -= cost;
            carryFrom = back;
        }
        return Math.max(carryFrom, chunkStart + 1);
    }

    /** Token estimate from a character count, without materialising a string. */
    private int tokensFor(int charCount) {
        if (charCount <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) charCount / charsPerToken);
    }

    /**
     * Picks a cut point inside an oversized sentence, preferring a clause
     * boundary in the second half of the budget so neither piece is trivially small.
     */
    private int clauseBoundary(String text, Span span) {
        int maxChars = targetTokens * charsPerToken;
        int available = span.end() - span.start();
        if (available <= maxChars) {
            return span.end();
        }
        int hardCut = span.start() + maxChars;
        int floor = span.start() + maxChars / 2;
        for (int i = hardCut; i > floor; i--) {
            char c = text.charAt(i);
            if (c == ';' || c == ':' || c == ',' || c == '—' || c == '–') {
                return i;
            }
        }
        return hardCut;
    }

    /**
     * Splits into sentences, recording exact spans so every returned sentence is
     * a verbatim slice of the input and offsets remain valid.
     */
    private List<Span> splitSpans(String text) {
        List<Span> spans = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isTerminator(c)) {
                continue;
            }
            if (c == '.' && (isDecimalPoint(text, i) || isAbbreviation(text, i))) {
                continue;
            }
            // Consume trailing quotes and brackets that belong to this sentence.
            int end = i + 1;
            while (end < text.length() && isSentenceTail(text.charAt(end))) {
                end++;
            }
            if (!text.substring(start, end).isBlank()) {
                spans.add(new Span(start, end));
            }
            start = end;
        }
        if (start < text.length() && !text.substring(start).isBlank()) {
            spans.add(new Span(start, text.length()));
        }
        return spans;
    }

    /** Public sentence splitter; returns the sentence strings without offsets. */
    public List<String> splitSentences(String text) {
        String normalized = normalize(text);
        List<Span> spans = splitSpans(normalized);
        List<String> out = new ArrayList<>(spans.size());
        for (Span span : spans) {
            out.add(span.text(normalized));
        }
        return out;
    }

    private static boolean isTerminator(char c) {
        for (char t : SENTENCE_TERMINATORS) {
            if (c == t) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSentenceTail(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’' || c == '）';
    }

    private static boolean isDecimalPoint(String text, int i) {
        if (i == 0 || i + 1 >= text.length()) {
            return false;
        }
        return Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1));
    }

    private static boolean isAbbreviation(String text, int dotIndex) {
        int start = dotIndex - 1;
        int letters = 0;
        while (start >= 0 && Character.isLetter(text.charAt(start))) {
            start--;
            letters++;
        }
        if (letters == 0) {
            return false;
        }
        // A single uppercase letter before '.' is an initial: "J. R. Ewing".
        if (letters == 1 && Character.isUpperCase(text.charAt(dotIndex - 1))) {
            return true;
        }
        String word = text.substring(start + 1, dotIndex);
        return NON_TERMINATING.contains(word.toLowerCase(Locale.ROOT));
    }

    /** Normalizes line endings; offsets reported by the chunker are into this form. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');
        // Strip NUL and other C0 control characters except tab and newline.
        // Compared as chars so no escape sequence can be mis-encoded as a literal.
        StringBuilder out = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            if (c == '\n' || c == '\t' || (c >= ' ' && c != 127)) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }
}
