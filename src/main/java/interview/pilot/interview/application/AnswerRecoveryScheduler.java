package interview.pilot.interview.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;

/** Bounded keyset scan; a broken row cannot starve later answers or stop the sweep. */
@Component
public class AnswerRecoveryScheduler {
  private static final Logger log = LoggerFactory.getLogger(AnswerRecoveryScheduler.class);
  private final AnswerAttemptRepository attempts;
  private final FixedAnswerService answers;
  private final InterviewProcessingSla sla;
  private long afterId;

  public AnswerRecoveryScheduler(AnswerAttemptRepository attempts, FixedAnswerService answers,
      InterviewProcessingSla sla) {
    this.attempts = attempts;
    this.answers = answers;
    this.sla = sla;
  }

  @Scheduled(fixedDelayString = "${app.interview.answer-recovery.interval:30s}",
      initialDelayString = "${app.interview.answer-recovery.initial-delay:1m}")
  public void recover() {
    // Includes the SSE margin so normal work gets its full response window before recovery.
    Instant cutoff = Instant.now().minus(sla.sseTimeout());
    var candidates = attempts.findExpired(cutoff, afterId, PageRequest.of(0, 50));
    if (candidates.isEmpty()) {
      afterId = 0;
      return;
    }
    for (var candidate : candidates) {
      try {
        if (answers.recoverExpired(candidate.getId(), candidate.getSessionId(), cutoff)) {
          log.info("Recovered expired answer attempt {}", candidate.getId());
        }
      } catch (RuntimeException exception) {
        log.warn("Answer recovery failed for attempt {} ({})", candidate.getId(),
            exception.getClass().getSimpleName());
      }
      afterId = candidate.getId();
    }
  }
}
