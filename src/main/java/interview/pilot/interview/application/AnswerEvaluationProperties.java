package interview.pilot.interview.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for asynchronous answer evaluation ({@code app.answer-evaluation.*}).
 *
 * <p>When {@link #enabled} is false, answering creates no ANSWER_EVALUATION task and turns stay
 * NOT_REQUIRED, so the report transparently falls back to its legacy raw-text path. {@code
 * reportBarrierMaxAttempts} bounds how many report attempts wait for pending per-turn evaluations
 * before the report proceeds with whatever evaluations are terminal (bounded wait, never hang).
 */
@Component
@ConfigurationProperties(prefix = "app.answer-evaluation")
public class AnswerEvaluationProperties {
  private boolean enabled = true;
  private int reportBarrierMaxAttempts = 3;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getReportBarrierMaxAttempts() {
    return reportBarrierMaxAttempts;
  }

  public void setReportBarrierMaxAttempts(int reportBarrierMaxAttempts) {
    this.reportBarrierMaxAttempts = Math.max(1, reportBarrierMaxAttempts);
  }
}
