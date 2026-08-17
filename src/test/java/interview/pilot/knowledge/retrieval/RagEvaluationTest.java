package interview.pilot.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import interview.pilot.common.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RagEvaluationTest {
  @Test
  void versionedEvaluationSetMeetsRecallMrrAndNoMatchTargets() throws Exception {
    InputStream stream = getClass().getResourceAsStream("/rag-evaluation/v1.json");
    Suite suite = new ObjectMapper().readValue(stream, Suite.class);
    KnowledgeRanker ranker = new KnowledgeRanker();
    int retrievedCases = 0;
    int hits = 0;
    double reciprocalRanks = 0;
    int noMatchCorrect = 0;
    int noMatchCases = 0;

    for (Case evaluationCase : suite.cases()) {
      List<KnowledgeChunk> candidates = evaluationCase.candidates().stream()
          .map(candidate -> new KnowledgeChunk(
              candidate.id(), UUID.nameUUIDFromBytes(candidate.id().getBytes()), "eval.md",
              1, 0, evaluationCase.id(), candidate.score(), candidate.content(), null))
          .toList();
      List<KnowledgeChunk> ranked = ranker.rank(candidates, 3, 0.72, 2_000);
      if (evaluationCase.expectNoMatch()) {
        noMatchCases++;
        if (ranked.isEmpty()) noMatchCorrect++;
        continue;
      }
      retrievedCases++;
      int firstRelevant = -1;
      for (int index = 0; index < ranked.size(); index++) {
        if (evaluationCase.relevantIds().contains(ranked.get(index).pointId())) {
          if (firstRelevant < 0) firstRelevant = index;
        }
      }
      if (firstRelevant >= 0) {
        hits++;
        reciprocalRanks += 1.0 / (firstRelevant + 1);
      }
    }

    double recallAtK = (double) hits / retrievedCases;
    double mrr = reciprocalRanks / retrievedCases;
    double noMatchAccuracy = (double) noMatchCorrect / noMatchCases;
    var meters = new SimpleMeterRegistry();
    new AiMetrics(meters).ragEvaluation(suite.version(), recallAtK, mrr, noMatchAccuracy);

    assertThat(suite.version()).isEqualTo(1);
    assertThat(recallAtK).isGreaterThanOrEqualTo(0.9);
    assertThat(mrr).isGreaterThanOrEqualTo(0.9);
    assertThat(noMatchAccuracy).isEqualTo(1.0);
    assertThat(meters.get("interview_pilot.knowledge.evaluation.recall_at_k").gauge().value())
        .isEqualTo(recallAtK);
  }

  record Suite(int version, List<Case> cases) {}
  record Case(
      String id, boolean expectNoMatch, List<String> relevantIds,
      List<Candidate> candidates) {}
  record Candidate(String id, double score, String content) {}
}
