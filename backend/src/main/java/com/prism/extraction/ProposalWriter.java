package com.prism.extraction;

import com.prism.common.Hashing;
import com.prism.claims.Claim;
import com.prism.claims.ClaimPolarity;
import com.prism.claims.ClaimRepository;
import com.prism.document.Document;
import com.prism.document.DocumentChunk;
import com.prism.knowledge.Entity;
import com.prism.knowledge.EntityResolutionService;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import com.prism.llm.Prompts;
import com.prism.user.User;
import com.prism.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The transactional half of proposal persistence.
 *
 * <p>Split from {@link ExtractionPersistenceService} so that the write runs in
 * its own transaction while entity resolution runs outside it.
 *
 * <p><b>Why that split matters.</b> Entity rows are the hottest contended rows
 * in the system: every document in a corpus mentions the same handful of
 * entities, and extraction runs documents concurrently. Resolving endpoints
 * inside the same transaction that then writes triples held those entity locks
 * for the whole write, so a 24-document corpus serialised onto them and most
 * jobs died of {@code Lock wait timeout exceeded}. Resolving first, in short
 * independent transactions, and then writing means the write transaction only
 * ever inserts and never contends.
 *
 * <p>Separate bean so {@code REQUIRES_NEW} actually applies: Spring's advice is
 * proxy-based, and a self-invoked {@code @Transactional} method silently does
 * nothing.
 */
@Service
public class ProposalWriter {

    private static final Logger log = LoggerFactory.getLogger(ProposalWriter.class);

    private final TripleRepository triples;
    private final ClaimRepository claims;
    private final EntityResolutionService entityResolution;
    private final UserService users;

    public ProposalWriter(TripleRepository triples, ClaimRepository claims,
                          EntityResolutionService entityResolution, UserService users) {
        this.triples = triples;
        this.claims = claims;
        this.entityResolution = entityResolution;
        this.users = users;
    }

    public record Persisted(int triples, int claims) {
    }

    /**
     * Writes the proposals for one chunk.
     *
     * <p>Every row written here is PENDING. There is no code path in this class
     * that can set APPROVED or REJECTED; approval is the human workflow's
     * exclusive privilege.
     *
     * @param endpoints the already-resolved entity ids for each triple, in the
     *                  same order as {@code result.triples()}; a {@code null}
     *                  entry means that triple's endpoints were unidentifiable
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Persisted write(Long runId, Document document, DocumentChunk chunk,
                           ExtractionDtos.ExtractionResultDto result,
                           List<ResolvedEndpoints> endpoints) {
        Long corpusId = document.getCorpus().getId();
        User proposer = users.getById(document.getUploader().getId());
        int tripleCount = 0;
        int claimCount = 0;

        List<ExtractionDtos.TripleDto> dtos = result.triples();
        for (int i = 0; i < dtos.size(); i++) {
            ExtractionDtos.TripleDto dto = dtos.get(i);
            ResolvedEndpoints resolved = i < endpoints.size() ? endpoints.get(i) : null;
            if (resolved == null || !resolved.resolved()) {
                // Without identifiable endpoints there is no attributable
                // provenance, so the fact could never be audited. Skip it rather
                // than persist something nobody can later verify.
                log.debug("Skipping triple with unidentifiable endpoint: {} -{}-> {}",
                        dto.subject(), dto.predicate(), dto.object());
                continue;
            }

            String factKey = resolved.subjectKey() + "|" + dto.predicate() + "|" + resolved.objectKey();
            String factHash = Hashing.sha256Hex(factKey);

            // Idempotency: a retried job must not create a second copy of a fact
            // that already exists, which would inflate graph out-degree.
            Optional<Triple> existing = triples.findByCorpusIdAndFactHash(corpusId, factHash);
            if (existing.isPresent()) {
                existing.get().recordAdditionalEvidence(Instant.now());
                triples.save(existing.get());
                tripleCount++;
                continue;
            }

            Entity subject = entityResolution.reference(resolved.subjectId());
            Entity object = entityResolution.reference(resolved.objectId());
            Triple triple = new Triple(document.getCorpus(), subject, dto.subject(), dto.predicate(),
                    object, dto.object(), dto.sentence(), chunk, proposer,
                    Prompts.EXTRACT_V1, runId, factKey, factHash);
            triples.save(triple);
            tripleCount++;
        }

        for (ExtractionDtos.ClaimDto dto : result.claims()) {
            String claimKey = dto.subject() + "|" + dto.claim() + "|" + dto.polarity().name();
            String claimHash = Hashing.sha256Hex(claimKey);
            if (claims.findByCorpusIdAndClaimHash(corpusId, claimHash).isPresent()) {
                claimCount++;
                continue;
            }
            Claim claim = new Claim(document.getCorpus(), dto.subject(), dto.claim(),
                    ClaimPolarity.valueOf(dto.polarity().name()),
                    null, null, dto.sentence(), chunk, proposer,
                    Prompts.EXTRACT_V1, runId, claimKey, claimHash);
            claims.save(claim);
            claimCount++;
        }

        return new Persisted(tripleCount, claimCount);
    }

    /** The resolved endpoints of one proposed triple. */
    public record ResolvedEndpoints(Long subjectId, Long objectId, String subjectKey, String objectKey) {

        public static ResolvedEndpoints unresolved() {
            return new ResolvedEndpoints(null, null, null, null);
        }

        public boolean resolved() {
            return subjectId != null && objectId != null;
        }
    }
}
