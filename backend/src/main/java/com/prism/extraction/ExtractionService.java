package com.prism.extraction;

import com.prism.common.error.ApiException;
import com.prism.config.LlmProperties;
import com.prism.config.PrismMetrics;
import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.document.DocumentRepository;
import com.prism.document.DocumentStatus;
import com.prism.document.DocumentChunkRepository;
import com.prism.llm.LlmCompletion;
import com.prism.llm.LlmPermanentException;
import com.prism.llm.LlmTransientException;
import com.prism.llm.Prompts;
import com.prism.llm.RetryingLlmService;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceOperationType;
import com.prism.trace.TraceRecorder;
import com.prism.trace.TraceRun;
import com.prism.trace.TraceRunStatus;
import com.prism.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The Lattice: turns chunks into untrusted proposals.
 *
 * <p><b>The invariant this class protects.</b> Nothing written here is knowledge.
 * Everything is a PENDING proposal awaiting a human. APPROVED and REJECTED are
 * written exclusively by {@code ApprovalService} on behalf of a Verifier.
 *
 * <p><b>Transaction discipline.</b> The model call happens with no transaction
 * open. Persistence is delegated to {@link ExtractionPersistenceService}, whose
 * {@code REQUIRES_NEW} methods commit independently. Holding a pooled
 * connection across a 60-second model call would exhaust the pool under any
 * real concurrency.
 */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    private final ExtractionRunRepository runs;
    private final ExtractionResultParser parser;
    private final ExtractionPersistenceService persistence;
    private final RetryingLlmService llm;
    private final TraceRecorder traces;
    private final LlmProperties llmProperties;
    private final PrismMetrics metrics;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final com.prism.document.DocumentStatusService documentStatus;

    public ExtractionService(ExtractionRunRepository runs,
                             ExtractionResultParser parser,
                             ExtractionPersistenceService persistence,
                             RetryingLlmService llm,
                             TraceRecorder traces,
                             LlmProperties llmProperties,
                             PrismMetrics metrics,
                             DocumentRepository documents,
                             DocumentChunkRepository chunks,
                             com.prism.document.DocumentStatusService documentStatus) {
        this.runs = runs;
        this.parser = parser;
        this.persistence = persistence;
        this.llm = llm;
        this.traces = traces;
        this.llmProperties = llmProperties;
        this.metrics = metrics;
        this.documents = documents;
        this.chunks = chunks;
        this.documentStatus = documentStatus;
    }

    /** Result of processing one document. */
    public record ExtractionOutcome(Long runId, int chunksProcessed, int triplesProposed,
                                    int claimsProposed, int quarantined, boolean succeeded, String error) {
    }

    private record ChunkResult(int triplesPersisted, int claimsPersisted, boolean quarantined) {
    }

    /**
     * Runs extraction over every chunk of a document.
     *
     * <p>A single failing chunk is quarantined and the run continues. One bad
     * chunk must not discard the knowledge recoverable from the rest of the
     * document.
     */
    public ExtractionOutcome extractDocument(Long documentId) {
        long started = System.currentTimeMillis();
        Document document = documents.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document", documentId));
        List<DocumentChunk> chunkList = chunks.findByDocumentOrderByChunkIndex(document);

        if (chunkList.isEmpty()) {
            throw ApiException.validation("document " + documentId + " has no chunks; run chunking first");
        }

        TraceRun traceRun = traces.startRun(TraceOperationType.EXTRACTION, document.getCorpus(),
                document, document.getUploader(), "extract:document:" + documentId, null);
        ExtractionRun run = persistence.startRun(document, chunkList.size(),
                llmProperties.extractModel());
        documentStatus.updateStatus(documentId, DocumentStatus.EXTRACTING);

        TraceStep root = traces.record(traceRun.getId(), null, ActorType.ENGINE,
                TraceEventType.EXTRACTION_STARTED, "Extraction started",
                TraceRecorder.StepPayload.builder()
                        .inputSummary(document.getTitle())
                        .outputSummary(chunkList.size() + " chunks to process")
                        .outputRefs(chunkList.stream().map(DocumentChunk::getId).toList())
                        .ruleVersion(Prompts.EXTRACT_V1)
                        .build());

        int triples = 0;
        int claims = 0;
        int quarantinedCount = 0;

        try {
            for (DocumentChunk chunk : chunkList) {
                ChunkResult result = processChunk(run, document, chunk, traceRun.getId(), root);
                triples += result.triplesPersisted();
                claims += result.claimsPersisted();
                quarantinedCount += result.quarantined() ? 1 : 0;
                advanceProgress(run.getId(), result);
            }

            finishRun(run.getId(), true, null);
            // AWAITING_APPROVAL, not READY: nothing is trusted until a human acts.
            documentStatus.updateStatus(documentId, DocumentStatus.AWAITING_APPROVAL);

            traces.record(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.EXTRACTION_STARTED,
                    "Extraction complete; proposals await human approval",
                    TraceRecorder.StepPayload.builder()
                            .outputSummary("triples=" + triples + " claims=" + claims
                                    + " quarantined=" + quarantinedCount)
                            .build());
            traces.finishRun(traceRun.getId(), TraceRunStatus.SUCCEEDED, null);
            metrics.recordPipelineDuration("extraction", System.currentTimeMillis() - started);
            metrics.increment("prism.extraction.runs", "outcome", "success");

            return new ExtractionOutcome(run.getId(), chunkList.size(), triples, claims,
                    quarantinedCount, true, null);

        } catch (RuntimeException ex) {
            log.error("Extraction failed for document {}", documentId, ex);
            finishRun(run.getId(), false, ex.getMessage());
            documentStatus.updateStatus(documentId, DocumentStatus.FAILED);
            traces.failure(traceRun.getId(), root, ActorType.ENGINE, TraceEventType.PIPELINE_FAILED,
                    "Extraction failed", ex.getMessage());
            traces.finishRun(traceRun.getId(), TraceRunStatus.FAILED, ex.getMessage());
            metrics.recordPipelineDuration("extraction", System.currentTimeMillis() - started);
            metrics.increment("prism.extraction.runs", "outcome", "failure");
            return new ExtractionOutcome(run.getId(), 0, triples, claims, quarantinedCount,
                    false, ex.getMessage());
        }
    }

    /**
     * Processes one chunk: call the model, validate, then persist either
     * proposals or a quarantine record.
     */
    private ChunkResult processChunk(ExtractionRun run, Document document, DocumentChunk chunk,
                                     Long traceRunId, TraceStep parent) {
        traces.record(traceRunId, parent, ActorType.LLM, TraceEventType.LLM_REQUEST,
                "Extraction request for chunk " + chunk.getId(),
                TraceRecorder.StepPayload.builder()
                        .inputSummary("chunk " + chunk.getId() + " (" + chunk.getTokenEstimate() + " tokens)")
                        .inputRefs(List.of(chunk.getId()))
                        .promptVersion(Prompts.EXTRACT_V1)
                        .model(llmProperties.extractModel())
                        .outputSummary("source text passed as untrusted DATA, not as instructions")
                        .build());

        LlmCompletion completion;
        try {
            completion = llm.complete(Prompts.extractSystem(), Prompts.extractUser(chunk),
                    LlmProperties.Purpose.EXTRACT, Prompts.EXTRACT_V1,
                    "extract:chunk:" + chunk.getId(), traceRunId, parent);
        } catch (LlmPermanentException ex) {
            persistence.persistQuarantine(run, document, chunk, null, llmProperties.extractModel(),
                    QuarantineReason.PROVIDER_ERROR, ex.getMessage(), 1);
            traces.failure(traceRunId, parent, ActorType.LLM, TraceEventType.QUARANTINED,
                    "Provider permanently rejected the extraction request", ex.getMessage());
            metrics.increment("prism.extraction.quarantine", "reason", "PROVIDER_ERROR");
            return new ChunkResult(0, 0, true);
        } catch (LlmTransientException ex) {
            persistence.persistQuarantine(run, document, chunk, null, llmProperties.extractModel(),
                    QuarantineReason.PROVIDER_TIMEOUT, ex.getMessage(), llmProperties.maxRetries());
            traces.failure(traceRunId, parent, ActorType.LLM, TraceEventType.QUARANTINED,
                    "Provider retries exhausted for this chunk", ex.getMessage());
            metrics.increment("prism.extraction.quarantine", "reason", "PROVIDER_TIMEOUT");
            return new ChunkResult(0, 0, true);
        }

        ExtractionResultParser.ParseOutcome outcome =
                parser.parse(completion.rawText(), chunk.getContent());

        if (!outcome.success()) {
            persistence.persistQuarantine(run, document, chunk, completion.rawText(),
                    completion.model(), outcome.reason(), outcome.message(), completion.attempt());
            traces.record(traceRunId, parent, ActorType.ENGINE, TraceEventType.VALIDATION_FAILED,
                    "Extraction response rejected by validation",
                    TraceRecorder.StepPayload.builder()
                            .status("REJECTED")
                            .inputRefs(List.of(chunk.getId()))
                            .promptVersion(Prompts.EXTRACT_V1)
                            .model(completion.model())
                            .outputSummary(outcome.reason().name() + ": " + outcome.message())
                            .build());
            traces.simple(traceRunId, parent, ActorType.ENGINE, TraceEventType.QUARANTINED,
                    "Response quarantined: " + outcome.reason());
            metrics.increment("prism.extraction.quarantine", "reason", outcome.reason().name());
            return new ChunkResult(0, 0, true);
        }

        // Re-attach the managed run so the FK is valid after the async boundary.
        ExtractionRun managed = runs.findById(run.getId()).orElseThrow();
        ProposalWriter.Persisted persisted =
                persistence.persistProposals(managed.getId(), document, chunk, outcome.result());

        traces.record(traceRunId, parent, ActorType.ENGINE, TraceEventType.JSON_PARSED,
                "Extraction response accepted",
                TraceRecorder.StepPayload.builder()
                        .inputRefs(List.of(chunk.getId()))
                        .promptVersion(Prompts.EXTRACT_V1)
                        .model(completion.model())
                        .outputSummary("triples=" + persisted.triples()
                                + " claims=" + persisted.claims())
                        .build());

        return new ChunkResult(persisted.triples(), persisted.claims(), false);
    }

    /**
     * Advances the run's progress counters.
     *
     * <p>Each call re-reads and writes within one transaction rather than holding
     * a versioned entity across calls, which is what would otherwise cause a
     * stale-version failure on the second chunk.
     */
    private void advanceProgress(Long runId, ChunkResult result) {
        runs.findById(runId).ifPresent(run -> {
            run.setProcessedChunks(run.getProcessedChunks() + 1);
            run.recordProgress(result.triplesPersisted(), result.claimsPersisted(),
                    result.quarantined() ? 1 : 0, Instant.now());
            runs.save(run);
        });
    }

    private void finishRun(Long runId, boolean success, String error) {
        runs.findById(runId).ifPresent(run -> {
            if (success) {
                run.markSucceeded(Instant.now());
            } else {
                run.markFailed(error, Instant.now());
            }
            runs.save(run);
        });
    }
}
