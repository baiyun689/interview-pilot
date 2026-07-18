package interview.pilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.resume.infrastructure.ResumeTextExtractor;

@SpringBootTest(properties =
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4")
@Testcontainers
class ResumeUploadServiceTest {
  @MockitoBean
  private RedissonClient redissonClient;

  private static final String CLEANED_TEXT =
      "Senior Java engineer with Spring Boot, PostgreSQL, Redis, messaging, testing, and cloud experience.";

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_resume_test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired
  private ResumeUploadService service;

  @Autowired
  private ResumeQueryService queryService;

  @Autowired
  private ResumeRepository resumeRepository;

  @MockitoSpyBean
  private ResumeTextExtractor extractor;

  @Autowired
  private AsyncTaskRepository taskRepository;

  @BeforeEach
  void cleanDatabase() {
    taskRepository.deleteAll();
    resumeRepository.deleteAll();
  }

  @Test
  void newUploadCreatesPendingResumeAndAnalysisTask() throws Exception {
    var result = service.upload(txt("candidate.txt", CLEANED_TEXT));

    assertThat(result.duplicate()).isFalse();
    var resume = resumeRepository.findById(result.resumeId()).orElseThrow();
    assertThat(resume.getOriginalFilename()).isEqualTo("candidate.txt");
    assertThat(resume.getParsedText()).isEqualTo(CLEANED_TEXT);
    assertThat(resume.getContentHash()).isEqualTo(sha256(CLEANED_TEXT));
    assertThat(resume.getStatus()).isEqualTo(ResumeStatus.PENDING);
    assertThat(resume.getUserAccountId()).isEqualTo(1L);

    var task = taskRepository.findByTaskTypeAndBizKey(
        AsyncTaskType.RESUME_ANALYSIS, "resume:" + result.resumeId()).orElseThrow();
    assertThat(result.analysisTaskId()).isEqualTo(task.getTaskId());
    assertThat(taskRepository.findByTaskId(result.analysisTaskId()))
        .map(taskEntity -> taskEntity.getId())
        .contains(task.getId());
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getAttemptCount()).isZero();
    assertThat(task.getPayloadSnapshot()).contains(result.resumeId().toString());
  }

  @Test
  void duplicateCleanedContentReturnsExistingResumeAndUniqueTask() {
    var first = service.upload(txt("first.txt", CLEANED_TEXT));
    var duplicate = service.upload(txt(
        "renamed.txt",
        "\r\n" + CLEANED_TEXT + "   \r\n\r\n\r\n"));

    assertThat(first.duplicate()).isFalse();
    assertThat(duplicate.duplicate()).isTrue();
    assertThat(duplicate.resumeId()).isEqualTo(first.resumeId());
    assertThat(duplicate.analysisTaskId()).isEqualTo(first.analysisTaskId());
    assertThat(resumeRepository.count()).isEqualTo(1);
    assertThat(taskRepository.count()).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateUploadsReturnTheSameResumeAndTaskWithoutError() throws Exception {
    var bothTransactionsObservedAnEmptyDatabase = new CyclicBarrier(2);
    doAnswer(invocation -> {
      Object result = invocation.callRealMethod();
      resumeRepository.count();
      bothTransactionsObservedAnEmptyDatabase.await(10, TimeUnit.SECONDS);
      return result;
    }).when(extractor).extract(any());

    var start = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        return service.upload(txt("first.txt", CLEANED_TEXT));
      });
      var second = executor.submit(() -> {
        start.await(10, TimeUnit.SECONDS);
        return service.upload(txt("second.txt", CLEANED_TEXT));
      });

      var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(results).extracting(ResumeUploadService.UploadResumeResult::duplicate)
          .containsExactlyInAnyOrder(false, true);
      assertThat(results).extracting(ResumeUploadService.UploadResumeResult::resumeId)
          .containsOnly(results.getFirst().resumeId());
      assertThat(results).extracting(ResumeUploadService.UploadResumeResult::analysisTaskId)
          .containsOnly(results.getFirst().analysisTaskId());
      assertThat(resumeRepository.count()).isEqualTo(1);
      assertThat(taskRepository.count()).isEqualTo(1);
      assertThat(resumeRepository.findAll())
          .singleElement()
          .extracting(ResumeEntity::getUserAccountId)
          .isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void queryResponsesResolveTheUniqueAnalysisTaskAndLeaveProfileNull() {
    var uploaded = service.upload(txt("candidate.txt", CLEANED_TEXT));

    var detail = queryService.get(uploaded.resumeId());
    assertThat(detail.id()).isEqualTo(uploaded.resumeId());
    assertThat(detail.analysisTaskId()).isEqualTo(uploaded.analysisTaskId());
    assertThat(detail.profile()).isNull();
    assertThat(queryService.list()).extracting("analysisTaskId")
        .containsExactly(uploaded.analysisTaskId());
  }

  @Test
  void uploadMethodKeepsDocumentExtractionOutsideTheTransaction() throws Exception {
    var method = ResumeUploadService.class.getMethod(
        "upload", org.springframework.web.multipart.MultipartFile.class);
    assertThat(method.getAnnotation(Transactional.class)).isNull();
  }

  private static MockMultipartFile txt(String filename, String content) {
    return new MockMultipartFile(
        "file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(String content) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
  }
}
