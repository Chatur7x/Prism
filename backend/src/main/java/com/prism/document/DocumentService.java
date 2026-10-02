package com.prism.document;

import com.prism.common.Hashing;
import com.prism.common.error.ApiException;
import com.prism.config.PrismTuningProperties;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import com.prism.user.User;
import com.prism.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

/**
 * Document lifecycle up to and including chunking.
 *
 * <p>Chunking is synchronous and transactional because it is deterministic and
 * fast. Extraction, which is slow and calls an external model, is handed to the
 * async pipeline so the upload request returns immediately.
 */
@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final CorpusAccessService access;
    private final UserService users;
    private final SentenceChunker chunker;
    private final FileTextExtractor fileExtractor;

    public DocumentService(DocumentRepository documents, DocumentChunkRepository chunks,
                           CorpusAccessService access, UserService users,
                           PrismTuningProperties tuning, FileTextExtractor fileExtractor) {
        this.documents = documents;
        this.chunks = chunks;
        this.access = access;
        this.users = users;
        this.chunker = new SentenceChunker(tuning.chunking());
        this.fileExtractor = fileExtractor;
    }

    @Transactional
    public Document createFromText(Long userId, Long corpusId, String title, String contentText) {
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        String cleanTitle = validateTitle(title);
        String normalized = SentenceChunker.normalize(contentText);
        if (normalized.isEmpty()) {
            throw ApiException.validation("document content must not be empty");
        }
        enforceContentLength(normalized);
        return persist(corpus, users.getById(userId), cleanTitle, normalized, null, null);
    }

    @Transactional
    public Document createFromFile(Long userId, Long corpusId, String title, MultipartFile file) {
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("an uploaded file is required");
        }
        if (!fileExtractor.isSupported(file)) {
            throw new ApiException(com.prism.common.error.ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported file type. Accepted: .txt, .md, .csv, .pdf, .docx");
        }
        String safeName = FileTextExtractor.sanitizeFilename(file.getOriginalFilename());
        String text = fileExtractor.extract(file);
        enforceContentLength(text);

        String cleanTitle = (title == null || title.isBlank())
                ? FileTextExtractor.titleFromFilename(safeName)
                : validateTitle(title);
        return persist(corpus, users.getById(userId), cleanTitle, text, safeName, file.getContentType());
    }

    private Document persist(Corpus corpus, User uploader, String title, String normalizedText,
                             String filename, String mimeType) {
        String hash = Hashing.sha256Hex(normalizedText);
        // Idempotency: re-uploading identical text must not create a second
        // document, which would double every extracted proposal.
        List<Document> existing = documents.findByCorpusIdAndContentHash(corpus.getId(), hash);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Document document = documents.save(
                new Document(corpus, uploader, title, normalizedText, filename, mimeType, hash));
        return document;
    }

    /**
     * Chunks a document and stores its chunks. Idempotent: re-running replaces
     * nothing and returns the existing chunks, so a retried job cannot create
     * duplicate chunks and shift every citation offset.
     */
    @Transactional
    public List<DocumentChunk> chunkDocument(Long documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document", documentId));

        List<DocumentChunk> existing = chunks.findByDocumentOrderByChunkIndex(document);
        if (!existing.isEmpty()) {
            return existing;
        }

        document.transitionTo(DocumentStatus.CHUNKING, Instant.now());
        documents.save(document);

        List<SentenceChunker.Chunk> pieces = chunker.chunk(document.getContentText());
        if (pieces.isEmpty()) {
            document.transitionTo(DocumentStatus.FAILED, Instant.now());
            documents.save(document);
            throw ApiException.validation("document produced no chunks; its text is empty");
        }

        List<DocumentChunk> saved = new java.util.ArrayList<>(pieces.size());
        for (SentenceChunker.Chunk piece : pieces) {
            saved.add(chunks.save(new DocumentChunk(document, piece.chunkIndex(), piece.content(),
                    piece.startOffset(), piece.endOffset(), piece.tokenEstimate())));
        }
        document.transitionTo(DocumentStatus.CHUNKED, Instant.now());
        documents.save(document);
        return saved;
    }

    @Transactional(readOnly = true)
    public Document get(Long userId, Long documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document", documentId));
        // Object-level authorization: a valid token alone must not grant access
        // to an arbitrary document id.
        access.requireAccessible(document.getCorpus().getId(), userId);
        return document;
    }

    @Transactional(readOnly = true)
    public List<Document> list(Long userId, Long corpusId, int page, int size) {
        Corpus corpus = access.requireAccessible(corpusId, userId);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return documents.findByCorpusOrderByCreatedAtDesc(corpus,
                org.springframework.data.domain.PageRequest.of(safePage, safeSize)).getContent();
    }

    @Transactional(readOnly = true)
    public List<DocumentChunk> chunksOf(Long userId, Long documentId) {
        Document document = get(userId, documentId);
        return chunks.findByDocumentOrderByChunkIndex(document);
    }

    @Transactional(readOnly = true)
    public DocumentChunk chunk(Long userId, Long chunkId) {
        DocumentChunk chunk = chunks.findById(chunkId)
                .orElseThrow(() -> ApiException.notFound("DocumentChunk", chunkId));
        access.requireAccessible(chunk.getDocument().getCorpus().getId(), userId);
        return chunk;
    }

    @Transactional
    public void setStatus(Long documentId, DocumentStatus status) {
        documents.findById(documentId).ifPresent(d -> {
            d.transitionTo(status, Instant.now());
            documents.save(d);
        });
    }

    /**
     * Marks a document FAILED from the pipeline's failure path.
     *
     * <p>Exists so the pipeline can reach a terminal state without needing a
     * whole transaction spanning the failure handling. A document left in
     * CHUNKING or EXTRACTING would read as "still working" to every user
     * indefinitely, which is worse than a visible failure.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markFailed(Long documentId) {
        documents.findById(documentId).ifPresent(d -> {
            d.transitionTo(DocumentStatus.FAILED, Instant.now());
            documents.save(d);
        });
    }

    /**
     * Refuses to delete a document. Always, for everyone.
     *
     * <p>Deleting a document would orphan the provenance of every verdict that
     * cites it, so this endpoint exists to state the refusal rather than to
     * delete anything.
     *
     * <p>There is deliberately no ownership check first. The operation cannot
     * succeed for the owner either, so an ownership test would only have
     * changed the status code — a corpus member would have seen "only the corpus
     * owner may delete this", which reads as though the owner could. One
     * unconditional refusal with one message is the honest contract.
     *
     * <p>{@code get} still runs first, so a document the caller cannot see is
     * reported as not found rather than as deletable-but-refused, which would
     * otherwise disclose that the id exists.
     */
    @Transactional
    public void delete(Long userId, Long documentId) {
        get(userId, documentId);
        throw new ApiException(com.prism.common.error.ErrorCode.CONFLICT,
                "documents are not deleted, by anyone: approved knowledge and the verdicts that "
                        + "cite them depend on this provenance. Archive the corpus instead.");
    }

    private String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Untitled document";
        }
        String trimmed = title.trim();
        if (trimmed.length() > 500) {
            throw ApiException.validation("document title must be at most 500 characters");
        }
        return trimmed;
    }

    private void enforceContentLength(String text) {
        int maxChars = 2_000_000;
        if (text.length() > maxChars) {
            throw ApiException.validation("document text must be at most " + maxChars + " characters");
        }
    }
}
