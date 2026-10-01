package com.prism.extraction;

import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.knowledge.EntityResolutionService;
import com.prism.knowledge.EntityStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates short-transaction persistence of extraction output.
 *
 * <p>Deliberately a separate bean from {@link ExtractionService}. Spring's
 * transaction proxying is proxy-based, so a {@code @Transactional} method called
 * from inside the same class bypasses the advice entirely. Splitting the
 * persistence boundary into its own bean is what makes {@code REQUIRES_NEW}
 * actually take effect — which matters here, because these writes must commit
 * independently of the caller's state.
 *
 * <p>The actual triple and claim inserts live in {@link ProposalWriter}, which
 * owns the single write transaction. This class only decides <em>what</em> to
 * write; the writer decides <em>when</em>.
 */
@Service
public class ExtractionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionPersistenceService.class);

    private final ProposalWriter writer;
    private final EntityResolutionService entityResolution;
    private final ExtractionQuarantineRepository quarantineRepository;
    private final ExtractionRunRepository runs;

    public ExtractionPersistenceService(ProposalWriter writer,
                                        EntityResolutionService entityResolution,
                                        ExtractionQuarantineRepository quarantineRepository,
                                        ExtractionRunRepository runs) {
        this.writer = writer;
        this.entityResolution = entityResolution;
        this.quarantineRepository = quarantineRepository;
        this.runs = runs;
    }

    /**
     * Creates and claims a new extraction run.
     *
     * <p>Lives here rather than in {@link ExtractionService} so the transaction
     * actually applies: Spring's advice is proxy-based, so a {@code @Transactional}
     * method invoked from inside its own class never passes through the proxy and
     * the annotation silently does nothing.
     *
     * <p>Returns the saved instance, whose {@code version} is current. Carrying
     * the pre-save instance forward would fail on the next write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExtractionRun startRun(Document document, int chunkCount, String model) {
        ExtractionRun run = new ExtractionRun(document, chunkCount,
                com.prism.llm.Prompts.EXTRACT_V1, model);
        run.markRunning(Instant.now());
        return runs.saveAndFlush(run);
    }

    /**
     * Resolves endpoints, then writes proposals, for one chunk.
     *
     * <p><b>Not transactional, and that is the point.</b> The two halves are
     * deliberately separated:
     *
     * <ol>
     *   <li>Resolution touches {@code entities} — the most contended rows in the
     *       system, because every document in a corpus mentions the same handful
     *       of entities and documents are extracted concurrently.</li>
     *   <li>The write then inserts triples and claims without locking an entity
     *       row, so it cannot block behind another document's resolution.</li>
     * </ol>
     *
     * <p>Doing both inside one transaction held those locks across the whole
     * write, which serialised a 24-document corpus onto a handful of rows and
     * killed most jobs with {@code Lock wait timeout exceeded}.
     */
    public ProposalWriter.Persisted persistProposals(Long runId, Document document, DocumentChunk chunk,
                                                     ExtractionDtos.ExtractionResultDto result) {
        Long corpusId = document.getCorpus().getId();
        Long chunkId = chunk.getId();

        List<ProposalWriter.ResolvedEndpoints> endpoints = new ArrayList<>(result.triples().size());
        for (ExtractionDtos.TripleDto dto : result.triples()) {
            Optional<EntityStoreService.Snapshot> subject =
                    entityResolution.resolveOrCreate(corpusId, dto.subject(), chunkId);
            Optional<EntityStoreService.Snapshot> object =
                    entityResolution.resolveOrCreate(corpusId, dto.object(), chunkId);
            if (subject.isEmpty() || object.isEmpty()) {
                endpoints.add(ProposalWriter.ResolvedEndpoints.unresolved());
            } else {
                endpoints.add(new ProposalWriter.ResolvedEndpoints(
                        subject.get().id(), object.get().id(),
                        subject.get().normalizedName(), object.get().normalizedName()));
            }
        }

        return writer.write(runId, document, chunk, result, endpoints);
    }

    /** Records a rejected model response so the failure remains auditable. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistQuarantine(ExtractionRun run, Document document, DocumentChunk chunk,
                                  String rawResponse, String model, QuarantineReason reason,
                                  String message, int attempt) {
        quarantineRepository.save(new ExtractionQuarantine(run, document, chunk,
                com.prism.llm.Prompts.EXTRACT_V1, model, rawResponse, reason, message, attempt));
    }
}
