package interview.pilot.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.ai.provider.AiSettingRepository;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.domain.UserStatus;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;

@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@AutoConfigureMockMvc
class AuthControllerTest {
  @MockitoBean RedissonClient redissonClient;
  @MockitoBean AiSettingRepository aiSettingRepository;
  @MockitoBean ResumeRepository resumeRepository;
  @MockitoBean AsyncTaskRepository asyncTaskRepository;
  @MockitoBean JobProfileRepository jobProfileRepository;
  @MockitoBean InterviewSessionRepository interviewSessionRepository;
  @MockitoBean InterviewTurnRepository interviewTurnRepository;
  @MockitoBean AnswerAttemptRepository answerAttemptRepository;
  @MockitoBean InterviewReportRepository interviewReportRepository;
  @MockitoBean PlatformTransactionManager transactionManager;
  @MockitoBean AiProviderService aiProviderService;
  @MockitoBean UserAccountRepository accounts;
  @MockitoBean RateLimiter rateLimiter;
  @MockitoBean KnowledgeBaseJpaRepository knowledgeBaseJpaRepository;
  @MockitoBean KnowledgeDocumentJpaRepository knowledgeDocumentJpaRepository;
  @MockitoBean InterviewKnowledgeBaseRepository interviewKnowledgeBaseRepository;

  @Autowired MockMvc mvc;
  @Autowired PasswordEncoder passwordEncoder;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void resetRepositoryDefaults() {
    when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(redissonClient.getBucket(anyString())).thenReturn(mock(RBucket.class));
  }

  @Test
  void registerReturnsTokenPairAndRefreshCookie() throws Exception {
    UserAccountEntity account = account(41L, "user@example.com", "小白", UserStatus.ACTIVE,
        "correct horse battery staple");
    when(accounts.save(any(UserAccountEntity.class))).thenReturn(account);
    when(accounts.findByEmail("user@example.com"))
        .thenReturn(Optional.empty())   // email check: not taken
        .thenReturn(Optional.of(account));

    mvc.perform(post("/api/auth/register")
            .contentType("application/json")
            .content("""
                {"email":"User@Example.com","password":"correct horse battery staple",
                 "displayName":"小白"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.email").value("user@example.com"))
        .andExpect(jsonPath("$.displayName").value("小白"))
        .andExpect(cookie().exists("refresh_token"));
  }

  @Test
  void duplicateEmailReturnsConflict() throws Exception {
    when(accounts.findByEmail("user@example.com")).thenReturn(Optional.of(
        account(42L, "user@example.com", "Existing", UserStatus.ACTIVE, "password")));

    mvc.perform(post("/api/auth/register").contentType("application/json")
            .content("{\"email\":\"User@Example.com\",\"password\":\"password\",\"displayName\":\"New\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
  }

  @Test
  void invalidPasswordReturnsAuthenticationFailed() throws Exception {
    when(accounts.findByEmail("user@example.com")).thenReturn(Optional.of(
        account(43L, "user@example.com", "小白", UserStatus.ACTIVE, "correct-password")));

    mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"email\":\"User@Example.com\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
  }

  @Test
  void disabledUserCannotLogIn() throws Exception {
    when(accounts.findByEmail("disabled@example.com")).thenReturn(Optional.of(
        account(44L, "disabled@example.com", "Disabled", UserStatus.DISABLED, "password")));

    mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"email\":\"disabled@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
  }

  @Test
  void anonymousRequestIsUnauthorized() throws Exception {
    mvc.perform(get("/api/resumes"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginReturnsTokensAndRefreshCookie() throws Exception {
    UserAccountEntity account = account(46L, "login@example.com", "Login", UserStatus.ACTIVE,
        "correct-password");
    when(accounts.findByEmail("login@example.com")).thenReturn(Optional.of(account));

    mvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"email\":\"Login@Example.com\",\"password\":\"correct-password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.email").value("login@example.com"))
        .andExpect(cookie().exists("refresh_token"));
  }

  @Test
  void logoutClearsRefreshCookie() throws Exception {
    mvc.perform(post("/api/auth/logout"))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge("refresh_token", 0));
  }

  private UserAccountEntity account(
      long id, String email, String displayName, UserStatus status, String password) throws Exception {
    UserAccountEntity account = UserAccountEntity.register(
        email, passwordEncoder.encode(password), displayName);
    set(account, "id", id);
    set(account, "status", status);
    set(account, "userId", UUID.randomUUID());
    return account;
  }

  private static void set(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
