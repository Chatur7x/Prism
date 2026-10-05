package com.prism.document;

import com.prism.common.PageResponse;
import com.prism.corpus.CorpusAccessService;
import com.prism.common.PageResponse;
import com.prism.extraction.ExtractionQuarantine;
import com.prism.common.PageResponse;
import com.prism.extraction.ExtractionQuarantineRepository;
import com.prism.common.PageResponse;
import com.prism.extraction.ExtractionRun;
import com.prism.common.PageResponse;
import com.prism.extraction.ExtractionRunRepository;
import com.prism.common.PageResponse;
import com.prism.pipeline.IngestionPipeline;
import com.prism.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import com.prism.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.prism.common.PageResponse;
import jakarta.validation.Valid;
import com.prism.common.PageResponse;
import jakarta.validation.constraints.NotBlank;
import com.prism.common.PageResponse;
import jakarta.validation.constraints.NotNull;
import com.prism.common.PageResponse;
import jakarta.validation.constraints.Size;
import com.prism.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import com.prism.common.PageResponse;
import org.springframework.http.HttpStatus;
import com.prism.common.PageResponse;
import org.springframework.http.ResponseEntity;
import com.prism.common.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.PathVariable;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.PostMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RequestBody;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RequestParam;
import com.prism.common.PageResponse;
import org.springframework.web.bind.annotation.RestController;
import com.prism.common.PageResponse;
import org.springframework.web.multipart.MultipartFile;

import com.prism.common.PageResponse;
import java.time.Instant;
import com.prism.common.PageResponse;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "Ingestion, chunking, and extraction progress")
public class DocumentController {

    private final DocumentService documents;
    private final ExtractionRunRepository runs;
    private final ExtractionQuarantineRepository quarantine;
    private final IngestionPipeline pipeline;
    private final CorpusAccessService access;

    public DocumentController(DocumentService documents, ExtractionRunRepository runs,
                              ExtractionQuarantineRepository quarantine, IngestionPipeline pipeline,
                              CorpusAccessService access) {
        this.documents = documents;
        this.runs = runs;
        this.quarantine = quarantine;
        this.pipeline = pipeline;
        this.access = access;
    }

    public record CreateDocumentRequest(
            @NotNull Long corpusId,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 2_000_000) String contentText) {
    }

    public record DocumentResponse(Long id, Long corpusId, String title, DocumentStatus status,
                                   String originalFilename, String mimeType, int contentLength,
                                   Instant createdAt, Instant updatedAt) {

        static DocumentResponse from(Document d) {
            return new DocumentResponse(d.getId(), d.getCorpus().getId(), d.getTitle(), d.getStatus(),
                    d.getOriginalFilename(), d.getMimeType(),
                    d.getContentText() == null ? 0 : d.getContentText().length(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record ChunkResponse(Long id, int chunkIndex, String content, int startOffset,
                                int endOffset, int tokenEstimate) {
        static ChunkResponse from(DocumentChunk c) {
            return new ChunkResponse(c.getId(), c.getChunkIndex(), c.getContent(), c.getStartOffset(),
                    c.getEndOffset(), c.getTokenEstimate());
        }
    }

    @GetMapping
    @Operation(summary = "List documents in a corpus",
            description = "Returns the standard page envelope so a client can tell whether more "
                    + "pages exist. This used to return a bare array with the total discarded.")
    public PageResponse<DocumentResponse> list(@RequestParam Long corpusId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        return PageResponse.of(documents.list(userId, corpusId, page, size)
                .map(DocumentResponse::from));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST','VERIFIER','ADMIN')")
    @Operation(summary = "Create a document from text and begin extraction asynchronously")
    public ResponseEntity<DocumentResponse> create(@Valid @RequestBody CreateDocumentRequest request) {
        Long userId = access.requireCurrentUserId();
        Document document = documents.createFromText(userId, request.corpusId(),
                request.title(), request.contentText());
        Document queued = pipeline.enqueueExtraction(document.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(queued));
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ANALYST','VERIFIER','ADMIN')")
    @Operation(summary = "Upload a PDF, DOCX, TXT, MD, or CSV file and begin extraction")
    public ResponseEntity<DocumentResponse> upload(@RequestParam Long corpusId,
                                                   @RequestParam(required = false) String title,
                                                   @RequestParam("file") MultipartFile file) {
        Long userId = access.requireCurrentUserId();
        Document document = documents.createFromFile(userId, corpusId, title, file);
        Document queued = pipeline.enqueueExtraction(document.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(queued));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one document; denied unless the caller can access its corpus")
    public DocumentResponse get(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return DocumentResponse.from(documents.get(userId, id));
    }

    @GetMapping("/{id}/content")
    @Operation(summary = "Full document text, for provenance inspection")
    public java.util.Map<String, Object> content(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Document document = documents.get(userId, id);
        return java.util.Map.of("id", document.getId(), "title", document.getTitle(),
                "contentText", document.getContentText());
    }

    @GetMapping("/{id}/chunks")
    @Operation(summary = "Chunks with their exact character offsets")
    public List<ChunkResponse> chunks(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return documents.chunksOf(userId, id).stream().map(ChunkResponse::from).toList();
    }

    @GetMapping("/{id}/progress")
    @Operation(summary = "Extraction progress, quarantine counts, and job state")
    public java.util.Map<String, Object> progress(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Document document = documents.get(userId, id);
        long chunkCount = documents.chunksOf(userId, id).size();
        ExtractionRun run = runs.findFirstByDocumentIdOrderByIdDesc(id).orElse(null);
        long quarantined = run == null ? 0 : quarantine.countByRunId(run.getId());

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("documentId", id);
        payload.put("status", document.getStatus().name());
        payload.put("chunkCount", chunkCount);
        payload.put("extractionRunId", run == null ? null : run.getId());
        payload.put("runStatus", run == null ? "NOT_STARTED" : run.getStatus().name());
        payload.put("processedChunks", run == null ? 0 : run.getProcessedChunks());
        payload.put("totalChunks", run == null ? 0 : run.getTotalChunks());
        payload.put("triplesFound", run == null ? 0 : run.getTriplesFound());
        payload.put("claimsFound", run == null ? 0 : run.getClaimsFound());
        payload.put("quarantinedCount", quarantined);
        payload.put("model", run == null ? null : run.getModel());
        payload.put("lastError", run == null ? null : run.getLastError());
        return payload;
    }

    @GetMapping("/{id}/quarantine")
    @Operation(summary = "Rejected model responses for this document, for audit",
            description = "Returns the standard page envelope. The rows are typed rather than a "
                    + "hand-built map, so a renamed field fails the contract check instead of "
                    + "rendering as a blank cell.")
    public PageResponse<QuarantineRow> quarantine(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        documents.get(userId, id);
        var result = quarantine.findByDocumentIdOrderByCreatedAtDesc(id,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        return PageResponse.of(result.map(QuarantineRow::from));
    }

    /**
     * One quarantined model response.
     *
     * <p>This was a hand-built {@code Map<String, Object>} keyed by string
     * literals, which is how a renamed field becomes a silently absent one. The
     * record is transcribed into {@code frontend/src/api/types.ts} and checked by
     * the contract script, so a change here now fails the build rather than
     * rendering blank cells.
     *
     * <p>{@code rawResponse} is retained verbatim on purpose: it is the evidence
     * for whatever the model actually returned, and reformatting it would defeat
     * the point of quarantining rather than discarding.
     */
    public record QuarantineRow(Long id, Long chunkId, Integer chunkIndex, String errorType,
                                String validationMessage, String model, String promptVersion,
                                Integer attempt, java.time.Instant createdAt,
                                String rawResponse) {

        static QuarantineRow from(ExtractionQuarantine q) {
            return new QuarantineRow(
                    q.getId(),
                    q.getChunk() == null ? null : q.getChunk().getId(),
                    q.getChunkIndex(),
                    q.getErrorType().name(),
                    q.getValidationMessage(),
                    q.getModel(),
                    q.getPromptVersion(),
                    q.getAttempt(),
                    q.getCreatedAt(),
                    q.getRawResponse());
        }
    }

    @PostMapping("/{id}/reprocess")
    @PreAuthorize("hasAnyRole('ANALYST','VERIFIER','ADMIN')")
    @Operation(summary = "Re-run extraction. Idempotent: existing chunks are reused and "
            + "duplicate facts are collapsed rather than duplicated.")
    public DocumentResponse reprocess(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        documents.get(userId, id);
        Document queued = pipeline.enqueueExtraction(id);
        return DocumentResponse.from(queued);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Refuses deletion. Provenance of approved knowledge depends on documents.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        documents.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
