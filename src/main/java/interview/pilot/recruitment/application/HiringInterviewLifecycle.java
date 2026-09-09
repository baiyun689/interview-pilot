package interview.pilot.recruitment.application;

import java.time.Instant;
import java.util.*;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.interview.application.FixedAnswerResult;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.recruitment.infrastructure.HiringStore;
import static interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringInterviewLifecycle {
  private final HiringStore store;
  private final ObjectMapper json;
  public HiringInterviewLifecycle(HiringStore store, ObjectMapper json) {this.store=store;this.json=json;}

  @Transactional(readOnly = true)
  public List<Long> candidates() {
    return store.list(Long.class, "select s.id from InterviewSessionEntity s where s.hiringInvitationId is not null "
        + "and (s.status in ('EVALUATING','COMPLETED','EVALUATION_FAILED') or (s.status='INTERVIEWING' and s.answerDeadline<=?1)) "
        + "and exists (select i.id from HiringInterviewInvitation i where i.id=s.hiringInvitationId and i.status='STARTED') order by s.id",
        0, 100, Instant.now());
  }

  public void reconcile(Long id) {
    var reference = store.find(InterviewSessionEntity.class, id, false).orElseThrow();
    var invitationRef = store.find(Invitation.class, reference.getHiringInvitationId(), false).orElseThrow();
    var member = store.find(BatchMember.class, invitationRef.batchMemberId, false).orElseThrow();
    var batch = store.find(Batch.class, member.batchId, false).orElseThrow();
    store.find(Organization.class, batch.organizationId, true).orElseThrow();
    store.find(Application.class, invitationRef.applicationId, true).orElseThrow();
    var invitation = store.find(Invitation.class, invitationRef.id, true).orElseThrow();
    var session = store.find(InterviewSessionEntity.class, id, true).orElseThrow();
    if (!invitation.status.equals("STARTED")) return;
    if (session.getStatus() == SessionStatus.INTERVIEWING && !Instant.now().isBefore(session.getAnswerDeadline())) {
      // A claim admitted before the deadline already persisted its answer. Preserve it even if
      // follow-up generation is still in flight; the worker's later result cannot advance.
      for (var ref : store.list(InterviewTurnEntity.class, "from InterviewTurnEntity where sessionId=?1 order by turnNo", 0, 100, id)) {
        var turn = store.find(InterviewTurnEntity.class, ref.getId(), true).orElseThrow();
        if (turn.getStatus() != TurnStatus.PROCESSING) continue;
        turn.completeAnswer();
        if (turn.getPhase() == InterviewPhase.SELF_INTRODUCTION) turn.skipEvaluation();
        else {
          turn.markEvaluationPending();
          enqueue(session, AsyncTaskType.ANSWER_EVALUATION, "answer-eval:" + session.getSessionId() + ":" + turn.getTurnNo(),
              Map.of("sessionId",session.getSessionId(),"turnNo",turn.getTurnNo(),"requestId",turn.getRequestId()));
        }
        var attempt = store.one(AnswerAttemptEntity.class, "from AnswerAttemptEntity where requestId=?1", turn.getRequestId()).orElseThrow();
        attempt.complete(json.writeValueAsString(new FixedAnswerResult(session.getSessionId(), turn.getRequestId(), turn.getTurnNo(), SessionStatus.EVALUATING, null, false)));
      }
      session.beginEvaluation();
      enqueue(session, AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + session.getSessionId(), Map.of("sessionId", session.getSessionId()));
    }
    if (List.of(SessionStatus.EVALUATING,SessionStatus.COMPLETED,SessionStatus.EVALUATION_FAILED).contains(session.getStatus())) {
      invitation.status="COMPLETED"; invitation.scheduleRevision++;
    }
  }

  private void enqueue(InterviewSessionEntity session, AsyncTaskType type, String key, Object payload) {
    if (store.one(AsyncTaskEntity.class, "from AsyncTaskEntity where taskType=?1 and bizKey=?2", type, key).isEmpty())
      store.add(AsyncTaskEntity.pending(session.getUserAccountId(), type, key, json.writeValueAsString(payload)));
  }

  public static void cancel(HiringStore store, Long invitationId) {
    store.one(InterviewSessionEntity.class, "from InterviewSessionEntity where hiringInvitationId=?1", invitationId)
        .ifPresent(ref -> store.find(InterviewSessionEntity.class, ref.getId(), true).orElseThrow().cancelRecruitment());
  }
}
