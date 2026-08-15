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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseEntity;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.infrastructure.JobProfileRepository;
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
  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_kb_delete");

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
  @Autowired KnowledgeBaseRepository bases;
  @Autowired KnowledgeDocumentRepository documents;
  @Autowired KnowledgeBaseJpaRepository baseJpa;
  @Autowired KnowledgeDocumentJpaRepository documentJpa;
  @Autowired InterviewKnowledgeBaseRepository associations;
  @Autowired InterviewSessionRepository sessions;
  @Autowired JobProfileRepository jobs;

  private UserAccountEntity userA;
  private UserAccountEntity userB;

  @BeforeEach
  void setUp() {
    associations.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    documentJpa.deleteAll();
    baseJpa.deleteAll();
    userA = users.save(UserAccountEntity.register(
        "kb-delete-a-" + UUID.randomUUID() + "@example.com", "!", "Owner A"));
    userB = users.save(UserAccountEntity.register(
        "kb-delete-b-" + UUID.randomUUID() + "@example.com", "!", "Owner B"));
    when(rateLimiter.allowFixedWindow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void deleteRemovesDocumentsAssociationsAndBaseButKeepsInterviews() throws Exception {
    KnowledgeBaseEntity base = baseFor(userA.getId());
    KnowledgeDocumentEntity doc1 = documentFor(base, "doc-1.md");
    KnowledgeDocumentEntity doc2 = documentFor(base, "doc-2.md");
    InterviewSessionEntity session = sessionFor(userA.getId());
    associations.save(InterviewKnowledgeBaseEntity.create(session.getId(), base.getId()));

    mvc.perform(delete("/api/knowledge-bases/{id}", base.getKnowledgeBaseId())
            .with(authentication(principal(userA))))
        .andExpect(status().isNoContent());

    assertThat(bases.findByKnowledgeBaseIdAndUserAccountId(base.getKnowledgeBaseId(), userA.getId()))
        .isEmpty();
    assertThat(documents.findByDocumentId(doc1.getDocumentId())).isEmpty();
    assertThat(documents.findByDocumentId(doc2.getDocumentId())).isEmpty();
    assertThat(associations.findAllBySessionId(session.getId())).isEmpty();
    assertThat(sessions.findBySessionId(session.getSessionId())).isPresent();
  }

  @Test
  void retryAfterPartialDeletionCompletesWhenBaseIsAlreadyDeleting() throws Exception {
    KnowledgeBaseEntity base = baseFor(userA.getId());
    documentFor(base, "doc-1.md");
    base.beginDeletion();
    bases.save(base);

    mvc.perform(delete("/api/knowledge-bases/{id}", base.getKnowledgeBaseId())
            .with(authentication(principal(userA))))
        .andExpect(status().isNoContent());

    assertThat(bases.findByKnowledgeBaseIdAndUserAccountId(base.getKnowledgeBaseId(), userA.getId()))
        .isEmpty();
  }

  @Test
  void deleteReturnsNotFoundForAnotherUsersBase() throws Exception {
    KnowledgeBaseEntity base = baseFor(userA.getId());

    mvc.perform(delete("/api/knowledge-bases/{id}", base.getKnowledgeBaseId())
            .with(authentication(principal(userB))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));

    assertThat(bases.findByKnowledgeBaseIdAndUserAccountId(base.getKnowledgeBaseId(), userA.getId()))
        .isPresent();
  }

  @Test
  void deleteReturnsNotFoundWhenBaseIsMissing() throws Exception {
    mvc.perform(delete("/api/knowledge-bases/{id}", UUID.randomUUID())
            .with(authentication(principal(userA))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
  }

  private KnowledgeBaseEntity baseFor(Long ownerId) {
    return bases.save(KnowledgeBaseEntity.active(ownerId, "八股库"));
  }

  private KnowledgeDocumentEntity documentFor(KnowledgeBaseEntity base, String filename) {
    return documents.save(KnowledgeDocumentEntity.pending(
        base, filename, UUID.randomUUID().toString().replace("-", ""), "files/" + filename));
  }

  private InterviewSessionEntity sessionFor(Long ownerId) {
    JobProfileEntity job = jobs.saveAndFlush(JobProfileEntity.create(
        ownerId, "后端工程师", "JD", "{\"competencies\":[\"Java\"],\"preferredSkills\":[]}"));
    return sessions.saveAndFlush(InterviewSessionEntity.create(
        ownerId, null, job.getId(), Difficulty.MEDIUM, 8, "deepseek", "deepseek-chat", "{}"));
  }

  private static Authentication principal(UserAccountEntity account) {
    CurrentUser user = new CurrentUser(
        account.getId(), account.getUserId(), account.getEmail(), account.getDisplayName());
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
