package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.infrastructure.ReviewEntities.*;
import static interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.recruitment.infrastructure.HiringStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class HiringReviewService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;
  private final ObjectMapper json;
  public HiringReviewService(HiringStore store, HiringAccess access, OrganizationService organizations, ObjectMapper json) {
    this.store=store; this.access=access; this.organizations=organizations; this.json=json;
  }
  public record Dimension(@NotNull @Size(max=100) String name, @NotNull @Size(max=2000) String evaluation) {}
  public record Content(@NotNull @Size(max=20) List<@NotNull @Valid Dimension> dimensions,
      @NotNull @Size(max=100) List<@NotNull Integer> evidenceTurns,
      @NotNull @Size(max=10000) String notes, @NotBlank String decision) {}
  public record Save(@Min(-1) long version, @NotNull @Valid Content content, boolean submit) {}
  public record Publish(@Min(0) int revision, @NotNull Long reviewRevisionId,
      @NotBlank String decision, @NotBlank @Size(max=5000) String feedback) {}
  public record Summary(String invitationId, String candidateName, String jobTitle, int roundNo, String status) {}
  public record Turn(int turnNo, String question, String answer, String evaluationStatus, Object evaluation, Object rubric, Object knowledge) {}
  public record ReviewView(Long id, Long reviewerId, String reviewerName, String status, long version, Content content) {}
  public record RevisionView(Long id, Long reviewerId, int revision, Content content, Instant submittedAt) {}
  public record PublicFeedback(int revision, String decision, String feedback, Instant publishedAt) {}
  public record Detail(Summary interview, String sessionStatus, Object aiReport, List<Turn> turns,
      List<ReviewView> reviews, List<RevisionView> history, List<Long> assignments, List<PublicFeedback> publications,
      Long currentUserId, boolean canPublish) {}

  public record Reviewer(Long userId,String displayName) {}
  @Transactional(readOnly=true)
  public List<Reviewer> reviewers(CurrentUser user,Long orgId) {
    if(access.member(user,orgId,false).role.equals("INTERVIEWER")) throw forbidden();
    return store.list(Membership.class,"from HiringMembership where organizationId=?1 and active=true order by id",0,200,orgId)
        .stream().map(m->new Reviewer(m.userAccountId,name(m.userAccountId))).toList();
  }

  @Transactional(readOnly=true)
  public HiringModels.Page<Summary> list(CurrentUser user, Long orgId, int page) {
    if(page<0 || page>10000) throw conflict("无效页码");
    var member=access.member(user,orgId,false);
    String permission=member.role.equals("ADMIN") ? "" : member.role.equals("INTERVIEWER")
        ? " and exists (select a.id from HiringReviewAssignment a where a.invitationId=i.id and a.reviewerId=?2)"
        : " and exists (select a.id from HiringJobAssignment a where a.jobId=b.jobId and a.userAccountId=?2)";
    Object[] params=member.role.equals("ADMIN")?new Object[]{orgId}:new Object[]{orgId,user.databaseId()};
    var rows=store.list(Invitation.class,"select i from HiringInterviewInvitation i, HiringBatchMember m, HiringBatch b "
        +"where i.batchMemberId=m.id and m.batchId=b.id and b.organizationId=?1 "
        +"and exists (select s.id from InterviewSessionEntity s where s.hiringInvitationId=i.id)"+permission+" order by i.id desc",page*25,26,params);
    return new HiringModels.Page<>(rows.stream().limit(25).map(this::summary).toList(),page,rows.size()>25);
  }
  @Transactional(readOnly=true)
  public Detail detail(CurrentUser user, Long orgId, String id) {
    var invite=authorize(user,orgId,id,false,false);
    var session=session(invite);
    var reviews=store.list(Review.class,"from HiringReview where invitationId=?1 order by id",0,200,invite.id);
    var history=store.list(Revision.class,"select r from HiringReviewRevision r, HiringReview h where r.reviewId=h.id and h.invitationId=?1 order by r.id desc",0,200,invite.id);
    return new Detail(summary(invite),session.getStatus().name(),store.one(InterviewReportEntity.class,
        "from InterviewReportEntity where sessionId=?1",session.getId()).map(r->json.readValue(r.getReportSnapshot(),Object.class)).orElse(null),
        store.list(InterviewTurnEntity.class,"from InterviewTurnEntity where sessionId=?1 order by turnNo",0,200,session.getId()).stream()
          .map(this::turn).toList(),
        reviews.stream().map(r->new ReviewView(r.id,r.reviewerId,name(r.reviewerId),r.status,r.version,json.readValue(r.content,Content.class))).toList(),
        history.stream().map(r->new RevisionView(r.id,reviews.stream().filter(h->h.id.equals(r.reviewId)).findFirst().orElseThrow().reviewerId,
          r.revision,json.readValue(r.content,Content.class),r.submittedAt)).toList(),
        store.list(Assignment.class,"from HiringReviewAssignment where invitationId=?1",0,200,invite.id).stream().map(a->a.reviewerId).toList(),
        publications(invite),user.databaseId(),!access.member(user,orgId,false).role.equals("INTERVIEWER"));
  }
  public void assign(CurrentUser user,Long orgId,String id,Long reviewerId,boolean assigned) {
    var invite=authorize(user,orgId,id,true,true);
    store.one(Membership.class,"from HiringMembership where organizationId=?1 and userAccountId=?2 and active=true",orgId,reviewerId).orElseThrow(HiringAccess::notFound);
    var existing=store.one(Assignment.class,"from HiringReviewAssignment where invitationId=?1 and reviewerId=?2",invite.id,reviewerId);
    if(assigned && existing.isEmpty()) {var a=new Assignment();a.invitationId=invite.id;a.reviewerId=reviewerId;store.add(a);}
    if(!assigned) existing.ifPresent(store::remove);
    organizations.audit(user,orgId,assigned?"REVIEW_ASSIGNED":"REVIEW_UNASSIGNED",invite.id);
  }
  public Detail save(CurrentUser user,Long orgId,String id,Save input) {
    var invite=authorize(user,orgId,id,true,false); requireFinished(invite);
    validateDecision(input.content().decision());
    if(input.submit() && (input.content().notes().isBlank() || input.content().dimensions().isEmpty()
        || input.content().dimensions().stream().anyMatch(d->d.name().isBlank() || d.evaluation().isBlank())))
      throw conflict("提交评审前请填写维度评价和内部评语");
    var session=session(invite);
    var valid=store.list(InterviewTurnEntity.class,"from InterviewTurnEntity where sessionId=?1 and answerText is not null",0,200,session.getId())
        .stream().map(InterviewTurnEntity::getTurnNo).toList();
    if(!valid.containsAll(input.content().evidenceTurns())) throw conflict("证据必须引用本次面试中已保存的回答轮次");
    var review=store.one(Review.class,"from HiringReview where invitationId=?1 and reviewerId=?2",invite.id,user.databaseId()).orElse(null);
    if(review==null) {version(input.version(),-1);review=new Review();review.invitationId=invite.id;review.reviewerId=user.databaseId();}
    else version(input.version(),review.version);
    review.content=json.writeValueAsString(input.content()); review.status=input.submit()?"SUBMITTED":"DRAFT";
    if(review.id==null) store.add(review);
    if(input.submit()) {var revision=new Revision();revision.reviewId=review.id;revision.revision=++review.revision;
      revision.content=review.content;revision.submittedAt=Instant.now();store.add(revision);}
    store.flush(); organizations.audit(user,orgId,input.submit()?"REVIEW_SUBMITTED":"REVIEW_DRAFT_SAVED",invite.id);
    return detail(user,orgId,id);
  }
  public Detail publish(CurrentUser user,Long orgId,String id,Publish input) {
    var invite=authorize(user,orgId,id,true,true);requireFinished(invite);validateDecision(input.decision());
    var member=store.find(BatchMember.class,invite.batchMemberId,false).orElseThrow();
    var app=store.find(Application.class,invite.applicationId,true).orElseThrow();
    if(app.submissionNo!=member.submissionNo || app.status.equals("WITHDRAWN")) throw conflict("投递已撤回或已重新投递，不能发布旧流程结果");
    if(store.one(Invitation.class,"from HiringInterviewInvitation where applicationId=?1 and roundNo>?2 and status in ('CREATED','ISSUED','ACCEPTED','STARTED','COMPLETED')",app.id,invite.roundNo).isPresent())
      throw conflict("候选人已进入后续轮次，请在最新轮次发布结果");
    var latest=store.one(Feedback.class,"from HiringFeedback where invitationId=?1 order by revision desc",invite.id);
    version(input.revision(),latest.map(f->f.revision).orElse(0));
    var revision=store.find(Revision.class,input.reviewRevisionId(),false).orElseThrow(HiringAccess::notFound);
    store.find(Review.class,revision.reviewId,false).filter(r->r.invitationId.equals(invite.id)).orElseThrow(HiringAccess::notFound);
    if(!json.readValue(revision.content,Content.class).decision().equals(input.decision())) throw conflict("发布决定必须与选定人工评审一致，请先提交新评审修订");
    var feedback=new Feedback();feedback.invitationId=invite.id;feedback.revision=input.revision()+1;feedback.reviewRevisionId=revision.id;
    feedback.decision=input.decision();feedback.feedback=input.feedback().trim();feedback.publishedBy=user.databaseId();feedback.publishedAt=Instant.now();store.add(feedback);
    app.status=input.decision().equals("END_PROCESS")?"FINISHED":"IN_PROCESS";
    var event=new ApplicationEvent();event.applicationId=app.id;event.actorId=user.databaseId();event.action="FEEDBACK_"+input.decision();
    event.submissionNo=app.submissionNo;event.jobRevision=app.jobRevision;event.resumeRevisionId=app.resumeRevisionId;event.createdAt=Instant.now();store.add(event);
    HiringNotifications.enqueue(store,invite,"FEEDBACK",feedback.revision,Instant.now());
    organizations.audit(user,orgId,"FEEDBACK_PUBLISHED",invite.id);store.flush();return detail(user,orgId,id);
  }
  @Transactional(readOnly=true)
  public List<PublicFeedback> feedback(CurrentUser user,String id) {
    var invite=store.one(Invitation.class,"from HiringInterviewInvitation where publicId=?1 and candidateId=?2 and issuedAt is not null",id,user.databaseId()).orElseThrow(HiringAccess::notFound);
    return publications(invite);
  }
  private List<PublicFeedback> publications(Invitation invite) {
    return store.list(Feedback.class,"from HiringFeedback where invitationId=?1 order by revision desc",0,100,invite.id).stream()
        .map(f->new PublicFeedback(f.revision,f.decision,f.feedback,f.publishedAt)).toList();
  }
  private Invitation authorize(CurrentUser user,Long orgId,String id,boolean write,boolean manager) {
    var ref=store.one(Invitation.class,"from HiringInterviewInvitation where publicId=?1",id).orElseThrow(HiringAccess::notFound);
    var app=store.find(Application.class,ref.applicationId,false).filter(a->a.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    var member=access.member(user,orgId,write);
    if(member.role.equals("INTERVIEWER")) {
      if(manager) throw forbidden();
      store.one(Assignment.class,"from HiringReviewAssignment where invitationId=?1 and reviewerId=?2",ref.id,user.databaseId()).orElseThrow(HiringAccess::notFound);
    } else access.job(user,orgId,app.jobId,write);
    if(write) store.find(Application.class,app.id,true).orElseThrow();
    return store.find(Invitation.class,ref.id,write).orElseThrow();
  }
  private void requireFinished(Invitation invite) {
    if(List.of("PREPARING","READY","INTERVIEWING").contains(session(invite).getStatus().name())) throw conflict("面试尚未结束，暂不能提交评审");
  }
  private InterviewSessionEntity session(Invitation invite) {
    return store.one(InterviewSessionEntity.class,"from InterviewSessionEntity where hiringInvitationId=?1",invite.id).orElseThrow(HiringAccess::notFound);
  }
  private Summary summary(Invitation invite) {
    var app=store.find(Application.class,invite.applicationId,false).orElseThrow();
    var member=store.find(BatchMember.class,invite.batchMemberId,false).orElseThrow();
    return new Summary(invite.publicId,name(invite.candidateId),store.one(JobRevision.class,"from HiringJobRevision where jobId=?1 and revision=?2",app.jobId,member.jobRevision).orElseThrow().title,invite.roundNo,invite.status);
  }
  private String name(Long id) {return store.find(UserAccountEntity.class,id,false).orElseThrow().getDisplayName();}
  private Turn turn(InterviewTurnEntity t) {
    var card=store.find(InterviewQuestionCardEntity.class,t.getSourceCardId(),false).orElseThrow();
    return new Turn(t.getTurnNo(),t.getQuestionText(),t.getAnswerText(),t.getEvalStatus().name(),
        t.getAnswerEvaluation()==null?null:json.readValue(t.getAnswerEvaluation(),Object.class),
        card.getRubric()==null?null:json.readValue(card.getRubric(),Object.class),
        card.getRagContextSnapshot()==null?null:json.readValue(card.getRagContextSnapshot(),Object.class));
  }
  private static void validateDecision(String value) {
    if(!List.of("NEXT_ROUND","MORE_ASSESSMENT","END_PROCESS").contains(value)) throw conflict("无效的下一步决定");
  }
}
