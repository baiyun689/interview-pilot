package interview.pilot.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.PlatformTransactionManager;

import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.ai.provider.AiSettingRepository;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.domain.UserStatus;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;

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

  @BeforeEach
  void resetRepositoryDefaults() {
    when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void registerCreatesSessionAndMeReturnsTheUser() throws Exception {
    UserAccountEntity account = account(41L, "user@example.com", "小白", UserStatus.ACTIVE,
        "correct horse battery staple");
    when(accounts.save(any(UserAccountEntity.class))).thenReturn(account);
    when(accounts.findByEmail("user@example.com"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(account));

    MvcResult result = mvc.perform(csrfPost("/api/auth/register")
            .contentType("application/json")
            .content("""
                {"email":"User@Example.com","password":"correct horse battery staple",
                 "displayName":"小白"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value("user@example.com"))
        .andReturn();

    mvc.perform(get("/api/auth/me").session((MockHttpSession) result.getRequest().getSession()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("小白"));
  }

  @Test
  void duplicateEmailReturnsConflict() throws Exception {
    when(accounts.findByEmail("user@example.com")).thenReturn(Optional.of(
        account(42L, "user@example.com", "Existing", UserStatus.ACTIVE, "password")));

    mvc.perform(csrfPost("/api/auth/register").contentType("application/json")
            .content("{\"email\":\"User@Example.com\",\"password\":\"password\",\"displayName\":\"New\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
  }

  @Test
  void invalidPasswordReturnsAuthenticationFailed() throws Exception {
    when(accounts.findByEmail("user@example.com")).thenReturn(Optional.of(
        account(43L, "user@example.com", "小白", UserStatus.ACTIVE, "correct-password")));

    mvc.perform(csrfPost("/api/auth/login").contentType("application/json")
            .content("{\"email\":\"User@Example.com\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
  }

  @Test
  void disabledUserCannotLogIn() throws Exception {
    when(accounts.findByEmail("disabled@example.com")).thenReturn(Optional.of(
        account(44L, "disabled@example.com", "Disabled", UserStatus.DISABLED, "password")));

    mvc.perform(csrfPost("/api/auth/login").contentType("application/json")
            .content("{\"email\":\"disabled@example.com\",\"password\":\"password\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
  }

  @Test
  void anonymousMeRequestIsUnauthorizedAndMaterializesCsrfCookie() throws Exception {
    mvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().exists("XSRF-TOKEN"));
  }

  @Test
  void anonymousResumeRequestIsUnauthorized() throws Exception {
    mvc.perform(get("/api/resumes"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void registerRotatesAnExistingAnonymousSessionBeforeSavingAuthentication() throws Exception {
    UserAccountEntity account = account(45L, "register@example.com", "Register", UserStatus.ACTIVE,
        "correct horse battery staple");
    when(accounts.save(any(UserAccountEntity.class))).thenReturn(account);
    when(accounts.findByEmail("register@example.com"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(account));
    MockHttpSession anonymousSession = new MockHttpSession();
    String previousSessionId = anonymousSession.getId();

    mvc.perform(csrfPost("/api/auth/register", anonymousSession)
            .contentType("application/json")
            .content("""
                {"email":"Register@Example.com","password":"correct horse battery staple",
                 "displayName":"Register"}
                """))
        .andExpect(status().isCreated());

    assertThat(anonymousSession.getId()).isNotEqualTo(previousSessionId);
    mvc.perform(get("/api/auth/me").session(anonymousSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("register@example.com"));
  }

  @Test
  void loginRotatesAnExistingAnonymousSessionBeforeSavingAuthentication() throws Exception {
    UserAccountEntity account = account(46L, "login@example.com", "Login", UserStatus.ACTIVE,
        "correct-password");
    when(accounts.findByEmail("login@example.com")).thenReturn(Optional.of(account));
    MockHttpSession anonymousSession = new MockHttpSession();
    String previousSessionId = anonymousSession.getId();

    mvc.perform(csrfPost("/api/auth/login", anonymousSession)
            .contentType("application/json")
            .content("{\"email\":\"Login@Example.com\",\"password\":\"correct-password\"}"))
        .andExpect(status().isOk());

    assertThat(anonymousSession.getId()).isNotEqualTo(previousSessionId);
    mvc.perform(get("/api/auth/me").session(anonymousSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("login@example.com"));
  }

  private MockHttpServletRequestBuilder csrfPost(String path) throws Exception {
    return csrfPost(path, new MockHttpSession());
  }

  private MockHttpServletRequestBuilder csrfPost(String path, MockHttpSession session) throws Exception {
    MvcResult csrfResult = mvc.perform(get("/api/auth/me").session(session))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().exists("XSRF-TOKEN"))
        .andReturn();
    var token = csrfResult.getResponse().getCookie("XSRF-TOKEN");
    return post(path).session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue());
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
