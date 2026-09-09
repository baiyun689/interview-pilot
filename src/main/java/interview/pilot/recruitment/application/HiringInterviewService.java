package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import static interview.pilot.recruitment.application.CampaignModels.*;
import static interview.pilot.recruitment.application.HiringAccess.*;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.interview.rag.*;
import interview.pilot.recruitment.infrastructure.AssessmentEntities.*;
import interview.pilot.recruitment.infrastructure.HiringStore;
import interview.pilot.voice.application.QuestionSpeechTaskCreator;
import interview.pilot.voice.config.VoiceProperties;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringInterviewService {
  private final HiringStore store;
  private final ObjectMapper json;
  private final OrganizationService organizations;
  private final VoiceProperties voice;
  private final QuestionSpeechTaskCreator speeches;

  public HiringInterviewService(HiringStore store, ObjectMapper json, OrganizationService organizations,
      VoiceProperties voice, QuestionSpeechTaskCreator speeches) {
    this.store=store; this.json=json; this.organizations=organizations; this.voice=voice; this.speeches=speeches;
  }

  public Started start(CurrentUser user, String publicId) {
    var reference = store.one(Invitation.class, "from HiringInterviewInvitation where publicId=?1 and candidateId=?2 and issuedAt is not null",
        publicId, user.databaseId()).orElseThrow(HiringAccess::notFound);
    var member = store.find(BatchMember.class, reference.batchMemberId, false).orElseThrow();
    var batch = store.find(Batch.class, member.batchId, false).orElseThrow();
    store.find(Organization.class, batch.organizationId, true).filter(o -> o.active).orElseThrow(HiringAccess::notFound);
    var application = store.find(Application.class, reference.applicationId, true).orElseThrow();
    var invite = store.find(Invitation.class, reference.id, true).orElseThrow();
    HiringCampaignService.requireCurrent(application, member);
    var existing = store.one(InterviewSessionEntity.class, "from InterviewSessionEntity where hiringInvitationId=?1", invite.id);
    if (existing.isPresent()) {
      if (!List.of("STARTED", "COMPLETED").contains(invite.status)) throw conflict("本次面试已终止");
      return new Started(existing.get().getSessionId(), existing.get().getAnswerDeadline());
    }
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (!invite.status.equals("ACCEPTED")) throw conflict("请先确认邀请并安排时间");
    if (now.isBefore(batch.opensAt) || now.isAfter(batch.latestStartAt)) throw conflict("当前不在允许开始面试的时间范围内");
    if (member.approvedSnapshot == null) throw conflict("企业尚未确认题目");
    var scheme = store.find(SchemeRevision.class, batch.schemeRevisionId, false).orElseThrow();
    var definition = json.readValue(scheme.definition, AssessmentModels.Definition.class);
    var deck = json.readValue(member.approvedSnapshot, PreparedDeck.class);
    var job = store.one(JobRevision.class, "from HiringJobRevision where jobId=?1 and revision=?2", batch.jobId, member.jobRevision).orElseThrow();
    var brief = new InterviewBriefSnapshot(JobSourceType.CUSTOM, null, null, job.title, job.description,
        null, null, definition.difficulty(), InterviewSize.STANDARD, scheme.providerId, scheme.modelName, null, 2);
    String voiceSnapshot = null;
    if (definition.mode() == InterviewMode.VOICE) {
      if (!voice.asrConfigured()) throw conflict("语音服务暂不可用，请联系企业调整安排");
      voiceSnapshot = json.writeValueAsString(voice.toSnapshot());
    }
    Instant deadline = now.plusSeconds(definition.durationMinutes() * 60L);
    if (deadline.isAfter(batch.closesAt)) deadline = batch.closesAt;
    var session = InterviewSessionEntity.preparing(user.databaseId(), null, definition.difficulty(), InterviewSize.STANDARD,
        JobSourceType.CUSTOM, job.title, scheme.providerId, scheme.modelName, json.writeValueAsString(brief),
        scheme.knowledgeScopeSnapshot, definition.mode(), voiceSnapshot);
    session.bindInvitation(invite.id, deadline, deck.questions().size());
    store.add(session); store.flush();
    var plan = new ArrayList<InterviewExecutionPlan.Card>();
    var sequence = new EnumMap<InterviewPhase, Integer>(InterviewPhase.class);
    for (var question : deck.questions()) {
      int quota = definition.stages().stream().filter(s -> s.phase() == question.phase()).findFirst().orElseThrow().followUpLimit();
      var chunks = deck.knowledgeEvidence().stream().flatMap(s -> s.chunks().stream())
          .filter(chunk -> question.knowledgeEvidenceIds().contains(chunk.pointId()))
          .collect(java.util.stream.Collectors.toMap(RagContextSnapshot.Chunk::pointId, c -> c, (a,b) -> a, LinkedHashMap::new));
      var grounding = chunks.isEmpty() ? GroundingMode.GENERAL : GroundingMode.KNOWLEDGE_ASSISTED;
      var rag = chunks.isEmpty() ? RagContextSnapshot.notConfigured()
          : new RagContextSnapshot(RagStatus.RETRIEVED, question.question(), "", List.copyOf(chunks.values()), null, grounding, question.knowledgeEvidenceIds());
      var rubric = question.rubric().stream().map(r -> new RubricPoint(r.point(), r.acceptance())).toList();
      var card = store.add(InterviewQuestionCardEntity.create(session.getId(), question.phase(), sequence.merge(question.phase(), 1, Integer::sum),
          "企业面试", question.question(), json.writeValueAsString(question.rubric().stream().map(AssessmentModels.RubricItem::point).toList()),
          null, "[]", grounding, rag.status(), json.writeValueAsString(rag), json.writeValueAsString(question.knowledgeEvidenceIds()),
          json.writeValueAsString(rubric), quota, "请结合具体例子，进一步说明你的实现思路、取舍和验证方法。"));
      store.flush();
      plan.add(new InterviewExecutionPlan.Card(card.getId(), card.getPhase(), quota));
    }
    var frozen = new InterviewExecutionPlan(plan);
    session.freezeExecutionPlan(json.writeValueAsString(frozen));
    session.preparationReady(); session.beginFixedInterview();
    var first = frozen.first();
    var turn = store.add(InterviewTurnEntity.asked(session.getId(), 1, first.phase(),
        first.phase() == InterviewPhase.SELF_INTRODUCTION ? QuestionType.SELF_INTRODUCTION : QuestionType.MAIN,
        first.id(), deck.questions().getFirst().question()));
    speeches.createForTurn(session, turn);
    invite.status="STARTED"; invite.scheduleRevision++;
    organizations.audit(user, batch.organizationId, "HIRING_INTERVIEW_STARTED", invite.id);
    return new Started(session.getSessionId(), deadline);
  }
  public record Started(UUID sessionId, Instant deadline) {}
}
