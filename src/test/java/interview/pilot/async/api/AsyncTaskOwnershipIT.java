package interview.pilot.async.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.ratelimit.RateLimiter;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class AsyncTaskOwnershipIT {
  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_task_ownership");

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
  @Autowired AsyncTaskRepository tasks;

  private UserAccountEntity userA;
  private UserAccountEntity userB;

  @BeforeEach
  void setUp() {
    tasks.deleteAll();
    userA = users.save(UserAccountEntity.register(
        "task-owner-a-" + UUID.randomUUID() + "@example.com", "!", "Owner A"));
    userB = users.save(UserAccountEntity.register(
        "task-owner-b-" + UUID.randomUUID() + "@example.com", "!", "Owner B"));
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void userCannotReadOrRetryAnotherUsersTask() throws Exception {
    UUID taskId = failedTask(userA.getId());

    mvc.perform(get("/api/tasks/{taskId}", taskId).with(authentication(principal(userB))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    mvc.perform(post("/api/tasks/{taskId}/retry", taskId).with(authentication(principal(userB))).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
  }

  private UUID failedTask(Long ownerId) {
    AsyncTaskEntity task = AsyncTaskEntity.pending(
        ownerId, AsyncTaskType.RESUME_ANALYSIS, "resume:42", "{\"resumeId\":42}");
    task.setStatus(AsyncTaskStatus.FAILED);
    return tasks.saveAndFlush(task).getTaskId();
  }

  private static Authentication principal(UserAccountEntity account) {
    CurrentUser user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
