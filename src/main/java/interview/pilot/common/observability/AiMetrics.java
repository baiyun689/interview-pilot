package interview.pilot.common.observability;

import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AiMetrics {
  private final MeterRegistry meters;

  public AiMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  public void aiCall(String provider, String outcome, Duration latency) {
    String safeOutcome = "success".equals(outcome) ? "success" : "failure";
    String safeProvider = boundedProvider(provider);
    meters.counter("interview_pilot.ai.calls", "provider", safeProvider,
        "outcome", safeOutcome).increment();
    meters.timer("interview_pilot.ai.latency", "provider", safeProvider,
        "outcome", safeOutcome).record(latency);
  }

  public void structuredRetry(String provider) {
    meters.counter("interview_pilot.ai.structured_retries",
        "provider", boundedProvider(provider)).increment();
  }

  public void taskCompleted(AsyncTaskType type) {
    meters.counter("interview_pilot.tasks.completed", "task_type", taskType(type)).increment();
  }

  public void taskFailed(AsyncTaskType type, String status) {
    String safeStatus = "dead".equalsIgnoreCase(status) ? "dead" : "failed";
    meters.counter("interview_pilot.tasks.failed", "task_type", taskType(type),
        "status", safeStatus).increment();
  }

  public void answerDuplicate(String category) {
    String safe = switch (category == null ? "" : category.toLowerCase(Locale.ROOT)) {
      case "replay" -> "replay";
      default -> "conflict";
    };
    meters.counter("interview_pilot.answers.duplicates", "category", safe).increment();
  }

  public void optimisticLockConflict() {
    meters.counter("interview_pilot.optimistic_lock.conflicts").increment();
  }

  public void knowledgeIndexDuration(Duration latency, int chunkCount) {
    meters.timer("interview_pilot.knowledge.index.duration").record(latency);
    meters.counter("interview_pilot.knowledge.index.chunks").increment(chunkCount);
  }

  public void knowledgeRetrievalDuration(String status, Duration latency, int matchCount) {
    String safe = switch (status) {
      case "RETRIEVED" -> "retrieved";
      case "NO_MATCH" -> "no_match";
      default -> "unavailable";
    };
    meters.timer("interview_pilot.knowledge.retrieval.duration", "status", safe).record(latency);
    meters.counter("interview_pilot.knowledge.retrieval.matches", "status", safe)
        .increment(matchCount);
  }

  public void interviewRagQuestion(String status) {
    meters.counter("interview_pilot.interview.rag.question", "status",
        status.toLowerCase(Locale.ROOT)).increment();
  }

  public void interviewRagFallback(String reason) {
    meters.counter("interview_pilot.interview.rag.fallback", "reason",
        reason).increment();
  }

  public void interviewGrounding(
      String skillId, String competency, String status, int injectedCharacters,
      int citationCount) {
    String safeSkill = boundedDimension(skillId);
    String safeCompetency = boundedDimension(competency);
    String safeStatus = boundedDimension(status);
    meters.counter("interview_pilot.interview.grounding.turns",
        "skill", safeSkill, "competency", safeCompetency, "status", safeStatus).increment();
    meters.summary("interview_pilot.interview.grounding.injected_characters",
        "skill", safeSkill, "status", safeStatus).record(Math.max(0, injectedCharacters));
    meters.counter("interview_pilot.interview.grounding.citations",
        "skill", safeSkill, "competency", safeCompetency)
        .increment(Math.max(0, citationCount));
    if (!"retrieved".equals(safeStatus)) {
      meters.counter("interview_pilot.interview.grounding.degraded",
          "skill", safeSkill, "status", safeStatus).increment();
    }
  }

  public void afterCommit(Runnable recording) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          recording.run();
        }
      });
    } else {
      recording.run();
    }
  }

  private String taskType(AsyncTaskType type) {
    return type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT);
  }

  private String boundedProvider(String provider) {
    if (provider == null || provider.isBlank()) return "default";
    return provider.length() <= 64 && provider.matches("[A-Za-z0-9_-]+")
        ? provider.toLowerCase(Locale.ROOT) : "other";
  }

  private String boundedDimension(String value) {
    if (value == null || value.isBlank()) return "unknown";
    String normalized = value.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_-]", "_");
    return normalized.length() <= 64 ? normalized : "other";
  }
}
