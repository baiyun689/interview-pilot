package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import static interview.pilot.recruitment.application.AssessmentModels.*;
import static interview.pilot.recruitment.application.HiringModels.*;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Uses the same real MySQL fixture as the foundation suite, with deterministic AI results. */
@SpringJUnitConfig(RecruitmentFoundationIT.Config.class)
class HiringAssessmentIT {
  @Autowired HiringStore store;
  @Autowired OrganizationService organizations;
  @Autowired RecruitmentService recruitment;
  @Autowired HiringAssessmentService assessment;
  @Autowired HiringWorkState workState;
  @Autowired PlatformTransactionManager manager;
  CurrentUser admin;
  CurrentUser candidate;
  OrganizationView organization;
  JobView job;
  ApplicationView application;

  @BeforeEach void setup() {
    admin = account("Recruiter"); candidate = account("Candidate");
    organization = organizations.create(admin, new OrganizationInput("Assessment " + UUID.randomUUID()));
    job = recruitment.createJob(admin, organization.id(), new JobInput("Java", "理解 Redis 缓存一致性", "上海", "实习", 0));
    job = recruitment.publishJob(admin, organization.id(), job.id(), job.version());
    Long resume = tx(() -> store.add(ResumeEntity.pending(candidate.databaseId(), "resume.txt", UUID.randomUUID().toString(), "我在订单项目中使用 Redis 缓存商品数据")).getId());
    application = recruitment.apply(candidate, job.id(), new ApplicationInput(resume, job.publishedRevision(), false));
  }

  @Test void analysisUsesFrozenMaterialsAndDuplicateRequestsReuseWork() {
    var work = assessment.analyze(admin, organization.id(), application.id(), "test");
    assertThat(assessment.analyze(admin, organization.id(), application.id(), "test").id()).isEqualTo(work.id());
    assertThat(work.input().requirements().getFirst().text()).contains("一致性");
    assertThat(work.input().resumeEvidence().getFirst().text()).contains("商品数据");
    assertThatThrownBy(() -> assessment.analysis(candidate, organization.id(), application.id())).isInstanceOf(BusinessException.class);
    var claim = workState.begin(message(work.id()));
    assertThat(workState.begin(message(work.id()))).isNull();
    workState.finish(claim, validResult(), null);
    assertThat(assessment.analysis(admin, organization.id(), application.id()).status()).isEqualTo("COMPLETED");
    assertThat(workState.begin(message(work.id()))).isNull();
  }

  @Test void expiredOwnerCannotOverwriteReplacementResult() {
    var work = assessment.analyze(admin, organization.id(), application.id(), "test");
    var oldClaim = workState.begin(message(work.id()));
    tx(() -> { store.find(AssessmentEntities.Work.class, work.id(), true).orElseThrow().leaseUntil = Instant.now().minusSeconds(1); return null; });
    workState.recoverExpired();
    makeDue(work.id());
    var newClaim = workState.begin(message(work.id()));
    assertThat(newClaim.token()).isNotEqualTo(oldClaim.token());
    workState.finish(oldClaim, validResult(), null);
    assertThat(assessment.analysis(admin, organization.id(), application.id()).status()).isEqualTo("RUNNING");
    workState.finish(newClaim, validResult(), null);
    assertThat(assessment.analysis(admin, organization.id(), application.id()).status()).isEqualTo("COMPLETED");
  }

  @Test void exhaustedRetriesAreVisibleAndManualRetryAdvancesEpoch() {
    var work = assessment.analyze(admin, organization.id(), application.id(), "test");
    var originalMessage = message(work.id());
    for (int i = 0; i < 3; i++) {
      makeDue(work.id());
      workState.finish(workState.begin(originalMessage), null, "测试失败");
    }
    assertThat(assessment.analysis(admin, organization.id(), application.id()).status()).isEqualTo("FAILED");
    assessment.retryAnalysis(admin, organization.id(), work.id());
    assertThat(workState.begin(originalMessage)).isNull();
    assertThat(workState.begin(message(work.id()))).isNotNull();
  }

  @Test void withdrawalCancelsOutstandingAnalysisWithoutPublishingResult() {
    var work = assessment.analyze(admin, organization.id(), application.id(), "test");
    var claim = workState.begin(message(work.id()));
    recruitment.withdraw(candidate, application.id(), application.version());
    workState.finish(claim, validResult(), null);
    var result = assessment.analysis(admin, organization.id(), application.id());
    assertThat(result.status()).isEqualTo("CANCELLED"); assertThat(result.result()).isNull();
  }

  @Test void publishedSchemeIsImmutableAndRubricIdentifiersAreUnique() {
    var scheme = assessment.saveScheme(admin, organization.id(), job.id(), null, new SchemeInput("一面", definition(), 0));
    var published = assessment.publishScheme(admin, organization.id(), scheme.id(), scheme.version());
    var current = assessment.schemes(admin, organization.id(), job.id()).getFirst();
    assessment.saveScheme(admin, organization.id(), job.id(), current.id(), new SchemeInput("编辑后的草稿", definition(), current.version()));
    assertThat(assessment.revisions(admin, organization.id(), current.id()).getFirst().name()).isEqualTo("一面");
    assertThat(published.modelName()).isEqualTo("test-model");
    assertThatThrownBy(() -> assessment.publishScheme(candidate, organization.id(), current.id(), 0)).isInstanceOf(BusinessException.class);
    var q = definition().commonQuestions().getFirst();
    var bad = new Definition(Difficulty.MEDIUM, InterviewMode.TEXT, 30, definition().stages(), List.of(q, q), "test");
    assertThatThrownBy(() -> assessment.validateDefinition(bad, true)).isInstanceOf(BusinessException.class);
  }

  private Definition definition() {
    return new Definition(Difficulty.MEDIUM, InterviewMode.TEXT, 30,
        List.of(new Stage(InterviewPhase.FUNDAMENTALS, 2, 1)),
        List.of(new CommonQuestion("q1", InterviewPhase.FUNDAMENTALS, "缓存更新如何处理？",
            List.of(new RubricItem("p1", "一致性", "解释数据库与缓存更新顺序及失败处理")))), "test");
  }
  private AnalysisResult validResult() { return new AnalysisResult(List.of(new Finding("j1", EvidenceStatus.SUPPORTED, List.of("r1"), "存在相关描述，需核实实现", List.of("如何处理缓存删除失败？")))); }
  private void makeDue(Long id) { tx(() -> { store.find(AssessmentEntities.Work.class, id, true).orElseThrow().nextAttemptAt = Instant.now().minusSeconds(1); return null; }); }
  private TaskMessage message(Long id) { return tx(() -> { var work = store.find(AssessmentEntities.Work.class, id, false).orElseThrow(); var task = store.find(AsyncTaskEntity.class, work.taskId, false).orElseThrow(); return new TaskMessage(task.getTaskId(), task.getTaskType(), task.getBizKey(), task.getExecutionEpoch()); }); }
  private CurrentUser account(String name) { return tx(() -> { var a = store.add(UserAccountEntity.register(UUID.randomUUID() + "@example.test", "!", name)); return new CurrentUser(a.getId(), a.getUserId(), a.getEmail(), a.getDisplayName()); }); }
  private <T> T tx(java.util.function.Supplier<T> operation) { return new TransactionTemplate(manager).execute(s -> operation.get()); }
}
