package com.prism.corpus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
@RestController
@RequestMapping("/api/corpora")
@Tag(name = "Corpora", description = "Corpus is the isolation boundary for all PRISM data")
public class CorpusController {

    private final CorpusService corpora;
    private final CorpusAccessService access;
    private final CorpusStatisticsService statisticsService;

    public CorpusController(CorpusService corpora, CorpusAccessService access,
                           CorpusStatisticsService statisticsService) {
        this.corpora = corpora;
        this.access = access;
        this.statisticsService = statisticsService;
    }

    public record CorpusRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {
    }

    public record CorpusResponse(Long id, String name, String description, Long ownerId, String ownerUsername,
                                 CorpusStatus status, Instant createdAt, Instant updatedAt) {
        static CorpusResponse from(Corpus c) {
            return new CorpusResponse(c.getId(), c.getName(), c.getDescription(),
                    c.getOwner().getId(), c.getOwner().getUsername(), c.getStatus(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    @GetMapping
    @Operation(summary = "List corpora the caller can access")
    public List<CorpusResponse> list() {
        Long userId = access.requireCurrentUserId();
        return corpora.list(userId).stream().map(CorpusResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST','VERIFIER','ADMIN')")
    @Operation(summary = "Create a corpus owned by the caller")
    public ResponseEntity<CorpusResponse> create(@Valid @RequestBody CorpusRequest request) {
        Long userId = access.requireCurrentUserId();
        Corpus created = corpora.create(userId, request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(CorpusResponse.from(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one corpus; denied unless the caller owns it or is ADMIN")
    public CorpusResponse get(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return CorpusResponse.from(corpora.get(userId, id));
    }

    /**
     * Pipeline counters for one corpus, broken down by status at every stage.
     *
     * <p>Verifier-gated: the pending counts are the operational picture of the
     * human gate, so enumerating how much review work a corpus holds is not
     * something a plain analyst needs.
     */
    @GetMapping("/{id}/statistics")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Count documents, triples, claims, verdicts, contradictions, debates and "
            + "quarantined responses, each broken down by status")
    public CorpusStatisticsService.CorpusStatistics statistics(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        // Authorize first. The statistics service counts; it does not check access,
        // so relying on the caller's ordering here would be a silent hole.
        access.requireAccessible(id, userId);
        return statisticsService.forCorpus(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a corpus (owner only)")
    public CorpusResponse update(@PathVariable Long id, @Valid @RequestBody CorpusRequest request) {
        Long userId = access.requireCurrentUserId();
        return CorpusResponse.from(corpora.update(userId, id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Archive a corpus (owner only). Provenance is preserved, never hard-deleted.")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        corpora.archive(userId, id);
        return ResponseEntity.noContent().build();
    }
}
