package interview.pilot.resume.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
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
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class ResumeOwnershipIT {
  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_resume_ownership");

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

  private UserAccountEntity userA;
  private UserAccountEntity userB;

  @BeforeEach
  void setUp() {
    tasks.deleteAll();
    resumes.deleteAll();
    userA = users.save(UserAccountEntity.register(
        "resume-owner-a-" + UUID.randomUUID() + "@example.com", "!", "Owner A"));
    userB = users.save(UserAccountEntity.register(
        "resume-owner-b-" + UUID.randomUUID() + "@example.com", "!", "Owner B"));
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void userCannotReadAnotherUsersResumeOrSeeItInTheirList() throws Exception {
    Long resumeId = readyResume(userA.getId());
    mvc.perform(get("/api/resumes/{id}", resumeId).with(authentication(principal(userB))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
    mvc.perform(get("/api/resumes").with(authentication(principal(userB))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void identicalUploadsForDifferentUsersCreateSeparateOwnerScopedWork() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file", "candidate.txt", "text/plain",
        "Senior Java engineer with Spring Boot, PostgreSQL, Redis, messaging, testing, and cloud "
            .concat("experience.").getBytes());

    mvc.perform(multipart("/api/resumes").file(file).with(authentication(principal(userA))).with(csrf()))
        .andExpect(status().isAccepted());
    mvc.perform(multipart("/api/resumes").file(file).with(authentication(principal(userB))).with(csrf()))
        .andExpect(status().isAccepted());

    assertThat(resumes.findAllByUserAccountIdOrderByCreatedAtDesc(userA.getId())).hasSize(1);
    assertThat(resumes.findAllByUserAccountIdOrderByCreatedAtDesc(userB.getId())).hasSize(1);
    assertThat(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.RESUME_ANALYSIS,
        "resume:" + resumes.findAllByUserAccountIdOrderByCreatedAtDesc(userA.getId()).getFirst().getId(),
        userA.getId())).isPresent();
  }

  private Long readyResume(Long ownerId) {
    ResumeEntity resume = resumes.saveAndFlush(ResumeEntity.pending(
        ownerId, "owner-a.txt", "a".repeat(64), "Owner A resume"));
    tasks.saveAndFlush(AsyncTaskEntity.pending(
        ownerId, AsyncTaskType.RESUME_ANALYSIS, "resume:" + resume.getId(),
        "{\"resumeId\":" + resume.getId() + "}"));
    return resume.getId();
  }

  private static Authentication principal(UserAccountEntity account) {
    CurrentUser user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
