package com.prism.debate;

import com.prism.corpus.CorpusAccessService;
import com.prism.synthesis.SynthesisReport;
import com.prism.synthesis.SynthesisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Council: debate lifecycle, chair weighting, live streaming, and synthesis. */
@RestController
@RequestMapping("/api/debates")
@Tag(name = "Council", description = "Multi-persona debate as a deterministic state machine")
public class DebateController {

    private final DebateService debates;
    private final SynthesisService synthesis;
    private final DebateEventBroker broker;
    private final CorpusAccessService access;

    public DebateController(DebateService debates, SynthesisService synthesis,
                            DebateEventBroker broker, CorpusAccessService access) {
        this.debates = debates;
        this.synthesis = synthesis;
        this.broker = broker;
        this.access = access;
    }

    public record WeightRequest(
            @NotNull @Min(DebateEngine.MIN_WEIGHT) @Max(DebateEngine.MAX_WEIGHT) Integer weight,
            @Size(max = 1000) String note) {
    }


    @GetMapping("/{id}")
    @Operation(summary = "Full debate state, rounds, arguments, weights, and citations")
    public DebateResponseAssembler.DebateResponse get(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        Debate debate = debates.loadForUser(id, userId);
        return toResponse(debate);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Start the debate and run round 1's personas")
    public DebateResponseAssembler.DebateResponse start(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return toResponse(debates.start(userId, id));
    }

    @PostMapping("/{id}/abort")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Abandon the debate; the contradiction returns to OPEN")
    public DebateResponseAssembler.DebateResponse abort(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return toResponse(debates.abort(userId, id));
    }

    @PostMapping("/{id}/arguments/{argumentId}/weight")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Record a chair weight (1-5). Append-only: revising creates a new audit row.")
    public Map<String, Object> weight(@PathVariable Long id, @PathVariable Long argumentId,
                                      @Valid @RequestBody WeightRequest request) {
        Long userId = access.requireCurrentUserId();
        ArgumentWeight saved = debates.weigh(userId, id, argumentId, request.weight(), request.note());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("weightId", saved.getId());
        body.put("argumentId", argumentId);
        body.put("weight", saved.getWeight());
        body.put("verifier", saved.getVerifier().getUsername());
        body.put("note", saved.getNote());
        body.put("createdAt", saved.getCreatedAt());
        return body;
    }

    @PostMapping("/{id}/advance")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Submit this round's weights and open the next round, or move to synthesis "
            + "at the ceiling. Concurrent calls yield one success and one 409.")
    public DebateResponseAssembler.DebateResponse advance(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        return toResponse(debates.advance(userId, id));
    }

    @PostMapping("/{id}/synthesize")
    @PreAuthorize("hasAnyRole('VERIFIER','ADMIN')")
    @Operation(summary = "Generate the synthesis report. Idempotent: an existing report is returned.")
    public Map<String, Object> synthesize(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        SynthesisReport report = synthesis.synthesize(userId, id);
        return Map.of("reportId", report.getId(), "debateId", id,
                "createdAt", report.getCreatedAt(), "model", String.valueOf(report.getModel()));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events for this debate: arguments, phase changes, and results. "
            + "Single-instance in-memory broker; not horizontally scalable.")
    public SseEmitter stream(@PathVariable Long id) {
        Long userId = access.requireCurrentUserId();
        debates.loadForUser(id, userId);
        return broker.subscribe(id);
    }

    @GetMapping("/fsm")
    @Operation(summary = "The debate state machine, for the UI to render transitions")
    public Map<String, Object> fsm() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("states", DebateEngine.describeStates());
        body.put("events", List.of("START", "ARGUMENTS_DONE", "WEIGHTS_SUBMITTED",
                "SYNTHESIS_DONE", "ABORT"));
        body.put("maxRounds", DebateEngine.DEFAULT_MAX_ROUNDS);
        body.put("weightRange", Map.of("min", DebateEngine.MIN_WEIGHT, "max", DebateEngine.MAX_WEIGHT));
        body.put("personas", DebateEngine.requiredPersonas().stream().map(Enum::name).sorted().toList());
        return body;
    }

    /**
     * Assembles a debate for the wire.
     *
     * <p>Delegated to {@link DebateResponseAssembler} so this controller and
     * the contradiction controller cannot drift into returning two different
     * shapes for the same resource.
     */
    private DebateResponseAssembler.DebateResponse toResponse(Debate debate) {
        Map<Long, Integer> weightByArgument = new LinkedHashMap<>();
        Map<Long, String> weightedBy = new LinkedHashMap<>();
        for (ArgumentWeight w : debates.latestWeights(debate.getId())) {
            weightByArgument.put(w.getArgument().getId(), w.getWeight());
            weightedBy.put(w.getArgument().getId(), w.getVerifier().getUsername());
        }
        Map<Long, List<DebateResponseAssembler.ArgumentCitationResponse>> citationsByArgument =
                new LinkedHashMap<>();
        for (var citation : debates.citationsOf(debate.getId())) {
            citationsByArgument.computeIfAbsent(citation.getArgument().getId(),
                    k -> new ArrayList<>()).add(DebateResponseAssembler.citationRow(citation));
        }
        Map<Long, List<Argument>> byRound = new LinkedHashMap<>();
        for (Argument a : debates.argumentsOf(debate.getId())) {
            byRound.computeIfAbsent(a.getDebateRound().getId(), k -> new ArrayList<>()).add(a);
        }

        return DebateResponseAssembler.assemble(debate, debates.roundsOf(debate.getId()), byRound,
                weightByArgument, weightedBy, citationsByArgument);
    }
}
