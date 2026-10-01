package com.prism.debate;

import com.prism.claims.RetrievalService.RetrievedPassage;
import com.prism.document.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one persona's argument and its validated citations, atomically.
 *
 * <p>Separate bean because the method was previously on {@link DebateService}
 * and called from it, which silently bypassed the transaction proxy. The visible
 * consequence was that the argument insert and its citation inserts ran in two
 * different implicit transactions: an argument could be recorded with none of
 * the provenance that justifies it, and no failure reported that.
 *
 * <p>That matters more than a normal write because the citations <em>are</em> the
 * argument's standing. An argument that cites nothing is still an argument, and
 * an argument whose citations were lost looks indistinguishable from one that
 * genuinely cited nothing. So the two must commit together or not at all.
 *
 * @see DebateService#runRound for why the calling service is deliberately not
 *      transactional: it makes model calls and must not hold a connection open
 *      across them.
 */
@Service
public class ArgumentPersistenceService {

    private final ArgumentRepository arguments;
    private final ArgumentCitationRepository citations;
    private final DocumentChunkRepository chunks;

    public ArgumentPersistenceService(ArgumentRepository arguments,
                                      ArgumentCitationRepository citations,
                                      DocumentChunkRepository chunks) {
        this.arguments = arguments;
        this.citations = citations;
        this.chunks = chunks;
    }

    /**
     * Persists one persona's result.
     *
     * <p>A failed persona is persisted as a failed argument with its reason. That
     * is deliberate: a silent omission would make a Council that lost two of its
     * three voices look identical to one where those voices agreed, and a failed
     * argument must never count as support for anything.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Argument write(DebateRound round, Debate debate,
                          PersonaRunner.PersonaResult result,
                          List<RetrievedPassage> retrieved) {
        Argument argument = result.succeeded()
                ? new Argument(round, debate, result.persona(), result.argumentText(),
                result.stance(), result.model(), result.promptVersion(), result.durationMs())
                : Argument.failed(round, debate, result.persona(),
                String.valueOf(result.failureReason()), result.model(), result.promptVersion());
        Argument saved = arguments.save(argument);

        if (!result.succeeded()) {
            return saved;
        }

        // Only passages the retriever actually returned are eligible. The model
        // is never trusted with a chunk id here: an invented id is discarded
        // rather than stored, so the provenance graph cannot contain a reference
        // to a passage that does not exist.
        Map<Long, RetrievedPassage> byId = new LinkedHashMap<>();
        for (RetrievedPassage p : retrieved) {
            byId.put(p.chunkId(), p);
        }

        for (Long chunkId : result.citedChunkIds()) {
            if (!byId.containsKey(chunkId)) {
                continue;
            }
            // Re-check corpus membership before linking, the same second lock the
            // verification evidence path uses. A chunk id from another corpus must
            // never be attached to a debate, even if the retriever returned it.
            chunks.findByIdAndCorpusId(chunkId, debate.getCorpus().getId())
                    .ifPresent(chunk -> citations.save(ArgumentCitation.toChunk(
                            saved, debate, debate.getCorpus(), chunk, abbreviate(chunk.getContent(), 600))));
        }
        return saved;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}