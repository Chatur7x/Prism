package com.prism.debate;

import com.prism.claims.Claim;
import com.prism.claims.ClaimRepository;
import com.prism.claims.VerdictRepository;
import com.prism.claims.VerdictType;
import com.prism.corpus.CorpusAccessService;
import com.prism.graph.GraphService;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import com.prism.trace.ActorType;
import com.prism.trace.TraceEventType;
import com.prism.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the Skeptic brief from live database state.
 *
 * <p>Every figure here is read at build time and nothing is synthesised. If the
 * verification record is empty, the brief says so — the Skeptic is told the
 * record is silent rather than being handed a plausible paragraph.
 *
 * <p>Existing verdicts are <b>reused</b>, never recomputed. Recomputing would
 * mean an LLM call per debate round per claim, and it would mean the Skeptic
 * sees a different verdict each round than the one persisted against the claim.
 */
@Service
public class SkepticBriefBuilder {

    private static final Logger log = LoggerFactory.getLogger(SkepticBriefBuilder.class);

    private static final int MAX_PAGERANK_FACTS = 10;
    private static final int MAX_COMMUNITY_FACTS = 10;
    private static final int MAX_VERDICT_FACTS = 20;

    private final ClaimRepository claims;
    private final VerdictRepository verdicts;
    private final TripleRepository triples;
    private final GraphService graphService;
    private final CorpusAccessService access;
    private final TraceRecorder traces;

    public SkepticBriefBuilder(ClaimRepository claims, VerdictRepository verdicts,
                               TripleRepository triples, GraphService graphService,
                               CorpusAccessService access, TraceRecorder traces) {
        this.claims = claims;
        this.verdicts = verdicts;
        this.triples = triples;
        this.graphService = graphService;
        this.access = access;
        this.traces = traces;
    }

    public record BriefResult(SkepticBrief brief, String rendered) {
    }

    /**
     * Assembles the brief for a contradiction.
     *
     * @param traceRunId trace run to record the brief under; may be null
     */
    @Transactional(readOnly = true)
    public BriefResult build(Long userId, Long corpusId, com.prism.contradiction.Contradiction contradiction,
                             Long traceRunId) {
        access.requireAccessible(corpusId, userId);
        SkepticBrief brief = new SkepticBrief();
        brief.setSubject(buildSubject(contradiction));

        // ---- 1. claims implicated by the contradiction ----
        List<Long> claimIds = new ArrayList<>();
        addIfPresent(claimIds, contradiction.getLeftClaimId());
        addIfPresent(claimIds, contradiction.getRightClaimId());

        // Include claims about the same subject, so the Skeptic can see whether
        // the record is silent or merely narrow.
        claims.findApprovedInCorpus(corpusId).stream()
                .filter(c -> c.getSubject().equalsIgnoreCase(contradiction.getSubjectText()))
                .limit(10)
                .forEach(c -> addIfPresent(claimIds, c.getId()));

        // ---- 2. live verdicts for those claims ----
        List<com.prism.claims.Verdict> liveVerdicts =
                claimIds.isEmpty() ? List.of() : verdicts.findByClaimIds(distinct(claimIds));
        for (com.prism.claims.Verdict verdict : liveVerdicts) {
            Claim claim = verdict.getClaim();
            brief.addVerdict(claim.getId(), verdict.getId(), verdict.getVerdictType().name(),
                    verdict.getEvidenceStatus().name(),
                    verdict.getFusedScore() == null ? null : verdict.getFusedScore().doubleValue(),
                    verdict.getVerdictReason());
        }
        if (claimIds.isEmpty() || liveVerdicts.isEmpty()) {
            brief.addGap("No verification record exists for the claims implicated in this "
                    + "contradiction. Claims " + claimIds + " have not been verified.");
        }

        // ---- 3. the approved triples that created the conflict ----
        List<Triple> implicated = new ArrayList<>();
        if (contradiction.getLeftTripleId() != null) {
            triples.findById(contradiction.getLeftTripleId()).ifPresent(implicated::add);
        }
        if (contradiction.getRightTripleId() != null) {
            triples.findById(contradiction.getRightTripleId()).ifPresent(implicated::add);
        }
        for (Triple t : implicated) {
            brief.addTriple(t.getId(), t.getSubject(), t.getPredicate(), t.getObject(),
                    t.getStatus().name());
        }
        if (implicated.isEmpty()) {
            brief.addGap("The approved triples that produced this conflict are no longer present "
                    + "in the corpus.");
        }

        // ---- 4. PageRank over the approved graph ----
        List<Map.Entry<Long, Double>> ranked = graphService
                .pagerank(userId, corpusId, GraphService.Scope.ALL_APPROVED);
        if (ranked.isEmpty()) {
            brief.addGap("The approved graph has no nodes, so no centrality figures exist.");
        } else {
            KnowledgeGraphView view = loadGraphView(userId, corpusId, ranked);
            int position = 1;
            for (Map.Entry<Long, Double> entry : ranked) {
                if (position > MAX_PAGERANK_FACTS) {
                    break;
                }
                brief.addPageRank(entry.getKey(), view.nameOf(entry.getKey()),
                        entry.getValue(), position);
                position++;
            }
        }

        // ---- 5. community structure ----
        Map<Integer, List<Long>> communities = graphService
                .communitiesFor(userId, corpusId, GraphService.Scope.ALL_APPROVED);
        if (communities.isEmpty()) {
            brief.addGap("No communities could be computed: the approved graph is empty.");
        } else {
            KnowledgeGraphView view = loadGraphView(userId, corpusId, ranked);
            int emitted = 0;
            for (Map.Entry<Integer, List<Long>> entry : communities.entrySet()) {
                for (Long member : entry.getValue()) {
                    if (emitted >= MAX_COMMUNITY_FACTS) {
                        break;
                    }
                    brief.addCommunity(member, view.nameOf(member), entry.getKey(),
                            entry.getValue().size());
                    emitted++;
                }
            }
            brief.add("COM-TOTAL", "COMMUNITY_SUMMARY",
                    "The approved graph resolves into " + communities.size()
                            + " communities determined by deterministic label propagation.", null);
        }

        String rendered = brief.render();

        if (traceRunId != null) {
            traces.record(traceRunId, null, ActorType.ENGINE, TraceEventType.SKEPTIC_BRIEF_CREATED,
                    "Skeptic machine evidence assembled",
                    TraceRecorder.StepPayload.builder()
                            .inputSummary(brief.factCount() + " machine facts from live database state")
                            .outputSummary(rendered)
                            .ruleVersion("SKEPTIC_BRIEF_V1")
                            .build());
        }
        log.debug("Skeptic brief for contradiction {}: {} facts", contradiction.getId(), brief.factCount());
        return new BriefResult(brief, rendered);
    }

    /** Resolves entity display names for the ranked nodes. */
    private KnowledgeGraphView loadGraphView(Long userId, Long corpusId,
                                             List<Map.Entry<Long, Double>> ranked) {
        var graph = graphService.buildGraph(userId, corpusId, GraphService.Scope.ALL_APPROVED, Set.of());
        return new KnowledgeGraphView(graph);
    }

    private record KnowledgeGraphView(com.prism.graph.KnowledgeGraph graph) {
        String nameOf(Long entityId) {
            return graph.nameOf(entityId);
        }
    }

    private String buildSubject(com.prism.contradiction.Contradiction contradiction) {
        return contradiction.getSubjectText()
                + (contradiction.getPredicate() == null ? "" : " " + contradiction.getPredicate())
                + "\nPosition A: " + contradiction.getLeftDescription()
                + "\nPosition B: " + contradiction.getRightDescription()
                + "\nDetected by rule " + contradiction.getRuleCode()
                + " (" + contradiction.getRuleVersion() + "): " + contradiction.getExplanation();
    }

    private static void addIfPresent(List<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }

    private static List<Long> distinct(List<Long> values) {
        return new java.util.LinkedHashSet<>(values).stream().toList();
    }
}
