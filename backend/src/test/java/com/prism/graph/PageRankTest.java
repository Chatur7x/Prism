package com.prism.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRankTest {

    @Test
    @DisplayName("an empty graph yields no scores rather than dividing by zero")
    void emptyGraph() {
        assertThat(new PageRank().compute(new KnowledgeGraph())).isEmpty();
    }

    @Test
    @DisplayName("a single node scores 1.0")
    void singleNode() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addNode(1L, "only");
        Map<Long, Double> scores = new PageRank().compute(g);
        assertThat(scores).containsExactly(org.assertj.core.api.Assertions.entry(1L, 1.0));
    }

    @Test
    @DisplayName("a symmetric two-node graph splits evenly and deterministically")
    void symmetricPair() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 2L, "controls");
        g.addEdge(2L, 1L, "controls");
        Map<Long, Double> scores = new PageRank().compute(g);
        assertThat(scores.get(1L)).isEqualTo(0.5);
        assertThat(scores.get(2L)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the hub in a 1->2->3 chain scores highest")
    void chainOrdering() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 2L, "controls");
        g.addEdge(2L, 3L, "controls");
        List<Map.Entry<Long, Double>> ranked = new PageRank().ranked(g);
        assertThat(ranked.get(0).getKey()).isEqualTo(3L);
        assertThat(ranked.get(2).getKey()).isEqualTo(1L);
        assertThat(ranked.get(0).getValue()).isGreaterThan(ranked.get(2).getValue());
    }

    @Test
    @DisplayName("scores always sum to 1.0, including with dangling nodes")
    void scoresSumToOne() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 2L, "controls");
        g.addEdge(2L, 3L, "controls");
        g.addNode(99L, "isolated"); // dangling: no out-edges
        double total = new PageRank().compute(g).values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(total).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("identical input yields byte-identical output across runs")
    void deterministic() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 2L, "controls");
        g.addEdge(2L, 3L, "controls");
        g.addEdge(3L, 4L, "controls");
        g.addEdge(1L, 3L, "funds");
        PageRank pr = new PageRank();
        assertThat(pr.compute(g)).isEqualTo(pr.compute(g));
    }

    @Test
    @DisplayName("node insertion order does not change the result")
    void insertionOrderIndependent() {
        KnowledgeGraph a = new KnowledgeGraph();
        a.addEdge(1L, 2L, "controls");
        a.addEdge(2L, 3L, "controls");
        a.addEdge(3L, 1L, "controls");

        KnowledgeGraph b = new KnowledgeGraph();
        b.addEdge(3L, 1L, "controls");
        b.addEdge(2L, 3L, "controls");
        b.addEdge(1L, 2L, "controls");

        assertThat(new PageRank().compute(a)).isEqualTo(new PageRank().compute(b));
    }

    @Test
    @DisplayName("duplicate triples on the same relation do not inflate centrality")
    void duplicateEdgesAreCollapsed() {
        KnowledgeGraph withDuplicates = new KnowledgeGraph();
        withDuplicates.addEdge(1L, 2L, "controls");
        withDuplicates.addEdge(1L, 2L, "controls"); // same fact asserted again
        withDuplicates.addEdge(1L, 2L, "controls");
        assertThat(withDuplicates.edgeCount()).isEqualTo(1);

        KnowledgeGraph plain = new KnowledgeGraph();
        plain.addEdge(1L, 2L, "controls");
        assertThat(new PageRank().compute(withDuplicates))
                .isEqualTo(new PageRank().compute(plain));
    }

    @Test
    @DisplayName("different predicates between the same pair are distinct edges")
    void distinctPredicatesAreDistinctEdges() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 2L, "controls");
        g.addEdge(1L, 2L, "funds");
        assertThat(g.edgeCount()).isEqualTo(2);
        assertThat(g.outDegree(1L)).isEqualTo(1);
        assertThat(g.predicatesFrom(1L)).containsExactlyInAnyOrder("controls", "funds");
    }

    @Test
    @DisplayName("self-loops are ignored rather than distorting degrees")
    void selfLoopsIgnored() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 1L, "controls");
        g.addEdge(1L, 2L, "controls");
        assertThat(g.edgeCount()).isEqualTo(1);
        assertThat(g.outDegree(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("higher damping concentrates rank on the hub")
    void dampingAffectsDistribution() {
        KnowledgeGraph g = new KnowledgeGraph();
        g.addEdge(1L, 2L, "controls");
        g.addEdge(2L, 3L, "controls");

        double lowDamping = new PageRank(0.20, 50).compute(g).get(3L);
        double highDamping = new PageRank(0.95, 50).compute(g).get(3L);
        assertThat(highDamping).isGreaterThan(lowDamping);
    }

    @Test
    @DisplayName("more iterations converge rather than oscillate")
    void iterationCountMatters() {
        KnowledgeGraph g = new KnowledgeGraph();
        for (int i = 1; i <= 5; i++) {
            g.addEdge(i, i + 1, "controls");
        }
        double early = new PageRank(0.85, 2).compute(g).get(6L);
        double converged = new PageRank(0.85, 200).compute(g).get(6L);
        assertThat(converged).isGreaterThan(early);
    }

    @Test
    @DisplayName("constructor rejects invalid parameters")
    void validatesParameters() {
        assertThatThrownBy(() -> new PageRank(1.5, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRank(0.85, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
