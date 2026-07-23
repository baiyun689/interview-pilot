package interview.pilot.interview.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "interview_knowledge_base")
@IdClass(InterviewKnowledgeBaseEntity.SessionKnowledgeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewKnowledgeBaseEntity {
  @Id
  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Id
  @Column(name = "knowledge_base_id", nullable = false)
  private Long knowledgeBaseId;

  public static InterviewKnowledgeBaseEntity create(Long sessionId, Long knowledgeBaseId) {
    var entity = new InterviewKnowledgeBaseEntity();
    entity.sessionId = sessionId;
    entity.knowledgeBaseId = knowledgeBaseId;
    return entity;
  }

  @NoArgsConstructor
  public static class SessionKnowledgeId implements Serializable {
    private Long sessionId;
    private Long knowledgeBaseId;

    public SessionKnowledgeId(Long sessionId, Long knowledgeBaseId) {
      this.sessionId = sessionId;
      this.knowledgeBaseId = knowledgeBaseId;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof SessionKnowledgeId other)) return false;
      return Objects.equals(sessionId, other.sessionId)
          && Objects.equals(knowledgeBaseId, other.knowledgeBaseId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(sessionId, knowledgeBaseId);
    }
  }
}
