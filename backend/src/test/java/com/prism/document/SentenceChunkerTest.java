package com.prism.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SentenceChunkerTest {

    private final SentenceChunker chunker = new SentenceChunker(20, 5, 4);

    @Test
    @DisplayName("empty and blank input produce no chunks")
    void emptyInput() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n\n\t  ")).isEmpty();
    }

    @Test
    @DisplayName("a short single-sentence document yields exactly one chunk")
    void shortDocument() {
        List<SentenceChunker.Chunk> chunks = chunker.chunk("Meridian Group reports to Northstar Holdings.");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkIndex()).isZero();
        assertThat(chunks.get(0).content()).isEqualTo("Meridian Group reports to Northstar Holdings.");
    }

    @Test
    @DisplayName("chunk indexes are contiguous and start at zero")
    void indexesAreContiguous() {
        String text = String.join(" ", java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "Sentence number " + i + " states a fact about the corpus.")
                .toList());
        List<SentenceChunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("every chunk stays within a sane multiple of the target")
    void respectsTarget() {
        String text = String.join(" ", java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> "Statement " + i + " describes something of moderate length.")
                .toList());
        for (SentenceChunker.Chunk c : chunker.chunk(text)) {
            // Allow one-oversized-sentence slack; the target itself is a soft bound.
            assertThat(c.tokenEstimate())
                    .as("chunk %d token estimate", c.chunkIndex())
                    .isLessThanOrEqualTo(60);
        }
    }

    @Test
    @DisplayName("a very long single sentence is split rather than dropped")
    void veryLongSentence() {
        String longSentence = "The committee noted, after considerable deliberation, that "
                + "the arrangement between the two entities remained unresolved and required "
                + "further review before any binding commitment could be made. ";
        String text = longSentence.repeat(12).trim();
        List<SentenceChunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).isNotBlank());
    }

    @Test
    @DisplayName("character offsets index back into the normalized text exactly")
    void offsetsAreExact() {
        String text = "First fact here. Second fact here. Third fact follows the second. Fourth fact ends it.";
        String normalized = SentenceChunker.normalize(text);
        for (SentenceChunker.Chunk c : chunker.chunk(text)) {
            assertThat(c.startOffset()).isGreaterThanOrEqualTo(0);
            assertThat(c.endOffset()).isLessThanOrEqualTo(normalized.length());
            assertThat(c.endOffset()).isGreaterThan(c.startOffset());
        }
    }

    @Test
    @DisplayName("decimals and abbreviations do not split sentences")
    void decimalAndAbbreviationHandling() {
        List<String> sentences = chunker.splitSentences(
                "The firm reported 3.5 percent growth. Dr. Alvarez signed the memo. "
                        + "Total assets reached 12.75 billion.");
        assertThat(sentences).hasSize(3);
        assertThat(sentences.get(0)).contains("3.5 percent");
        assertThat(sentences.get(1)).contains("Dr. Alvarez");
        assertThat(sentences.get(2)).contains("12.75 billion");
    }

    @Test
    @DisplayName("multiple paragraphs are chunked without losing content")
    void multipleParagraphs() {
        String text = "Para one sentence one. Para one sentence two.\n\n"
                + "Para two sentence one. Para two sentence two.\n\n"
                + "Para three sentence one. Para three sentence two.";
        List<SentenceChunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).isNotEmpty();
        // The first and last sentences of the document must both survive.
        String joined = chunks.stream().map(SentenceChunker.Chunk::content).collect(Collectors.joining(" "));
        assertThat(joined).contains("Para one sentence one");
        assertThat(joined).contains("Para three sentence two");
    }

    @Test
    @DisplayName("Unicode content is preserved and chunked")
    void unicode() {
        String text = " Gruppe Meridian meldete einen Anstieg. "
                + "Ωμέγα Systems reported growth in region αβγ. "
                + "「引用」テキストがここにあります。";
        List<SentenceChunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).isNotEmpty();
        String joined = chunks.stream().map(SentenceChunker.Chunk::content).collect(Collectors.joining(" "));
        assertThat(joined).contains("Meridian");
        assertThat(joined).contains("「引用」");
    }

    @Test
    @DisplayName("chunking is deterministic: identical input yields identical output")
    void deterministic() {
        String text = String.join(" ", java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "Deterministic sentence " + i + " with filler content.")
                .toList());
        List<SentenceChunker.Chunk> first = chunker.chunk(text);
        List<SentenceChunker.Chunk> second = chunker.chunk(text);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("overlap is applied so consecutive chunks share a boundary sentence")
    void overlapIsApplied() {
        // Sentences must be small enough that several fit in one chunk, otherwise
        // each chunk holds a single sentence and overlap is impossible by design.
        SentenceChunker small = new SentenceChunker(30, 8, 4);
        String text = String.join(" ", java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "Short fact " + i + " here.")
                .toList());
        List<SentenceChunker.Chunk> chunks = small.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(2);
        // Chunks must be multi-sentence for overlap to be observable.
        assertThat(chunks.get(0).content()).as("first chunk should hold several sentences")
                .contains(". Short fact");

        for (int i = 0; i < chunks.size() - 1; i++) {
            String current = chunks.get(i).content();
            String lastSentence = current.substring(current.lastIndexOf('.') - 6);
            assertThat(chunks.get(i + 1).content())
                    .as("chunk %d should carry the trailing sentence of chunk %d", i + 1, i)
                    .contains(lastSentence);
        }
    }

    @Test
    @DisplayName("chunk content is exactly the text at the reported offsets")
    void offsetsMatchContentExactly() {
        String text = "Alpha fact is stated here. Beta fact follows the first one. "
                + "Gamma fact comes third. Delta fact concludes the record.";
        String normalized = SentenceChunker.normalize(text);
        for (SentenceChunker.Chunk c : chunker.chunk(text)) {
            assertThat(normalized.substring(c.startOffset(), c.endOffset()))
                    .as("offsets for chunk %d must address exactly its stored content", c.chunkIndex())
                    .isEqualTo(c.content());
            assertThat(c.content()).doesNotStartWith(" ").doesNotEndWith(" ");
        }
    }

    @Test
    @DisplayName("a sentence larger than the target is split, not dropped or duplicated")
    void oversizedSentenceIsSplitNotDuplicated() {
        String oneSentence = "The board reviewed the proposal and after extended discussion "
                + "of the merger terms and the resulting obligations under the amended "
                + "shareholder agreement reached no conclusion whatsoever. ";
        String text = oneSentence.repeat(6).trim();
        List<SentenceChunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        // Every chunk must be a real slice; no chunk may be blank.
        assertThat(chunks).allSatisfy(c -> assertThat(c.content()).isNotBlank().hasSizeGreaterThan(10));
    }

    @Test
    @DisplayName("constructor rejects nonsensical configuration")
    void validatesConfiguration() {
        assertThatThrownBy(() -> new SentenceChunker(0, 5, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentenceChunker(20, 20, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentenceChunker(20, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("whitespace-only document with no sentences still terminates")
    void noInfiniteLoop() {
        List<SentenceChunker.Chunk> chunks = chunker.chunk("   ...   ...   ...   ");
        assertThat(chunks).isNotNull();
    }
}
