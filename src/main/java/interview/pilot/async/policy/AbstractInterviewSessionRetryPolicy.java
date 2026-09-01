package interview.pilot.async.policy;

import java.util.UUID;

import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;

/**
 * Shared manual-retry plumbing of the two interview-session task types (question
 * preparation and evaluation): the same {@code interview:<sessionId>} bizKey parsing and
 * the same owned-by-user session lookup. Each concrete policy keeps its own claim-key
 * prefix and failure-state gate, so the two types can diverge without touching shared code
 * (same pattern as {@link AbstractKnowledgeDocumentRetryPolicy}).
 */
abstract class AbstractInterviewSessionRetryPolicy extends AbstractRetryableTaskPolicy {
  private static final String BIZ_KEY_PREFIX = "interview:";

  private final InterviewSessionRepository sessions;

  protected AbstractInterviewSessionRetryPolicy(InterviewSessionRepository sessions) {
    this.sessions = sessions;
  }

  protected InterviewSessionRepository sessions() {
    return sessions;
  }

  protected UUID parseInterviewId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith(BIZ_KEY_PREFIX)) {
        throw new IllegalArgumentException();
      }
      return UUID.fromString(bizKey.substring(BIZ_KEY_PREFIX.length()));
    } catch (IllegalArgumentException exception) {
      throw stateInvalid();
    }
  }
}
