package interview.pilot.resume.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class ResumeDeleteIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_resume_delete");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean RedissonClient redissonClient;
  @MockitoBean RateLimiter rateLimiter;
  @Autowired MockMvc mvc;
  @Autowired UserAccountRepository users;
  @Autowired ResumeRepository resumes;
  @Autowired AsyncTaskRepository tasks;
  @Autowired InterviewSessionRepository sessions;
  private UserAccountEntity owner;
  private UserAccountEntity other;

  @BeforeEach
  void setUp() {
    sessions.deleteAll(); tasks.deleteAll(); resumes.deleteAll();
    owner = users.save(UserAccountEntity.register(
        "resume-a-" + UUID.randomUUID() + "@example.com", "!", "Owner"));
    other = users.save(UserAccountEntity.register(
        "resume-b-" + UUID.randomUUID() + "@example.com", "!", "Other"));
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void deleteReadyResumeKeepsTheImmutableInterviewAndClearsItsReference() throws Exception {
    ResumeEntity resume = resumeFor(owner.getId(), ResumeStatus.READY);
    InterviewSessionEntity session = sessions.saveAndFlush(InterviewSessionEntity.preparing(
        owner.getId(), resume.getId(), Difficulty.MEDIUM, InterviewSize.QUICK,
        JobSourceType.CUSTOM, "后端工程师", "dashscope", "qwen", "{}", null));

    mvc.perform(delete("/api/resumes/{id}", resume.getId())
            .with(authentication(principal(owner))))
        .andExpect(status().isNoContent());

    assertThat(resumes.findById(resume.getId())).isEmpty();
    assertThat(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.RESUME_ANALYSIS, "resume:" + resume.getId(), owner.getId())).isEmpty();
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getResumeId()).isNull();
  }

  @Test
  void activeAnalysisAndCrossUserDeletionAreRejected() throws Exception {
    ResumeEntity analyzing = resumeFor(owner.getId(), ResumeStatus.ANALYZING);
    mvc.perform(delete("/api/resumes/{id}", analyzing.getId())
            .with(authentication(principal(owner))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESUME_ANALYSIS_IN_PROGRESS"));

    ResumeEntity ready = resumeFor(owner.getId(), ResumeStatus.READY);
    mvc.perform(delete("/api/resumes/{id}", ready.getId())
            .with(authentication(principal(other))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
  }

  private ResumeEntity resumeFor(Long ownerId, ResumeStatus status) {
    ResumeEntity resume = resumes.saveAndFlush(ResumeEntity.pending(
        ownerId, "candidate.txt", UUID.randomUUID().toString().replace("-", ""), "Candidate"));
    resume.setStatus(status);
    resume = resumes.saveAndFlush(resume);
    tasks.saveAndFlush(AsyncTaskEntity.pending(
        ownerId, AsyncTaskType.RESUME_ANALYSIS, "resume:" + resume.getId(), "{}"));
    return resume;
  }

  private UsernamePasswordAuthenticationToken principal(UserAccountEntity account) {
    var current = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        current, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
