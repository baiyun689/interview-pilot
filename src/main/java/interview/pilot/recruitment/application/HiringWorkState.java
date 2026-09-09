package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.infrastructure.AssessmentEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import static interview.pilot.recruitment.application.AssessmentModels.*;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.recruitment.infrastructure.HiringStore;
import tools.jackson.databind.ObjectMapper;

/** Durable, fenced execution. Broker delivery and model calls never run inside these transactions. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringWorkState {
  private final HiringStore store;
  private final ObjectMapper json;
  public HiringWorkState(HiringStore store, ObjectMapper json) { this.store = store; this.json = json; }
  public record Claim(Long workId, String token, int epoch, String kind, String inputSnapshot) {}

  public Claim begin(TaskMessage message) {
    if (message == null || message.taskType() != AsyncTaskType.HIRING_WORK) return null;
    var taskReference = store.one(AsyncTaskEntity.class, "from AsyncTaskEntity where taskId=?1", message.taskId()).orElse(null);
    if (taskReference == null || taskReference.getTaskType() != message.taskType()
        || !taskReference.getBizKey().equals(message.bizKey())) return null;
    var reference = store.one(Work.class, "from HiringWork where taskId=?1", taskReference.getId()).orElse(null);
    if (reference == null) return null;
    lockContext(reference);
    var work = store.find(Work.class, reference.id, true).orElseThrow();
    var task = store.find(AsyncTaskEntity.class, work.taskId, true).orElseThrow();
    if (task.getExecutionEpoch() != message.executionEpoch() || !work.status.equals("PENDING")
        || work.nextAttemptAt.isAfter(Instant.now())) return null;
    if (!contextValid(work)) { cancel(work, task); return null; }
    work.status = "RUNNING"; work.leaseToken = UUID.randomUUID().toString();
    work.leaseUntil = Instant.now().plus(Duration.ofMinutes(10)); work.attempts++;
    task.setStatus(AsyncTaskStatus.PUBLISHED); task.setAttemptCount(task.getAttemptCount() + 1);
    return new Claim(work.id, work.leaseToken, task.getExecutionEpoch(), work.kind, work.inputSnapshot);
  }

  public void finish(Claim claim, Object output, String error) {
    var reference = store.find(Work.class, claim.workId(), false).orElseThrow();
    lockContext(reference);
    var work = store.find(Work.class, claim.workId(), true).orElseThrow();
    var task = store.find(AsyncTaskEntity.class, work.taskId, true).orElseThrow();
    if (!work.status.equals("RUNNING") || !claim.token().equals(work.leaseToken)
        || task.getExecutionEpoch() != claim.epoch() || !work.leaseUntil.isAfter(Instant.now())) return;
    if (!contextValid(work)) { cancel(work, task); return; }
    work.leaseToken = null; work.leaseUntil = null;
    if (error == null) {
      work.outputSnapshot = json.writeValueAsString(output); work.status = "COMPLETED"; work.error = null;
      task.setStatus(AsyncTaskStatus.COMPLETED); task.setLastError(null);
      if (work.kind.equals("KNOWLEDGE_DELETE")) {
        var input = json.readValue(work.inputSnapshot, EnterpriseKnowledgeDeleteProcessor.Input.class);
        var document = store.find(interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity.class, input.databaseId(), true).orElseThrow();
        if (document.getIndexRevision() == input.revision()
            && document.getStatus() == interview.pilot.knowledge.domain.KnowledgeDocumentStatus.DELETING) document.markDeleted();
      }
    } else retryOrFail(work, task, error);
  }

  public void recoverExpired() {
    var references = store.list(Work.class,
        "from HiringWork where status='RUNNING' and leaseUntil < ?1 order by id", 0, 50, Instant.now());
    for (var reference : references) {
      var work = store.find(Work.class, reference.id, true).orElseThrow();
      if (!work.status.equals("RUNNING") || work.leaseUntil.isAfter(Instant.now())) continue;
      var task = store.find(AsyncTaskEntity.class, work.taskId, true).orElseThrow();
      work.leaseToken = null; work.leaseUntil = null;
      retryOrFail(work, task, "执行超时，已恢复任务状态");
    }
  }

  private void retryOrFail(Work work, AsyncTaskEntity task, String safeError) {
    work.error = safeError; task.setLastError(safeError);
    if (work.attempts >= 3) { work.status = "FAILED"; task.setStatus(AsyncTaskStatus.FAILED); }
    else {
      work.status = "PENDING"; work.nextAttemptAt = Instant.now().plusSeconds(work.attempts == 1 ? 30 : 120);
      task.setStatus(AsyncTaskStatus.PENDING); task.setLastPublishedAt(null);
    }
  }
  private void lockContext(Work work) {
    store.find(Organization.class, work.organizationId, true).orElseThrow();
    if (work.applicationId != null) store.find(Application.class, work.applicationId, true).orElseThrow();
  }
  private boolean contextValid(Work work) {
    if (!store.find(Organization.class, work.organizationId, false).orElseThrow().active) return false;
    if (work.kind.equals("CANDIDATE_PREPARATION")) {
      var member = store.one(interview.pilot.recruitment.infrastructure.CampaignEntities.BatchMember.class,
          "from HiringBatchMember where workId=?1", work.id).orElseThrow();
      var invitation = store.one(interview.pilot.recruitment.infrastructure.CampaignEntities.Invitation.class,
          "from HiringInterviewInvitation where batchMemberId=?1", member.id).orElseThrow();
      if (!invitation.status.equals("CREATED")) return false;
    }
    if (work.applicationId == null) return true;
    var app = store.find(Application.class, work.applicationId, false).orElseThrow();
    return app.organizationId.equals(work.organizationId) && app.jobId.equals(work.jobId)
        && app.submissionNo == work.submissionNo && !app.status.equals("WITHDRAWN") && !app.status.equals("FINISHED");
  }
  private void cancel(Work work, AsyncTaskEntity task) {
    work.status = "CANCELLED"; work.error = "企业或投递状态已变更，任务取消";
    work.leaseToken = null; work.leaseUntil = null; task.setStatus(AsyncTaskStatus.COMPLETED); task.setLastError(work.error);
  }
}
