package interview.pilot.interview.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewKnowledgeBaseRepository
    extends JpaRepository<InterviewKnowledgeBaseEntity, InterviewKnowledgeBaseEntity.SessionKnowledgeId> {

  List<InterviewKnowledgeBaseEntity> findAllBySessionId(Long sessionId);

  @Query("""
      select kb.knowledgeBaseId from InterviewKnowledgeBaseEntity kb
      where kb.sessionId = :sessionId
      """)
  List<Long> findKnowledgeBaseIdsBySessionId(@Param("sessionId") Long sessionId);
}
