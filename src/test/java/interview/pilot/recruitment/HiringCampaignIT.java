package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import static interview.pilot.recruitment.application.AssessmentModels.*;
import static interview.pilot.recruitment.application.CampaignModels.*;
import static interview.pilot.recruitment.application.HiringModels.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.domain.*;
import interview.pilot.recruitment.application.*;
import interview.pilot.recruitment.infrastructure.*;
import interview.pilot.resume.infrastructure.ResumeEntity;

@SpringJUnitConfig(RecruitmentFoundationIT.Config.class)
class HiringCampaignIT {
  @Autowired HiringStore store;
  @Autowired OrganizationService organizations;
  @Autowired RecruitmentService recruitment;
  @Autowired HiringAssessmentService assessment;
  @Autowired HiringCampaignService campaigns;
  @Autowired HiringInvitationService invitations;
  @Autowired HiringWorkState workState;
  @Autowired HiringInterviewService interviews;
  @Autowired HiringInterviewLifecycle lifecycle;
  @Autowired HiringReviewService reviews;
  @Autowired HiringNotificationService notifications;
  @Autowired javax.sql.DataSource dataSource;
  @Autowired PlatformTransactionManager manager;
  CurrentUser admin, candidate, outsider;
  OrganizationView org;
  JobView job;
  ApplicationView application;
  SchemeRevisionView revision;
  @BeforeEach void setup() {
    admin=account("Admin");candidate=account("Candidate");outsider=account("Outsider");
    org=organizations.create(admin,new OrganizationInput("Campaign "+UUID.randomUUID()));
    job=recruitment.createJob(admin,org.id(),new JobInput("Java","掌握 Redis 缓存一致性","上海","实习",0));
    job=recruitment.publishJob(admin,org.id(),job.id(),job.version());
    Long resume=tx(()->store.add(ResumeEntity.pending(candidate.databaseId(),"resume.txt",UUID.randomUUID().toString(),"项目使用 Redis 缓存商品数据")).getId());
    application=recruitment.apply(candidate,job.id(),new ApplicationInput(resume,job.publishedRevision(),false));
    var definition=new Definition(Difficulty.MEDIUM,InterviewMode.TEXT,30,List.of(new Stage(InterviewPhase.FUNDAMENTALS,2,0)),
        List.of(new CommonQuestion("common1",InterviewPhase.FUNDAMENTALS,"解释缓存一致性",List.of(new RubricItem("cache","一致性","描述数据库与缓存更新顺序")))),"test");
    var scheme=assessment.saveScheme(admin,org.id(),job.id(),null,new SchemeInput("技术面",definition,0));
    revision=assessment.publishScheme(admin,org.id(),scheme.id(),scheme.version());
  }
  @Test void sameRequestIsIdempotentButChangedContentOrActiveRoundConflicts() {
    var input=input("request-001");var batch=campaigns.create(admin,org.id(),job.id(),input);
    assertThat(campaigns.create(admin,org.id(),job.id(),input).batch().id()).isEqualTo(batch.batch().id());
    assertThatThrownBy(()->campaigns.create(admin,org.id(),job.id(),input("request-002"))).isInstanceOf(BusinessException.class);
    var changed=new BatchInput("changed",input.schemeRevisionId(),1,input.applicationIds(),input.requestKey(),input.opensAt(),input.latestStartAt(),input.closesAt(),input.timezone());
    assertThatThrownBy(()->campaigns.create(admin,org.id(),job.id(),changed)).isInstanceOf(BusinessException.class);
    assertThat(invitations.mine(candidate,0).items()).isEmpty();
    assertThatThrownBy(()->campaigns.detail(outsider,org.id(),batch.batch().id())).isInstanceOf(BusinessException.class);
  }
  @Test void onlyApprovedMembersAreIssuedAndCandidateCannotReadDeck() {
    var batch=campaigns.create(admin,org.id(),job.id(),input("request-003"));var member=batch.members().getFirst();
    assertThat(campaigns.publish(admin,org.id(),batch.batch().id()).skipped()).containsExactly(member.id());
    complete(member.id());
    assertThat(campaigns.publish(admin,org.id(),batch.batch().id()).issued()).isEmpty();
    campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(member.version(),deck()));
    assertThat(campaigns.publish(admin,org.id(),batch.batch().id()).issued()).containsExactly(member.id());
    assertThat(campaigns.publish(admin,org.id(),batch.batch().id()).issued()).containsExactly(member.id());
    assertThat(invitations.mine(candidate,0).items()).hasSize(1);
    assertThat(invitations.mine(outsider,0).items()).isEmpty();
    var invite=invitations.mine(candidate,0).items().getFirst();
    assertThatThrownBy(()->invitations.schedule(outsider,invite.id(),new ScheduleInput(Instant.now().plusSeconds(5000),0))).isInstanceOf(BusinessException.class);
    var scheduled=invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(5000),invite.version()));
    assertThat(scheduled.status()).isEqualTo("ACCEPTED");
    assertThat(scheduled.scheduleRevision()).isEqualTo(1);
    assertThatThrownBy(() -> invitations.calendar(outsider, invite.id())).isInstanceOf(BusinessException.class);
    assertThat(new String(invitations.calendar(candidate, invite.id()), java.nio.charset.StandardCharsets.UTF_8))
        .contains("SEQUENCE:1\r\n", "STATUS:CONFIRMED\r\n").doesNotContain("解释缓存一致性");
    assertThatThrownBy(()->invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(1),scheduled.version()))).isInstanceOf(BusinessException.class);
  }
  @Test void cancellationFencesRunningPreparationAndAllowsExplicitReplacement() {
    var batch=campaigns.create(admin,org.id(),job.id(),input("request-004"));var member=batch.members().getFirst();
    var claim=workState.begin(message(member.id()));
    invitations.cancel(admin,org.id(),member.id());
    workState.finish(claim,deck(),null);
    assertThat(campaigns.detail(admin,org.id(),batch.batch().id()).members().getFirst().status()).isEqualTo("CANCELLED");
    assertThat(campaigns.create(admin,org.id(),job.id(),input("request-005")).batch().id()).isNotEqualTo(batch.batch().id());
  }
  @Test void withdrawalCancelsIssuedInvitationAndPreventsScheduling() {
    var batch=campaigns.create(admin,org.id(),job.id(),input("request-006"));var member=batch.members().getFirst();complete(member.id());
    campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(member.version(),deck()));campaigns.publish(admin,org.id(),batch.batch().id());
    var current=recruitment.mine(candidate,0).items().getFirst();recruitment.withdraw(candidate,current.id(),current.version());
    var invite=invitations.mine(candidate,0).items().getFirst();assertThat(invite.status()).isEqualTo("CANCELLED");
    assertThatThrownBy(()->invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(5000),invite.version()))).isInstanceOf(BusinessException.class);
  }
  @Test void approvalRejectsChangedCommonQuestionAndUnknownResumeEvidence() {
    var batch=campaigns.create(admin,org.id(),job.id(),input("request-007"));var member=batch.members().getFirst();complete(member.id());
    var wrong=new ArrayList<>(deck().questions());var q=wrong.getFirst();wrong.set(0,new PreparedQuestion(q.id(),q.phase(),true,"被改动",q.rubric(),List.of()));
    assertThatThrownBy(()->campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(0,new PreparedDeck(wrong)))).isInstanceOf(BusinessException.class);
    assertThat(campaigns.detail(admin,org.id(),batch.batch().id()).members().getFirst().approved()).isFalse();
    var personal=deck().questions().get(1);
    var unknown=new PreparedDeck(List.of(deck().questions().getFirst(),new PreparedQuestion(personal.id(),personal.phase(),false,personal.question(),personal.rubric(),List.of("r999"))));
    assertThatThrownBy(()->campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(0,unknown))).isInstanceOf(BusinessException.class);
  }
  @Test void concurrentCreationRetainsOneBatchAndOneActiveInvitation() throws Exception {
    var input=input("concurrent-request");
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Long> create=()->{start.await();return campaigns.create(admin,org.id(),job.id(),input).batch().id();};
      var a=executor.submit(create);var b=executor.submit(create);start.countDown();
      assertThat(a.get()).isEqualTo(b.get());
    }
    assertThat(campaigns.list(admin,org.id(),job.id())).hasSize(1);
  }
  @Test void expiryCancelsPendingPreparationAndReleasesRoundSlot() {
    var batch=campaigns.create(admin,org.id(),job.id(),input("expiry-request"));
    tx(()->{var entity=store.find(CampaignEntities.Batch.class,batch.batch().id(),true).orElseThrow();entity.opensAt=Instant.now().minusSeconds(7200);entity.latestStartAt=Instant.now().minusSeconds(3600);return null;});
    for(Long id:invitations.expiredCandidates()) invitations.expire(id);
    assertThat(workState.begin(message(batch.members().getFirst().id()))).isNull();
    assertThat(campaigns.create(admin,org.id(),job.id(),input("after-expiry-request")).batch().id()).isNotEqualTo(batch.batch().id());
  }
  @Test void invitationStartFreezesApprovedDeckAndDeadlineAndReplaysOneSession() throws Exception {
    var batch=campaigns.create(admin,org.id(),job.id(),input("start-request"));var member=batch.members().getFirst();complete(member.id());
    campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(0,deck()));campaigns.publish(admin,org.id(),batch.batch().id());
    var invite=invitations.mine(candidate,0).items().getFirst();
    assertThatThrownBy(()->interviews.start(candidate,invite.id())).isInstanceOf(BusinessException.class);
    invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(5000),invite.version()));
    assertThatThrownBy(()->interviews.start(candidate,invite.id())).hasMessageContaining("时间范围");
    tx(()->{store.find(CampaignEntities.Batch.class,batch.batch().id(),true).orElseThrow().opensAt=Instant.now().minusSeconds(60);return null;});
    assertThatThrownBy(()->interviews.start(outsider,invite.id())).isInstanceOf(BusinessException.class);
    HiringInterviewService.Started started;
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<HiringInterviewService.Started> operation=()->{gate.await();return interviews.start(candidate,invite.id());};
      var a=executor.submit(operation);var b=executor.submit(operation);gate.countDown();started=a.get();
      assertThat(b.get()).isEqualTo(started);
    }
    var session=tx(()->store.one(interview.pilot.interview.infrastructure.InterviewSessionEntity.class,"from InterviewSessionEntity where sessionId=?1",started.sessionId()).orElseThrow());
    assertThat(session.getTotalMainQuestionCount()).isEqualTo(2);
    assertThat(session.getStatus()).isEqualTo(SessionStatus.INTERVIEWING);
    assertThat(session.getExecutionPlan()).contains("FUNDAMENTALS").doesNotContain("SELF_INTRODUCTION");
    assertThat(session.getAnswerDeadline()).isBetween(Instant.now().plusSeconds(1750),Instant.now().plusSeconds(1801));
    var turn=tx(()->store.one(interview.pilot.interview.infrastructure.InterviewTurnEntity.class,"from InterviewTurnEntity where sessionId=?1",session.getId()).orElseThrow());
    assertThat(turn.getQuestionText()).isEqualTo(deck().questions().getFirst().question());
    invitations.cancel(admin,org.id(),member.id());
    assertThat(tx(()->store.find(interview.pilot.interview.infrastructure.InterviewSessionEntity.class,session.getId(),false).orElseThrow().getStatus())).isEqualTo(SessionStatus.CANCELLED);
    assertThatThrownBy(()->interviews.start(candidate,invite.id())).isInstanceOf(BusinessException.class);
  }

  @Test void timeoutPreservesAdmittedAnswerAndCreatesOneReportTask() {
    var batch=campaigns.create(admin,org.id(),job.id(),input("timeout-request"));var member=batch.members().getFirst();complete(member.id());
    campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(0,deck()));campaigns.publish(admin,org.id(),batch.batch().id());
    var invite=invitations.mine(candidate,0).items().getFirst();invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(5000),invite.version()));
    tx(()->{store.find(CampaignEntities.Batch.class,batch.batch().id(),true).orElseThrow().opensAt=Instant.now().minusSeconds(60);return null;});
    var started=interviews.start(candidate,invite.id());
    Long sessionId=tx(()->store.one(interview.pilot.interview.infrastructure.InterviewSessionEntity.class,"from InterviewSessionEntity where sessionId=?1",started.sessionId()).orElseThrow().getId());
    var requestId=UUID.randomUUID();
    tx(()->{var turn=store.one(interview.pilot.interview.infrastructure.InterviewTurnEntity.class,"from InterviewTurnEntity where sessionId=?1",sessionId).orElseThrow();
      turn.beginAnswer(requestId,"先更新数据库，再失效缓存",InputMode.TEXT);
      return store.add(interview.pilot.interview.infrastructure.AnswerAttemptEntity.processing(requestId,sessionId,turn.getId(),"test"));});
    new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("update interview_session set answer_deadline=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND where id=?",sessionId);
    lifecycle.reconcile(sessionId);lifecycle.reconcile(sessionId);
    assertThat(invitations.mine(candidate,0).items().getFirst().status()).isEqualTo("COMPLETED");
    assertThat(tx(()->store.one(Long.class,"select count(t) from AsyncTaskEntity t where bizKey=?1 and taskType=?2","interview:"+started.sessionId(),interview.pilot.async.domain.AsyncTaskType.INTERVIEW_EVALUATION).orElseThrow())).isEqualTo(1L);
    assertThat(tx(()->store.one(interview.pilot.interview.infrastructure.InterviewTurnEntity.class,"from InterviewTurnEntity where sessionId=?1",sessionId).orElseThrow().getStatus())).isEqualTo(TurnStatus.COMPLETED);
  }
  @Test void reviewRevisionsRemainPrivateUntilExplicitPublication() {
    String id=finishedInterview();
    assertThat(reviews.feedback(candidate,id)).isEmpty();
    assertThatThrownBy(()->reviews.detail(candidate,org.id(),id)).isInstanceOf(BusinessException.class);
    var draft=reviews.save(admin,org.id(),id,new HiringReviewService.Save(-1,reviewContent("内部秘密"),false));
    assertThat(draft.history()).isEmpty();
    assertThatThrownBy(()->reviews.save(admin,org.id(),id,new HiringReviewService.Save(-1,reviewContent("覆盖"),false))).isInstanceOf(BusinessException.class);
    var submitted=reviews.save(admin,org.id(),id,new HiringReviewService.Save(draft.reviews().getFirst().version(),reviewContent("内部秘密"),true));
    var changed=reviews.save(admin,org.id(),id,new HiringReviewService.Save(submitted.reviews().getFirst().version(),reviewContent("新内部秘密"),true));
    assertThat(changed.history()).hasSize(2);
    assertThat(reviews.feedback(candidate,id)).isEmpty();
    reviews.publish(admin,org.id(),id,new HiringReviewService.Publish(0,changed.history().getFirst().id(),"NEXT_ROUND","感谢参与，请等待下一轮安排"));
    assertThat(reviews.feedback(candidate,id)).hasSize(1);
    assertThat(reviews.feedback(candidate,id).getFirst().feedback()).doesNotContain("秘密");
    assertThatThrownBy(()->reviews.feedback(outsider,id)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(()->reviews.publish(admin,org.id(),id,new HiringReviewService.Publish(0,changed.history().getFirst().id(),"NEXT_ROUND","覆盖反馈"))).isInstanceOf(BusinessException.class);
    var next=input("review-next-round");
    assertThat(campaigns.create(admin,org.id(),job.id(),new BatchInput(next.name(),next.schemeRevisionId(),2,next.applicationIds(),next.requestKey(),next.opensAt(),next.latestStartAt(),next.closesAt(),next.timezone())).batch().roundNo()).isEqualTo(2);
    assertThatThrownBy(()->reviews.publish(admin,org.id(),id,new HiringReviewService.Publish(1,changed.history().getFirst().id(),"NEXT_ROUND","旧轮次覆盖"))).hasMessageContaining("后续轮次");
  }
  @Test void interviewerRequiresExplicitAssignmentAndCannotPublish() {
    String id=finishedInterview();
    var token=organizations.invite(admin,org.id(),new MemberInput(outsider.email(),Role.INTERVIEWER));organizations.accept(outsider,token.token());
    assertThat(reviews.list(outsider,org.id(),0).items()).isEmpty();
    assertThatThrownBy(()->reviews.detail(outsider,org.id(),id)).isInstanceOf(BusinessException.class);
    reviews.assign(admin,org.id(),id,outsider.databaseId(),true);
    assertThat(reviews.list(outsider,org.id(),0).items()).hasSize(1);
    var saved=reviews.save(outsider,org.id(),id,new HiringReviewService.Save(-1,reviewContent("意见"),true));
    assertThatThrownBy(()->reviews.publish(outsider,org.id(),id,new HiringReviewService.Publish(0,saved.history().getFirst().id(),"NEXT_ROUND","反馈"))).isInstanceOf(BusinessException.class);
    reviews.assign(admin,org.id(),id,outsider.databaseId(),false);
    assertThatThrownBy(()->reviews.detail(outsider,org.id(),id)).isInstanceOf(BusinessException.class);
  }
  @Test void incompleteDraftCanBeSavedButCannotBeSubmittedAndEndingStopsFurtherInvites() {
    String id=finishedInterview();
    var incomplete=new HiringReviewService.Content(List.of(new HiringReviewService.Dimension("技术","")),List.of(),"","END_PROCESS");
    var draft=reviews.save(admin,org.id(),id,new HiringReviewService.Save(-1,incomplete,false));
    assertThatThrownBy(()->reviews.save(admin,org.id(),id,new HiringReviewService.Save(draft.reviews().getFirst().version(),incomplete,true))).hasMessageContaining("填写");
    var content=new HiringReviewService.Content(List.of(new HiringReviewService.Dimension("技术","需要进一步准备")),List.of(1),"内部评语","END_PROCESS");
    var submitted=reviews.save(admin,org.id(),id,new HiringReviewService.Save(draft.reviews().getFirst().version(),content,true));
    reviews.publish(admin,org.id(),id,new HiringReviewService.Publish(0,submitted.history().getFirst().id(),"END_PROCESS","本轮流程已结束，感谢参与"));
    assertThat(recruitment.mine(candidate,0).items().getFirst().status()).isEqualTo("FINISHED");
    assertThatThrownBy(()->campaigns.create(admin,org.id(),job.id(),input("after-finished"))).isInstanceOf(BusinessException.class);
  }
  @Test void concurrentFeedbackPublicationHasOneWinner() throws Exception {
    String id=finishedInterview();var saved=reviews.save(admin,org.id(),id,new HiringReviewService.Save(-1,reviewContent("意见"),true));
    var input=new HiringReviewService.Publish(0,saved.history().getFirst().id(),"NEXT_ROUND","公开反馈");
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> publish=()->{gate.await();try{reviews.publish(admin,org.id(),id,input);return true;}catch(BusinessException e){return false;}};
      var a=executor.submit(publish);var b=executor.submit(publish);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
    }
    assertThat(reviews.feedback(candidate,id)).hasSize(1);
  }
  @Test void notificationDeliveryIsLeasedAndUnknownRequiresManualRetry() {
    var invite=issuedInterview();
    var notice=notifications.mine(candidate,0).items().getFirst();
    assertThatThrownBy(()->notifications.read(outsider,notice.id())).isInstanceOf(BusinessException.class);
    var claim=notifications.claim(notice.id(),true);assertThat(claim).isNotNull();assertThat(notifications.claim(notice.id(),true)).isNull();
    tx(()->{store.find(NotificationEntities.Notification.class,notice.id(),true).orElseThrow().leaseUntil=Instant.now().minusSeconds(1);return null;});
    assertThat(notifications.claim(notice.id(),true)).isNull();
    notifications.finish(claim,"ACCEPTED_BY_PROVIDER",null);
    var unknown=notifications.mine(candidate,0).items().getFirst();assertThat(unknown.mailStatus()).isEqualTo("UNKNOWN");
    assertThat(notifications.due()).doesNotContain(notice.id());
    assertThatThrownBy(()->notifications.retry(outsider,org.id(),notice.id(),unknown.version())).isInstanceOf(BusinessException.class);
    notifications.retry(admin,org.id(),notice.id(),unknown.version());
    var fresh=notifications.claim(notice.id(),true);notifications.finish(fresh,"ACCEPTED_BY_PROVIDER",null);
    assertThat(notifications.mine(candidate,0).items().getFirst().mailStatus()).isEqualTo("ACCEPTED_BY_PROVIDER");
    notifications.read(candidate,notice.id());assertThat(notifications.mine(candidate,0).items().getFirst().readAt()).isNotNull();
  }
  @Test void reschedulingAndCancellationFenceOldReminders() {
    var invite=issuedInterview();
    var scheduled=invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(7200),invite.version()));
    var old=tx(()->store.one(NotificationEntities.Notification.class,"from HiringNotification where recipientId=?1 and kind='REMINDER_1'",candidate.databaseId()).orElseThrow());
    tx(()->{var n=store.find(NotificationEntities.Notification.class,old.id,true).orElseThrow();n.dueAt=Instant.now();n.nextAttemptAt=n.dueAt;return null;});
    invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(8000),scheduled.version()));
    assertThat(notifications.claim(old.id,true)).isNull();
    assertThat(tx(()->store.find(NotificationEntities.Notification.class,old.id,false).orElseThrow().mailStatus)).isEqualTo("SKIPPED");
    var current=invitations.mine(candidate,0).items().getFirst();invitations.decline(candidate,current.id(),current.version());
    var newer=tx(()->store.one(NotificationEntities.Notification.class,"from HiringNotification where recipientId=?1 and kind='REMINDER_1' order by id desc",candidate.databaseId()).orElseThrow());
    tx(()->{var n=store.find(NotificationEntities.Notification.class,newer.id,true).orElseThrow();n.nextAttemptAt=Instant.now();return null;});
    assertThat(notifications.claim(newer.id,true)).isNull();
  }
  @Test void missedRemindersAreNotSentAfterRestart() {
    var invite=issuedInterview();invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(7200),invite.version()));
    var old=tx(()->store.one(NotificationEntities.Notification.class,"from HiringNotification where recipientId=?1 and kind='REMINDER_1'",candidate.databaseId()).orElseThrow());
    tx(()->{var n=store.find(NotificationEntities.Notification.class,old.id,true).orElseThrow();n.dueAt=Instant.now().minusSeconds(600);n.nextAttemptAt=n.dueAt;return null;});
    assertThat(notifications.claim(old.id,true)).isNull();
    assertThat(notifications.mine(candidate,0).items()).noneMatch(n->n.id().equals(old.id));
  }
  private HiringReviewService.Content reviewContent(String notes) {return new HiringReviewService.Content(List.of(new HiringReviewService.Dimension("技术能力","能够解释实现")),List.of(1),notes,"NEXT_ROUND");}
  private CampaignModels.InvitationView issuedInterview() {
    var batch=campaigns.create(admin,org.id(),job.id(),input(UUID.randomUUID().toString()));var member=batch.members().getFirst();complete(member.id());
    campaigns.approve(admin,org.id(),batch.batch().id(),member.id(),new ApprovalInput(0,deck()));campaigns.publish(admin,org.id(),batch.batch().id());
    return invitations.mine(candidate,0).items().getFirst();
  }
  private String finishedInterview() {
    var invite=issuedInterview();invitations.schedule(candidate,invite.id(),new ScheduleInput(Instant.now().plusSeconds(5000),invite.version()));
    tx(()->{var i=store.one(CampaignEntities.Invitation.class,"from HiringInterviewInvitation where publicId=?1",invite.id()).orElseThrow();var m=store.find(CampaignEntities.BatchMember.class,i.batchMemberId,false).orElseThrow();store.find(CampaignEntities.Batch.class,m.batchId,true).orElseThrow().opensAt=Instant.now().minusSeconds(60);return null;});
    var started=interviews.start(candidate,invite.id());
    tx(()->{var s=store.one(interview.pilot.interview.infrastructure.InterviewSessionEntity.class,"from InterviewSessionEntity where sessionId=?1",started.sessionId()).orElseThrow();
      var t=store.one(interview.pilot.interview.infrastructure.InterviewTurnEntity.class,"from InterviewTurnEntity where sessionId=?1",s.getId()).orElseThrow();t.beginAnswer(UUID.randomUUID(),"先写数据库再删除缓存",InputMode.TEXT);t.completeAnswer();s.beginEvaluation();
      store.one(CampaignEntities.Invitation.class,"from HiringInterviewInvitation where publicId=?1",invite.id()).orElseThrow().status="COMPLETED";return null;});
    return invite.id();
  }
  private BatchInput input(String key) { var opens=Instant.now().plusSeconds(3600);return new BatchInput("第一轮",revision.id(),1,List.of(application.id()),key,opens,opens.plusSeconds(86400),opens.plusSeconds(88200),"Asia/Shanghai"); }
  private PreparedDeck deck() { var common=revision.definition().commonQuestions().getFirst();return new PreparedDeck(List.of(
      new PreparedQuestion(common.id(),common.phase(),true,common.question(),common.rubric(),List.of()),
      new PreparedQuestion("personal1",InterviewPhase.FUNDAMENTALS,false,"你如何在商品项目中更新缓存？",List.of(new RubricItem("personal-cache","更新流程","说明项目中的更新顺序")),List.of("r1")))); }
  private void complete(Long memberId) { workState.finish(workState.begin(message(memberId)),deck(),null); }
  private TaskMessage message(Long memberId) { return tx(()->{var m=store.find(CampaignEntities.BatchMember.class,memberId,false).orElseThrow();var w=store.find(AssessmentEntities.Work.class,m.workId,false).orElseThrow();var t=store.find(AsyncTaskEntity.class,w.taskId,false).orElseThrow();return new TaskMessage(t.getTaskId(),t.getTaskType(),t.getBizKey(),t.getExecutionEpoch());}); }
  private CurrentUser account(String name) { return tx(()->{var a=store.add(UserAccountEntity.register(UUID.randomUUID()+"@example.test","!",name));return new CurrentUser(a.getId(),a.getUserId(),a.getEmail(),a.getDisplayName());}); }
  private <T>T tx(java.util.function.Supplier<T> f){return new TransactionTemplate(manager).execute(s->f.get());}
}
