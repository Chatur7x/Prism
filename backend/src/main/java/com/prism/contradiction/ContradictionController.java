package com.prism.contradiction;

import com.prism.common.error.ApiException;
import com.prism.corpus.Corpus;
import com.prism.corpus.CorpusAccessService;
import com.prism.debate.Debate;
import com.prism.debate.DebateResponseAssembler;
import com.prism.debate.DebateService;
import com.prism.synthesis.BlockType;
import com.prism.synthesis.ReportBlock;
import com.prism.synthesis.ReportBlockCitation;
import com.prism.synthesis.SynthesisReport;
import com.prism.synthesis.SynthesisReportResponse;
import com.prism.synthesis.SynthesisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contradictions and the Council that resolves them. */
@RestController
@RequestMapping("/api")
@Tag(name = "Contradictions and Council", description = "Detected conflicts, debates, and synthesis")
public class ContradictionController {

    private final ContradictionRepository contradictions;
    private final ContradictionScanService scan;
    private final DebateService debates;
    private final SynthesisService synthesis;
    private final CorpusAccessService access;

    public ContradictionController(ContradictionRepository contradictions,
                                   ContradictionScanService scan, DebateService debates,
                                   SynthesisService synthesis, CorpusAccessService access) {
        this.contradictions = contradictions;
        this.scan = scan;
        this.debates = debates;
        this.synthesis = synthesis;
        this.access = access;
    }

    public record ConveneRequest(@Size(max = 500) String topic) {
    }

    public record ContradictionResponse(Long id, Long corpusId, String contradictionType,
                                        String subjectText, String predicate,
                                        String leftDescription, String rightDescription,
                                        String ruleCode, String ruleVersion, String explanation,
                                        ContradictionStatus status, Long leftTripleId,
                                        Long rightTripleId, Long leftClaimId, Long rightClaimId,
                                        Long debateId, Instant createdAt, Instant updatedAt) {
    }

    private ContradictionResponse toResponse(Contradiction c) {
        Long debateId = debates.findByContradiction(c.getId()).map(Debate::getId).orElse(null);
        return new ContradictionResponse(c.getId(), c.getCorpus().getId(), c.getContradictionType(),
                c.getSubjectText(), c.getPredicate(), c.getLeftDescription(), c.getRightDescription(),
                c.getRuleCode(), c.getRuleVersion(), c.getExplanation(), c.getStatus(),
                c.getLeftTripleId(), c.getRightTripleId(), c.getLeftClaimId(), c.getRightClaimId(),
                debateId, c.getCreatedAt(), c.getUpdatedAt());
    }

    @GetMapping("/contradictions")
    @Operation(summary = "List detected contradictions in a corpus")
    public Map<String, Object> list(@RequestParam Long corpusId,
                                    @RequestParam(required = false) ContradictionStatus status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        Long userId = access.requireCurrentUserId();
        access.requireAccessible(corpusId, userId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = status == null
                ? contradictions.findByCorpusIdOrderByCreatedAtDesc(corpusId, pageable)
                : contradictions.findByCorpusIdAndStatusOrderByCreatedAtDesc(corpusId, status, pageable);
        return Map.of("content", result.getContent().stream().map(this::toResponse).toList(),
                "total", result.getTotalElements(), "page", result.getNumber(), "size", result.getSize());
    }

    @GetMapping("/contradictions/{id}")
    @Operation(summary = "Fetch one contradiction with the rule that produced it")
    public ContradictionResponse get(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return toResponse(accessible(id, userId));
    }

    /**
     * Loads a contradiction and authorizes the caller against the corpus that
     * owns it.
     *
     * <p>Deliberately two steps rather than a single
     * {@code findByIdAndCorpusId(id, userId)}. That method looks like it
     * performs the isolation check, but its second parameter is a
     * <em>corpus</em> id, so passing the user id there compares two unrelated
     * identifiers. It fails closed for most callers, which hides the mistake,
     * and in the case where a user's id happened to equal some other corpus's
     * id it would authorize a read across the isolation boundary. Reading the
     * owning corpus from the row and then calling the single corpus-isolation
     * authority is both correct and impossible to misread.
     */
    private Contradiction accessible(Long id, Long userId) {
        Contradiction c = contradictions.findById(id)
                .orElseThrow(() -> ApiException.notFound("Contradiction", id));
        access.requireAccessible(c.getCorpus().getId(), userId);
        return c;
    }

    @PostMapping("/contradictions/scan")
    @Operation(summary = "Re-run deterministic contradiction detection over a corpus. Idempotent.")
    public ContradictionScanService.ScanResult scan(@RequestParam Long corpusId) {
        Long userId = access.requireCurrentUserId();
        // The resolved Corpus is handed to the service rather than the raw id, so
        // the scan can never be invoked for a corpus the caller cannot reach.
        Corpus corpus = access.requireActiveAccessible(corpusId, userId);
        return scan.scanAuthorized(corpus, null);
    }

    /**
     * Convenes a Council over an OPEN contradiction.
     *
     * <p>Returns the same {@code DebateResponse} every other debate endpoint
     * returns, not the raw {@link Debate} entity. Convening used to serialise the
     * entity directly, so its response had {@code chairedBy} as a nested object,
     * no {@code rounds}, and no {@code stateDescription} -- a client had to
     * handle two shapes for one resource. The assembler is shared with
     * {@code DebateController} so the two cannot drift apart again.
     */
    @PostMapping("/contradictions/{id}/debate")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Convene a Council over an OPEN contradiction")
    public DebateResponseAssembler.DebateResponse convene(@PathVariable Long id,
                                                          @Valid @RequestBody(required = false) ConveneRequest request) {
        Long userId = access.requireCurrentUserId();
        Debate debate = debates.convene(userId, id, request == null ? null : request.topic());
        // Convening returns a freshly built entity, so there are no rounds,
        // weights, or citations to read back yet. The assembler handles the empty
        // case and resolves the chair from the in-memory association.
        return DebateResponseAssembler.assemble(debate, List.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    @PostMapping("/contradictions/{id}/dismiss")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Dismiss a contradiction as not a genuine conflict")
    public ContradictionResponse dismiss(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Contradiction c = accessible(id, userId);
        access.requireVerifier(userId);
        if (c.getStatus() == ContradictionStatus.IN_DEBATE) {
            throw ApiException.stateConflict(
                    "a debate is in progress; abort it before dismissing");
        }
        c.markDismissed(Instant.now());
        contradictions.save(c);
        return toResponse(c);
    }

    // ---- synthesis report --------------------------------------------------

    /**
     * The synthesis report, with every block's citations.
     *
     * <p>Typed via {@link SynthesisReportResponse} rather than assembled as an
     * untyped map. The map version published only four of a citation's six
     * target fields, so a citation into a triple, claim, or verdict came back
     * looking empty — and because an untyped map renders in OpenAPI as a bare
     * {@code object}, no client could have discovered the omission from the
     * schema either.
     */
    @GetMapping("/debates/{id}/report")
    @Operation(summary = "The structured synthesis report, with per-block citations")
    public SynthesisReportResponse.ReportResponse report(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        SynthesisReport report = synthesis.reportFor(userId, id);
        List<ReportBlock> blocks = synthesis.blocksOf(report.getId());
        List<ReportBlockCitation> citations = synthesis.citationsOf(report.getId());

        Map<Long, List<ReportBlockCitation>> byBlock = new LinkedHashMap<>();
        for (ReportBlockCitation c : citations) {
            byBlock.computeIfAbsent(c.getBlock().getId(), k -> new java.util.ArrayList<>()).add(c);
        }
        return SynthesisReportResponse.assemble(report, blocks, byBlock);
    }
}
