package interview.pilot.interview.grounding;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.RetrievalIntent;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

@Component
public final class DefaultKnowledgeGrounding implements KnowledgeGrounding {
  private final KnowledgeRetriever retriever;
  private final KnowledgeProperties properties;

  public DefaultKnowledgeGrounding(KnowledgeRetriever retriever, KnowledgeProperties properties) {
    this.retriever = retriever;
    this.properties = properties;
  }

  @Override
  public GroundingSnapshot ground(ValidatedKnowledgeScope scope, GroundingDirective directive) {
    if (!directive.enabled()) return empty(GroundingStatus.DISABLED, "", null);
    if (scope == null) return empty(GroundingStatus.NOT_REQUESTED, "", null);
    String query = query(directive);
    var intent = new RetrievalIntent(
        query, directive.competency(), directive.difficulty().name(),
        directive.triggerKeywords(), directive.coveredTopics(),
        properties.topK(), properties.similarityThreshold());
    final interview.pilot.knowledge.retrieval.RetrievedKnowledge result;
    try {
      result = retriever.retrieve(scope, intent);
    } catch (RuntimeException exception) {
      return empty(GroundingStatus.UNAVAILABLE, query,
          exception.getMessage() == null ? "RETRIEVAL_FAILED" : exception.getMessage());
    }
    GroundingStatus status = switch (result.status()) {
      case RETRIEVED -> GroundingStatus.RETRIEVED;
      case NO_MATCH -> GroundingStatus.NO_MATCH;
      case UNAVAILABLE -> GroundingStatus.UNAVAILABLE;
    };
    return new GroundingSnapshot(status, result.query(), result.embeddingModel(),
        result.chunks().stream().map(chunk -> new GroundingSnapshot.Chunk(
            chunk.pointId(), directive.role(), chunk.documentId(), chunk.documentRevision(),
            chunk.filename(), chunk.chunkIndex(), chunk.section(), chunk.pageNumber(),
            chunk.score(), chunk.content())).toList(), result.failureReason());
  }

  private GroundingSnapshot empty(GroundingStatus status, String query, String failure) {
    return new GroundingSnapshot(status, query, "", List.of(), failure);
  }

  private String query(GroundingDirective directive) {
    List<String> terms = new ArrayList<>();
    terms.add(directive.competency());
    terms.add(directive.questionMode().name().toLowerCase(java.util.Locale.ROOT));
    terms.add(directive.difficulty().name());
    terms.addAll(directive.evidenceGaps());
    if (!directive.probeFocus().isBlank()) terms.add(directive.probeFocus());
    terms.addAll(directive.scopes());
    terms.addAll(directive.triggerKeywords());
    if (!directive.coveredTopics().isEmpty()) {
      terms.add("已覆盖:" + String.join(",", directive.coveredTopics()));
    }
    return String.join(" ", new java.util.LinkedHashSet<>(terms));
  }
}
