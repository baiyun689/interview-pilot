package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.grounding.KnowledgeRole;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.RetrievalIntent;

@Component
public class DefaultPhaseRagRetriever implements PhaseRagRetriever {
  private final KnowledgeRetriever retriever;
  private final KnowledgeProperties properties;

  public DefaultPhaseRagRetriever(KnowledgeRetriever retriever, KnowledgeProperties properties) {
    this.retriever = retriever;
    this.properties = properties;
  }

  @Override
  public RagContextSnapshot retrieve(InterviewBriefSnapshot brief, InterviewPhase phase) {
    if (brief.knowledgeScope() == null) {
      return new RagContextSnapshot(RagStatus.NOT_REQUESTED, query(brief, phase), "", List.of(), null);
    }
    String query = query(brief, phase);
    try {
      int topK = Math.min(6, properties.topK());
      var result = retriever.retrieve(brief.knowledgeScope(), new RetrievalIntent(
          query, phase.name(), brief.difficulty().name(), keywords(brief, phase), List.of(),
          topK, Math.max(topK, properties.candidateCount()),
          properties.similarityThreshold(), properties.contextCharacterBudget()));
      RagStatus status = switch (result.status()) {
        case RETRIEVED -> RagStatus.RETRIEVED;
        case NO_MATCH -> RagStatus.NO_MATCH;
        case UNAVAILABLE -> RagStatus.UNAVAILABLE;
      };
      var chunks = result.chunks().stream().limit(6).map(chunk ->
          new RagContextSnapshot.Chunk(
              chunk.pointId(), chunk.documentId(), chunk.filename(), chunk.documentRevision(),
              chunk.chunkIndex(), KnowledgeRole.TECHNICAL_REFERENCE, chunk.section(),
              chunk.pageNumber(), chunk.score(), chunk.content())).toList();
      return new RagContextSnapshot(
          status, result.query(), result.embeddingModel(), chunks,
          status == RagStatus.UNAVAILABLE ? "RAG_UNAVAILABLE" : null);
    } catch (RuntimeException exception) {
      return new RagContextSnapshot(
          RagStatus.UNAVAILABLE, query, "", List.of(), "RAG_UNAVAILABLE");
    }
  }

  private String query(InterviewBriefSnapshot brief, InterviewPhase phase) {
    String fixed = switch (phase) {
      case FUNDAMENTALS -> "Java JVM 并发 Spring MySQL Redis 消息队列 分布式基础";
      case PROJECT_EXPERIENCE -> "项目职责 核心实现 技术选择 数据流 指标 故障 个人贡献";
      case SCENARIO_TRADEOFF -> "系统约束 故障 容量 降级 一致性 演进 取舍 可观测性";
      case SELF_INTRODUCTION -> "";
    };
    var terms = new ArrayList<String>();
    terms.add(fixed);
    terms.add(brief.jobTitle());
    terms.add(clip(brief.jobDescription(), 3_000));
    terms.addAll(brief.resume().technicalSkills());
    brief.resume().projects().forEach(project -> {
      terms.add(project.name());
      terms.addAll(project.technologies());
    });
    terms.add(brief.difficulty().name());
    return String.join(" ", terms).trim();
  }

  private String clip(String value, int maximum) {
    return value.length() <= maximum ? value : value.substring(0, maximum);
  }

  private List<String> keywords(InterviewBriefSnapshot brief, InterviewPhase phase) {
    var keywords = new ArrayList<String>();
    keywords.add(phase.name());
    keywords.add(brief.jobTitle());
    keywords.addAll(brief.resume().technicalSkills());
    return keywords.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
  }
}
