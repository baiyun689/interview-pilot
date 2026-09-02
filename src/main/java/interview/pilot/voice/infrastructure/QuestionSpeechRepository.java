package interview.pilot.voice.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface QuestionSpeechRepository extends JpaRepository<QuestionSpeechEntity, Long> {
  Optional<QuestionSpeechEntity> findBySpeechId(UUID speechId);

  Optional<QuestionSpeechEntity> findByTurnId(Long turnId);

  /**
   * Retention sweep (Task 11): every question speech of an expired COMPLETED session. The
   * session_id prefix is covered by the FK index InnoDB maintains for
   * {@code fk_question_speech_session} (V21), so the join-based sweep needs no new index and
   * no expires_at column — the deletion predicate lives on the session row (status + completed
   * at), never on the speech row.
   */
  List<QuestionSpeechEntity> findAllBySessionId(Long sessionId);

  /**
   * PESSIMISTIC_WRITE re-read for the cleanup sweeper's mark and completion-fact transactions
   * (Task 11): the deletion predicate is re-verified under the row lock so a concurrent
   * synthesis completion or a parallel sweep can never race a row deletion.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from QuestionSpeechEntity s where s.id = :id")
  Optional<QuestionSpeechEntity> findByIdForUpdate(@Param("id") Long id);
}
