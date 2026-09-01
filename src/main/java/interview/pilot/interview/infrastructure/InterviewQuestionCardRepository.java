package interview.pilot.interview.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import interview.pilot.interview.domain.InterviewPhase;

public interface InterviewQuestionCardRepository
    extends JpaRepository<InterviewQuestionCardEntity, Long> {
  List<InterviewQuestionCardEntity> findAllBySessionIdOrderByPhaseAscPhaseSequenceAsc(Long sessionId);
  List<InterviewQuestionCardEntity> findAllBySessionIdAndPhaseOrderByPhaseSequenceAsc(
      Long sessionId, InterviewPhase phase);
  Optional<InterviewQuestionCardEntity> findBySessionIdAndPhaseAndPhaseSequence(
      Long sessionId, InterviewPhase phase, int phaseSequence);
  long countBySessionId(Long sessionId);
  void deleteAllBySessionId(Long sessionId);
}
