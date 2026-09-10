package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import static interview.pilot.recruitment.application.HiringModels.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.recruitment.application.*;
import interview.pilot.recruitment.infrastructure.*;
import interview.pilot.resume.infrastructure.ResumeEntity;
import jakarta.persistence.EntityManagerFactory;

@SpringJUnitConfig(RecruitmentFoundationIT.Config.class)
class RecruitmentFoundationIT {
  static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
      .withDatabaseName("recruitment_foundation");
  // Shared by recruitment suites; Ryuk releases it when the test JVM exits.
  static { MYSQL.start(); }

  @org.springframework.boot.test.context.TestConfiguration @EnableTransactionManagement
  @Import({HiringStore.class, HiringAccess.class, OrganizationService.class, RecruitmentService.class, HiringPlatformService.class,
      HiringAssessmentService.class, HiringWorkState.class, HiringCampaignService.class, HiringInvitationService.class, HiringPreparationEngine.class,
      HiringInterviewService.class, HiringInterviewLifecycle.class, HiringReviewService.class, HiringNotificationService.class})
  static class Config {
    @Bean DataSource dataSource() {
      var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
      Flyway.configure().dataSource(ds).load().migrate(); return ds;
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
      var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setPackagesToScan("interview.pilot.recruitment.infrastructure", "interview.pilot.auth.infrastructure", "interview.pilot.resume.infrastructure", "interview.pilot.async.infrastructure", "interview.pilot.interview.infrastructure");
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate")); return factory;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    @Bean tools.jackson.databind.ObjectMapper objectMapper() { return new tools.jackson.databind.ObjectMapper(); }
    @Bean EnterpriseKnowledgeService knowledge() { return org.mockito.Mockito.mock(EnterpriseKnowledgeService.class); }
    @Bean interview.pilot.voice.config.VoiceProperties voiceProperties() { return org.mockito.Mockito.mock(interview.pilot.voice.config.VoiceProperties.class); }
    @Bean interview.pilot.voice.application.QuestionSpeechTaskCreator questionSpeeches() { return org.mockito.Mockito.mock(interview.pilot.voice.application.QuestionSpeechTaskCreator.class); }
    @Bean interview.pilot.ai.StructuredOutputInvoker structuredOutput() { return org.mockito.Mockito.mock(interview.pilot.ai.StructuredOutputInvoker.class); }
    @Bean interview.pilot.interview.application.QuestionRagRetriever questionRetrieval() { return org.mockito.Mockito.mock(interview.pilot.interview.application.QuestionRagRetriever.class); }
    @Bean org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator() { return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean(); }
    @Bean interview.pilot.ai.provider.AiProviderService providers() {
      var providers = org.mockito.Mockito.mock(interview.pilot.ai.provider.AiProviderService.class);
      org.mockito.Mockito.when(providers.resolveEnabled(org.mockito.ArgumentMatchers.any()))
          .thenReturn(new interview.pilot.ai.provider.AiProviderDescriptor("test", "Test", "test-model", true, true));
      return providers;
    }
  }

  @Autowired OrganizationService organizations;
  @Autowired RecruitmentService recruitment;
  @Autowired HiringNotificationService notifications;
  @Autowired HiringPlatformService platform;
  @Autowired HiringStore store;
  @Autowired PlatformTransactionManager manager;
  CurrentUser admin;
  CurrentUser candidate;
  CurrentUser outsider;
  OrganizationView company;
  JobView job;
  Long resumeId;

  @BeforeEach void fixture() {
    admin = account("Admin"); candidate = account("Candidate"); outsider = account("Outsider");
    company = organizations.create(admin, new OrganizationInput("企业 " + UUID.randomUUID()));
    job = recruitment.createJob(admin, company.id(), input("Java 后端实习", "掌握 Java、Redis 和数据库事务", 0));
    job = recruitment.publishJob(admin, company.id(), job.id(), job.version());
    resumeId = tx(() -> store.add(ResumeEntity.pending(candidate.databaseId(), "candidate.txt", UUID.randomUUID().toString(), "项目：Redis 缓存，使用延迟双删处理缓存更新")).getId());
  }

  @Test void freezesPublishedJobAndSubmittedResumeAcrossEditsAndDeletion() {
    var application = recruitment.apply(candidate, job.id(), applyInput(false));
    recruitment.updateJob(admin, company.id(), job.id(), input("未发布修改", "尚未发布的草稿", job.version()));
    assertThat(recruitment.publicJob(job.id()).title()).isEqualTo("Java 后端实习");
    var current = recruitment.companyJobs(admin, company.id(), 0).items().getFirst();
    recruitment.publishJob(admin, company.id(), job.id(), current.version());
    tx(() -> { var source = store.find(ResumeEntity.class, resumeId, true).orElseThrow();
      source.setParsedText("修改后的个人简历"); store.remove(source); return null; });
    var detail = recruitment.detail(admin, company.id(), application.id());
    assertThat(detail.application().jobRevision()).isEqualTo(1);
    assertThat(detail.application().jobTitle()).isEqualTo("Java 后端实习");
    assertThat(detail.resumeText()).contains("延迟双删");
    assertThat(detail.jobDescription()).contains("Redis");
  }

  @Test void submittedApplicationCreatesAnInboxNotificationForTheCompany() {
    assertThat(notifications.company(admin, company.id(), 0).items())
        .anyMatch(notification -> notification.title().equals("收到新的候选人投递")
            && notification.message().contains("Candidate"));
  }

  @Test void rejectsCrossTenantAndCrossCandidateReadsAndForeignResumeSubmission() {
    var application = recruitment.apply(candidate, job.id(), applyInput(false));
    var another = organizations.create(outsider, new OrganizationInput("另一家公司"));
    assertDenied(() -> recruitment.detail(outsider, company.id(), application.id()));
    assertDenied(() -> recruitment.detail(outsider, another.id(), application.id()));
    assertDenied(() -> recruitment.detail(outsider, null, application.id()));
    assertDenied(() -> recruitment.apply(outsider, job.id(), applyInput(false)));
    assertDenied(() -> recruitment.updateJob(outsider, company.id(), job.id(), input("攻击", "跨公司编辑", job.version())));
    assertThat(recruitment.mine(outsider, 0).items()).isEmpty();
  }

  @Test void concurrentDuplicateSubmissionsCreateOnlyOneApplicationAndSnapshot() throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Callable<Long>> tasks = java.util.stream.IntStream.range(0, 4)
          .mapToObj(i -> (Callable<Long>) () -> recruitment.apply(candidate, job.id(), applyInput(false)).id()).toList();
      var ids = executor.invokeAll(tasks).stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).toList();
      assertThat(ids).containsOnly(ids.getFirst());
    }
    assertThat(recruitment.mine(candidate, 0).items()).hasSize(1);
    long snapshots = tx(() -> store.one(Long.class,
        "select count(r) from HiringResumeRevision r where userAccountId=?1", candidate.databaseId()).orElseThrow());
    assertThat(snapshots).isEqualTo(1);
  }

  @Test void withdrawalRequiresExplicitResubmissionAndPreservesHistory() {
    var first = recruitment.apply(candidate, job.id(), applyInput(false));
    var withdrawn = recruitment.withdraw(candidate, first.id(), first.version());
    assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
    assertThatThrownBy(() -> recruitment.apply(candidate, job.id(), applyInput(false))).isInstanceOf(BusinessException.class);
    var second = recruitment.apply(candidate, job.id(), applyInput(true));
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.submissionNo()).isEqualTo(2);
    assertThat(second.resumeRevisionId()).isNotEqualTo(first.resumeRevisionId());
    assertThat(recruitment.detail(candidate, null, second.id()).events()).extracting(EventView::action)
        .containsExactly("RESUBMITTED", "WITHDRAWN", "SUBMITTED");
  }

  @Test void lastAdminIsProtectedAndDisabledRecruiterImmediatelyLosesAccess() {
    assertThatThrownBy(() -> organizations.updateMember(admin, company.id(), admin.databaseId(), new MemberUpdate(Role.ADMIN, false)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("最后一名");
    var invitation = organizations.invite(admin, company.id(), new MemberInput(outsider.email(), Role.RECRUITER));
    assertDenied(() -> organizations.accept(candidate, invitation.token()));
    organizations.accept(outsider, invitation.token());
    assertThat(recruitment.companyJobs(outsider, company.id(), 0).items()).isEmpty();
    recruitment.assignJob(admin, company.id(), job.id(), outsider.databaseId(), true);
    assertThat(recruitment.companyJobs(outsider, company.id(), 0).items()).hasSize(1);
    organizations.updateMember(admin, company.id(), outsider.databaseId(), new MemberUpdate(Role.RECRUITER, false));
    assertDenied(() -> recruitment.companyJobs(outsider, company.id(), 0));
    assertDenied(() -> organizations.accept(outsider, invitation.token()));
  }

  @Test void oldInvitationCannotOverrideNewerRoleAndInterviewerCannotReadAllApplicants() {
    var first = organizations.invite(admin, company.id(), new MemberInput(outsider.email(), Role.ADMIN));
    var newer = organizations.invite(admin, company.id(), new MemberInput(outsider.email(), Role.INTERVIEWER));
    organizations.accept(outsider, newer.token());
    assertThatThrownBy(() -> organizations.accept(outsider, first.token())).isInstanceOf(BusinessException.class);
    recruitment.assignJob(admin, company.id(), job.id(), outsider.databaseId(), true);
    assertDenied(() -> recruitment.applications(outsider, company.id(), job.id(), 0));
    assertDenied(() -> recruitment.updateJob(outsider, company.id(), job.id(), input("不允许", "不允许", job.version())));
  }

  @Test void closeAndStaleVersionBlockNewSubmissionsAndEdits() {
    assertThatThrownBy(() -> recruitment.apply(candidate, job.id(), new ApplicationInput(resumeId, 99, false)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("更新");
    recruitment.closeJob(admin, company.id(), job.id(), job.version());
    assertThatThrownBy(() -> recruitment.apply(candidate, job.id(), applyInput(false)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("关闭");
    assertThatThrownBy(() -> recruitment.updateJob(admin, company.id(), job.id(), input("旧编辑", "旧描述", job.version())))
        .isInstanceOf(BusinessException.class).hasMessageContaining("刷新");
  }

  @Test void simultaneousAdminDemotionsAlwaysRetainOneAdministrator() throws Exception {
    var invitation = organizations.invite(admin, company.id(), new MemberInput(outsider.email(), Role.ADMIN));
    organizations.accept(outsider, invitation.token());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var tasks = List.<Callable<Boolean>>of(
          () -> { start.await(); return demoteSelf(admin); },
          () -> { start.await(); return demoteSelf(outsider); });
      var first = executor.submit(tasks.get(0)); var second = executor.submit(tasks.get(1)); start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }
    assertThat(tx(() -> store.one(Long.class,
        "select count(m) from HiringMembership m where organizationId=?1 and role='ADMIN' and active=true", company.id()).orElseThrow())).isEqualTo(1L);
  }

  @Test void platformAccessRequiresExplicitProvisioningAndDisablingCompanyHidesJobs() {
    assertThat(platform.allowed(admin)).isFalse();
    assertDenied(() -> platform.organizations(admin, 0));
    tx(() -> { var operator = new HiringEntities.PlatformOperator(); operator.userAccountId = outsider.databaseId(); operator.active = true; return store.add(operator); });
    assertThat(platform.allowed(outsider)).isTrue();
    platform.setActive(outsider, company.id(), false);
    assertDenied(() -> recruitment.publicJob(job.id()));
    assertDenied(() -> recruitment.companyJobs(admin, company.id(), 0));
    assertDenied(() -> recruitment.apply(candidate, job.id(), applyInput(false)));
    platform.setActive(outsider, company.id(), true);
    assertThat(recruitment.publicJob(job.id()).id()).isEqualTo(job.id());
  }

  @Test void organizationRenameIsRestrictedAndClosedJobCannotBeReopened() {
    assertDenied(() -> organizations.rename(outsider, company.id(), new OrganizationInput("越权")));
    organizations.rename(admin, company.id(), new OrganizationInput("新名称"));
    assertThat(recruitment.publicJob(job.id()).organizationName()).isEqualTo("新名称");
    var closed = recruitment.closeJob(admin, company.id(), job.id(), job.version());
    assertThatThrownBy(() -> recruitment.publishJob(admin, company.id(), job.id(), closed.version())).isInstanceOf(BusinessException.class);
    var draft = recruitment.createJob(admin, company.id(), input("草稿", "草稿要求", 0));
    assertThatThrownBy(() -> recruitment.closeJob(admin, company.id(), draft.id(), draft.version())).isInstanceOf(BusinessException.class);
  }

  private boolean demoteSelf(CurrentUser user) {
    try { organizations.updateMember(user, company.id(), user.databaseId(), new MemberUpdate(Role.RECRUITER, true)); return true; }
    catch (BusinessException conflict) { assertThat(conflict.getMessage()).contains("最后一名"); return false; }
  }

  private ApplicationInput applyInput(boolean resubmit) { return new ApplicationInput(resumeId, job.publishedRevision(), resubmit); }
  private JobInput input(String title, String description, long version) { return new JobInput(title, description, "上海", "实习", version); }
  private CurrentUser account(String name) {
    return tx(() -> { var account = store.add(UserAccountEntity.register(UUID.randomUUID() + "@example.test", "!", name));
      return new CurrentUser(account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName()); });
  }
  private <T> T tx(java.util.function.Supplier<T> operation) { return new TransactionTemplate(manager).execute(s -> operation.get()); }
  private void assertDenied(Runnable operation) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.status().value()).isIn(403, 404));
  }
}
