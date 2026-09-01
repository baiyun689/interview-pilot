package interview.pilot.knowledge.api;

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

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseEntity;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.knowledge.files-root=./build/tmp/knowledge-delete-test-files",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@AutoConfigureMockMvc
@Testcontainers
class KnowledgeBaseDeleteIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_kb_delete");

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
  @Autowired KnowledgeBaseRepository bases;
  @Autowired KnowledgeDocumentRepository documents;
  @Autowired KnowledgeBaseJpaRepository baseJpa;
  @Autowired KnowledgeDocumentJpaRepository documentJpa;
  @Autowired InterviewKnowledgeBaseRepository associations;
  @Autowired InterviewSessionRepository sessions;
  private UserAccountEntity owner;
  private UserAccountEntity other;

  @BeforeEach
  void setUp() {
    associations.deleteAll(); sessions.deleteAll(); documentJpa.deleteAll(); baseJpa.deleteAll();
    owner = users.save(UserAccountEntity.register(
        "kb-a-" + UUID.randomUUID() + "@example.com", "!", "Owner"));
    other = users.save(UserAccountEntity.register(
        "kb-b-" + UUID.randomUUID() + "@example.com", "!", "Other"));
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void deleteRemovesDocumentsAndAssociationButKeepsInterviewSnapshot() throws Exception {
    KnowledgeBaseEntity base = bases.save(KnowledgeBaseEntity.active(owner.getId(), "八股库"));
    KnowledgeDocumentEntity document = documents.save(KnowledgeDocumentEntity.pending(
        base, "java.md", UUID.randomUUID().toString().replace("-", ""), "files/java.md"));
    InterviewSessionEntity session = sessions.saveAndFlush(InterviewSessionEntity.preparing(
        owner.getId(), null, Difficulty.MEDIUM, InterviewSize.QUICK, JobSourceType.CUSTOM,
        "后端工程师", "dashscope", "qwen", "{}", null));
    associations.save(InterviewKnowledgeBaseEntity.create(session.getId(), base.getId()));

    mvc.perform(delete("/api/knowledge-bases/{id}", base.getKnowledgeBaseId())
            .with(authentication(principal(owner))))
        .andExpect(status().isNoContent());

    assertThat(documents.findByDocumentId(document.getDocumentId())).isEmpty();
    assertThat(associations.findAllBySessionId(session.getId())).isEmpty();
    assertThat(sessions.findBySessionId(session.getSessionId())).isPresent();
  }

  @Test
  void anotherUserCannotDeleteTheBase() throws Exception {
    KnowledgeBaseEntity base = bases.save(KnowledgeBaseEntity.active(owner.getId(), "八股库"));
    mvc.perform(delete("/api/knowledge-bases/{id}", base.getKnowledgeBaseId())
            .with(authentication(principal(other))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
  }

  private UsernamePasswordAuthenticationToken principal(UserAccountEntity account) {
    var current = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        current, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
