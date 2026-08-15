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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.infrastructure.JobProfileRepository;
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
  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_resume_delete");

  @org.springframework.test.context.DynamicPropertySource
  static void databaseProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
  @Autowired JobProfileRepository jobs;

  private UserAccountEntity userA;
  private UserAccountEntity userB;

  @BeforeEach
  void setUp() {
    sessions.deleteAll();
    jobs.deleteAll();
    tasks.deleteAll();
    resumes.deleteAll();
    userA = users.save(UserAccountEntity.register(
        "resume-delete-a-" + UUID.randomUUID() + "@example.com", "!", "Owner A"));
    userB = users.save(UserAccountEntity.register(
        "resume-delete-b-" + UUID.randomUUID() + "@example.com", "!", "Owner B"));
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void deleteReadyResumeRemovesResumeAndTaskButKeepsInterviews() throws Exception {
    ResumeEntity resume = resumeFor(userA.getId(), ResumeStatus.READY);
    InterviewSessionEntity session = sessionUsing(resume.getId(), userA.getId());

    mvc.perform(delete("/api/resumes/{id}", resume.getId()).with(authentication(principal(userA))))
        .andExpect(status().isNoContent());

    assertThat(resumes.findById(resume.getId())).isEmpty();
    assertThat(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.RESUME_ANALYSIS, "resume:" + resume.getId(), userA.getId())).isEmpty();
    InterviewSessionEntity kept = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(kept.getResumeId()).isNull();
  }

  @Test
  void deleteRejectsResumeWithAnalysisInProgress() throws Exception {
    ResumeEntity resume = resumeFor(userA.getId(), ResumeStatus.ANALYZING);

    mvc.perform(delete("/api/resumes/{id}", resume.getId()).with(authentication(principal(userA))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESUME_ANALYSIS_IN_PROGRESS"));

    assertThat(resumes.findById(resume.getId())).isPresent();
  }

  @Test
  void deleteReturnsNotFoundForAnotherUsersResume() throws Exception {
    ResumeEntity resume = resumeFor(userA.getId(), ResumeStatus.READY);

    mvc.perform(delete("/api/resumes/{id}", resume.getId()).with(authentication(principal(userB))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));

    assertThat(resumes.findById(resume.getId())).isPresent();
  }

  private ResumeEntity resumeFor(Long ownerId, ResumeStatus status) {
    ResumeEntity resume = resumes.saveAndFlush(ResumeEntity.pending(
        ownerId, "candidate.txt", UUID.randomUUID().toString().replace("-", ""), "Candidate resume"));
    resume.setStatus(status);
    resume = resumes.saveAndFlush(resume);
    tasks.saveAndFlush(AsyncTaskEntity.pending(
        ownerId, AsyncTaskType.RESUME_ANALYSIS, "resume:" + resume.getId(),
        "{\"resumeId\":" + resume.getId() + "}"));
    return resume;
  }

  private InterviewSessionEntity sessionUsing(Long resumeId, Long ownerId) {
    JobProfileEntity job = jobs.saveAndFlush(JobProfileEntity.create(
        ownerId, "后端工程师", "JD", "{\"competencies\":[\"Java\"],\"preferredSkills\":[]}"));
    return sessions.saveAndFlush(InterviewSessionEntity.create(
        ownerId, resumeId, job.getId(), Difficulty.MEDIUM, 8, "deepseek", "deepseek-chat", "{}"));
  }

  private static Authentication principal(UserAccountEntity account) {
    CurrentUser user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
