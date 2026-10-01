package com.prism.claims;

import com.prism.knowledge.EntityNormalizer;
import com.prism.knowledge.Triple;
import com.prism.knowledge.TripleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves which approved triples qualify for the graph's VERIFIED_ONLY scope.
 *
 * <p>The rule, stated precisely: a triple is "verified" when an approved claim
 * about the same subject and predicate carries a SUPPORTED verdict that no human
 * has overridden to something else.
 *
 * <p>It is a proxy for the stronger condition, and deliberately so. PRISM
 * currently does not record a direct claim-to-triple foreign key — the
 * connection runs through shared subject and predicate. That is a real
 * limitation, documented rather than papered over: a claim about
 * "Aster Labs funds X" and a triple about "Aster Labs funds Y" will both match
 * the same predicate. Narrowing this is future work; the current behaviour is
 * at least conservative in the right direction, because it never adds a triple
 * that no SUPPORTED verdict speaks to.
 */
@Service
public class VerifiedGraphScope {

    private final VerdictRepository verdicts;
    private final TripleRepository triples;

    public VerifiedGraphScope(VerdictRepository verdicts, TripleRepository triples) {
        this.verdicts = verdicts;
        this.triples = triples;
    }

    /**
     * @return triple ids that satisfy the verified-only predicate
     */
    @Transactional(readOnly = true)
    public Set<Long> verifiedTripleIds(Long corpusId) {
        Set<String> supportedRelations = new HashSet<>();
        for (Verdict verdict : verdicts.findByCorpusIdAndVerdictType(corpusId, VerdictType.SUPPORTED)) {
            // A human override to a different verdict removes the support even
            // though the machine originally said yes. The human is the authority.
            if (verdict.getAdjudicationState() == AdjudicationState.HUMAN_DECISION
                    && verdict.getHumanVerdictType() != null
                    && verdict.getHumanVerdictType() != VerdictType.SUPPORTED) {
                continue;
            }
            var claim = verdict.getClaim();
            if (claim.getPredicate() == null) {
                continue;
            }
            supportedRelations.add(EntityNormalizer.normalizeKey(claim.getSubject())
                    + "|" + claim.getPredicate().toLowerCase(Locale.ROOT));
        }

        if (supportedRelations.isEmpty()) {
            return Set.of();
        }

        Set<Long> verified = new HashSet<>();
        List<Triple> approved = triples.findApprovedInCorpus(corpusId);
        for (Triple triple : approved) {
            String key = EntityNormalizer.normalizeKey(triple.getSubject())
                    + "|" + triple.getPredicate().toLowerCase(Locale.ROOT);
            if (supportedRelations.contains(key)) {
                verified.add(triple.getId());
            }
        }
        return verified;
    }
}
