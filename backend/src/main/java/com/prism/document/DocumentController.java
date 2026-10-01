package com.prism.document;

import com.prism.corpus.CorpusAccessService;
import com.prism.extraction.ExtractionQuarantine;
import com.prism.extraction.ExtractionQuarantineRepository;
import com.prism.extraction.ExtractionRun;
import com.prism.extraction.ExtractionRunRepository;
import com.prism.pipeline.IngestionPipeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
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
    @Operation(summary = "List documents in a corpus")
    public List<DocumentResponse> list(@RequestParam Long corpusId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        return documents.list(userId, corpusId, page, size).stream().map(DocumentResponse::from).toList();
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
        ExtractionRun run = runs.findByDocumentIdOrderByIdDesc(id).orElse(null);
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
    @Operation(summary = "Rejected model responses for this document, for audit")
    public java.util.Map<String, Object> quarantine(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        Long userId = access.requireCurrentUserId();
        documents.get(userId, id);
        var result = quarantine.findByDocumentIdOrderByCreatedAtDesc(id,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        List<java.util.Map<String, Object>> rows = result.getContent().stream()
                .map(DocumentController::describeQuarantine)
                .toList();
        return java.util.Map.of("content", rows, "total", result.getTotalElements(),
                "page", result.getNumber(), "size", result.getSize());
    }

    private static java.util.Map<String, Object> describeQuarantine(ExtractionQuarantine q) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", q.getId());
        row.put("chunkId", q.getChunk() == null ? null : q.getChunk().getId());
        row.put("chunkIndex", q.getChunkIndex());
        row.put("errorType", q.getErrorType().name());
        row.put("validationMessage", q.getValidationMessage());
        row.put("model", q.getModel());
        row.put("promptVersion", q.getPromptVersion());
        row.put("attempt", q.getAttempt());
        row.put("createdAt", q.getCreatedAt());
        row.put("rawResponse", q.getRawResponse());
        return row;
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
