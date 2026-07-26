package interview.pilot.interview.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
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
class InterviewOwnershipIT {
  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_interview_ownership");

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
  @Autowired JobProfileRepository jobs;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewTurnRepository turns;
  @Autowired AsyncTaskRepository tasks;

  private UserAccountEntity userA;
  private UserAccountEntity userB;

  @BeforeEach
  void setUp() {
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class)))
        .thenReturn(true);
    tasks.deleteAll();
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    resumes.deleteAll();
    userA = users.save(UserAccountEntity.register(
        "interview-owner-a-" + UUID.randomUUID() + "@example.com", "!", "Owner A"));
    userB = users.save(UserAccountEntity.register(
        "interview-owner-b-" + UUID.randomUUID() + "@example.com", "!", "Owner B"));
  }

  @Test
  void userCannotCreateFromOrReadAnotherUsersInterview() throws Exception {
    Long foreignResume = readyResume(userA.getId());
    UUID sessionId = interview(userA.getId(), foreignResume);

    mvc.perform(get("/api/interviews/{id}", sessionId).with(authentication(principal(userA))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/interviews").with(authentication(principal(userA))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sessionId").value(sessionId.toString()));
    mvc.perform(get("/api/interviews/{id}/report", sessionId).with(authentication(principal(userA))))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .with(authentication(principal(userA))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"answer\":\"answer\"}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/interviews").with(authentication(principal(userB))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(createRequest(foreignResume)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
    mvc.perform(get("/api/interviews/{id}", sessionId).with(authentication(principal(userB))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INTERVIEW_NOT_FOUND"));
    mvc.perform(get("/api/interviews").with(authentication(principal(userB))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(get("/api/interviews/{id}/report", sessionId).with(authentication(principal(userB))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INTERVIEW_NOT_FOUND"));
    mvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .with(authentication(principal(userB))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"answer\":\"answer\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INTERVIEW_NOT_FOUND"));
  }

  private Long readyResume(Long ownerId) {
    ResumeEntity resume = ResumeEntity.pending(
        ownerId, "candidate.txt", UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""), "Java experience");
    resume.setStatus(ResumeStatus.READY);
    resume.setSkillsSnapshot("""
        {"summary":"Java engineer","technicalSkills":["Java"],"projects":[],
         "strengths":["Reliable APIs"],"risks":[]}
        """);
    return resumes.saveAndFlush(resume).getId();
  }

  private UUID interview(Long ownerId, Long resumeId) {
    JobProfileEntity job = jobs.saveAndFlush(JobProfileEntity.create(
        ownerId, "Backend Engineer", "Java services",
        "{\"competencies\":[\"Java\"],\"tools\":[]}"));
    InterviewSessionEntity session = InterviewSessionEntity.create(
        ownerId, resumeId, job.getId(), Difficulty.MEDIUM, 5, "provider", "model",
        "{\"competencies\":[\"Java\"],\"totalTurnBudget\":5}");
    session.start();
    session = sessions.saveAndFlush(session);
    turns.saveAndFlush(InterviewTurnEntity.firstAsked(
        session.getId(), Difficulty.MEDIUM, "Explain Java concurrency.", "Java"));
    return session.getSessionId();
  }

  private static String createRequest(Long resumeId) {
    return """
        {"resumeId":%d,"jobTitle":"Backend Engineer","jdText":"Java services",
         "difficulty":"MEDIUM","totalTurnBudget":5,"providerId":"qwen","skillId":"java-backend"}
        """.formatted(resumeId);
  }

  private static Authentication principal(UserAccountEntity account) {
    CurrentUser user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
