package interview.pilot.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;

import interview.pilot.ai.AiGateway;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.model.AiResponse;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.InterviewReportResponse;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.application.CreateInterviewService;
import interview.pilot.interview.application.InterviewQueryService;
import interview.pilot.interview.application.InterviewReportHandler;
import interview.pilot.interview.application.PlanProposal;
import interview.pilot.interview.application.SubmitAnswerService;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.resume.application.ResumeAnalysisHandler;
import interview.pilot.resume.application.ResumeUploadService;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic core-business journey; delivery adapters are covered by focused tests. */
@SpringBootTest(properties = "app.async.rabbit.dispatch-initial-delay=1h")
@Testcontainers
@Import(InterviewJourneyIT.WireMockGatewayConfiguration.class)
class InterviewJourneyIT {
  private static final CurrentUser LEGACY_USER = new CurrentUser(
      1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  private static final String SCENARIO = "complete-interview-journey";

  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_journey");
  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer(
      DockerImageName.parse("rabbitmq:4-management"));
  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>(
      DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  static final WireMockServer LLM = new WireMockServer(
      options().dynamicPort().usingFilesUnderClasspath("wiremock"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("app.ai.default-provider", () -> "journey");
    provider(registry, "journey", "journey-model");
    provider(registry, "alternate", "alternate-model");
    registry.add("test.llm.base-url", LLM::baseUrl);
  }

  private static void provider(DynamicPropertyRegistry registry, String id, String model) {
    String prefix = "app.ai.providers." + id;
    registry.add(prefix + ".display-name", () -> id);
    registry.add(prefix + ".base-url", LLM::baseUrl);
    registry.add(prefix + ".api-key", () -> "deterministic-test-key");
    registry.add(prefix + ".model", () -> model);
    registry.add(prefix + ".enabled", () -> true);
    registry.add(prefix + ".timeout", () -> "5s");
  }

  @BeforeAll
  static void startLlm() {
    LLM.start();
  }

  @AfterAll
  static void stopLlm() {
    LLM.stop();
  }

  @Autowired ResumeUploadService uploads;
  @Autowired ResumeAnalysisHandler resumeAnalysis;
  @Autowired CreateInterviewService interviews;
  @Autowired SubmitAnswerService answers;
  @Autowired InterviewReportHandler reportHandler;
  @Autowired InterviewQueryService queries;
  @Autowired AiProviderService providers;
  @Autowired ResumeRepository resumes;
  @Autowired AsyncTaskRepository tasks;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewTurnRepository turns;
  @Autowired InterviewReportRepository reports;
  @Autowired AnswerAttemptRepository attempts;
  @Autowired JobProfileRepository jobs;

  @BeforeEach
  void cleanAndStubLlm() {
    reports.deleteAll();
    attempts.deleteAll();
    tasks.deleteAll();
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    resumes.deleteAll();
    LLM.resetAll();
    stubJourneyResponses();
  }

  @Test
  void completesTxtResumeAdaptiveInterviewAndReportWithoutARealProvider() {
    var file = new MockMultipartFile(
        "file", "candidate.txt", "text/plain",
        "Java backend engineer. Built idempotent APIs with Spring Boot and Redis."
            .getBytes(StandardCharsets.UTF_8));

    var uploaded = uploads.upload(LEGACY_USER, file);
    var resumeTask = tasks.findByTaskId(uploaded.analysisTaskId()).orElseThrow();
    assertThat(resumeAnalysis.handle(uploaded.analysisTaskId()))
        .isEqualTo(ResumeAnalysisHandler.Outcome.TERMINAL);
    assertThat(resumeAnalysis.handle(uploaded.analysisTaskId()))
        .as("duplicate async delivery is idempotent")
        .isEqualTo(ResumeAnalysisHandler.Outcome.TERMINAL);
    assertThat(resumes.findById(uploaded.resumeId()).orElseThrow().getStatus())
        .isEqualTo(ResumeStatus.READY);
    assertThat(tasks.findById(resumeTask.getId()).orElseThrow().getStatus())
        .isEqualTo(AsyncTaskStatus.COMPLETED);

    var created = interviews.create(LEGACY_USER, new CreateInterviewRequest(
        uploaded.resumeId(), "Java AI Backend Engineer",
        "Build reliable Java services with Redis and observability.",
        Difficulty.EASY, 5, "journey"));
    UUID sessionId = created.sessionId();
    assertThat(created.providerId()).isEqualTo("journey");
    assertThat(created.modelName()).isEqualTo("journey-model");

    providers.switchDefault("alternate");
    var orthogonalDecision = answers.submit(LEGACY_USER, sessionId, new SubmitAnswerRequest(
        UUID.randomUUID(), "Concrete mechanism: use a unique request id and a database constraint."));
    assertThat(orthogonalDecision.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(orthogonalDecision.decision().difficultyAdjustment())
        .isEqualTo(DifficultyAdjustment.INCREASE);

    for (int turn = 2; turn <= 3; turn++) {
      answers.submit(LEGACY_USER, sessionId, new SubmitAnswerRequest(
          UUID.randomUUID(), "Concrete mechanism with evidence for turn " + turn));
    }

    var evaluating = sessions.findBySessionId(sessionId).orElseThrow();
    assertThat(evaluating.getStatus()).isEqualTo(SessionStatus.EVALUATING);
    assertThat(evaluating.getProviderId()).isEqualTo("journey");
    assertThat(evaluating.getModelName()).isEqualTo("journey-model");
    assertThat(turns.findAllBySessionIdOrderByTurnNo(evaluating.getId()))
        .hasSize(3)
        .allSatisfy(turn -> assertThat(turn.getRequestId()).isNotNull())
        .extracting(turn -> turn.getRequestId()).doesNotHaveDuplicates();

    var reportTask = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId).orElseThrow();
    assertThat(reportHandler.handle(reportTask.getTaskId()))
        .isEqualTo(InterviewReportHandler.Outcome.TERMINAL);
    assertThat(reportHandler.handle(reportTask.getTaskId()))
        .as("duplicate report delivery is idempotent")
        .isEqualTo(InterviewReportHandler.Outcome.TERMINAL);

    var result = queries.report(LEGACY_USER, sessionId);
    assertThat(result.status().value()).isEqualTo(200);
    var response = (InterviewReportResponse) result.body();
    assertThat(response.report().competencyScores())
        .containsEntry("Java", 88)
        .containsEntry("System Design", 84);
    assertThat(sessions.findBySessionId(sessionId).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.COMPLETED);
    assertThat(tasks.findByTaskId(reportTask.getTaskId()).orElseThrow().getStatus())
        .isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(reports.count()).isEqualTo(1);

    verifyPromptContract(ResumeProfile.class, "严谨的中文简历分析师", 1);
    verifyPromptContract(JobRequirements.class, "资深招聘需求分析师", 1);
    verifyPromptContract(PlanProposal.class, "资深技术面试负责人", 1);
    verifyPromptContract(GeneratedQuestion.class, "中文技术面试官", 3);
    verifyPromptContract(AnswerEvaluation.class, "中文技术面试评审官", 3);
    verifyPromptContract(InterviewReport.class, "资深面试委员会评审", 1);
  }

  private static void stubJourneyResponses() {
    List<Fixture> responses = List.of(
        new Fixture(ResumeProfile.class, "严谨的中文简历分析师",
            "{\"summary\":\"Java backend engineer\",\"technicalSkills\":[\"Java\",\"Spring Boot\",\"Redis\"],\"projects\":[{\"name\":\"InterviewPilot\",\"description\":\"Reliable adaptive interviews\",\"technologies\":[\"Spring Boot\"]}],\"strengths\":[\"Idempotency\"],\"risks\":[\"Scale not measured\"]}"),
        new Fixture(JobRequirements.class, "资深招聘需求分析师",
            "{\"competencies\":[\"Java\",\"System Design\",\"Observability\"],\"preferredSkills\":[\"Redis\"]}"),
        new Fixture(PlanProposal.class, "资深技术面试负责人",
            "{\"items\":[{\"competency\":\"Java\",\"priorityScore\":95,\"resumeEntryPoint\":\"InterviewPilot\",\"rationale\":\"JD required\"},{\"competency\":\"System Design\",\"priorityScore\":90,\"resumeEntryPoint\":\"Reliable adaptive interviews\",\"rationale\":\"JD required\"},{\"competency\":\"Observability\",\"priorityScore\":85,\"resumeEntryPoint\":\"\",\"rationale\":\"JD required\"}]}"),
        new Fixture(GeneratedQuestion.class, "中文技术面试官",
            question("How do you make answer submission idempotent?", "Java")),
        new Fixture(AnswerEvaluation.class, "中文技术面试评审官",
            evaluation("NEXT_TOPIC", "INCREASE", "System Design")),
        new Fixture(GeneratedQuestion.class, "中文技术面试官",
            question("Design the durable interview answer flow.", "System Design")),
        new Fixture(AnswerEvaluation.class, "中文技术面试评审官",
            evaluation("NEXT_TOPIC", "KEEP", "Observability")),
        new Fixture(GeneratedQuestion.class, "中文技术面试官",
            question("How would you observe AI failures?", "Observability")),
        new Fixture(AnswerEvaluation.class, "中文技术面试评审官",
            evaluation("FOLLOW_UP", "DECREASE", "Observability")),
        new Fixture(InterviewReport.class, "资深面试委员会评审",
            "{\"overallScore\":86,\"competencyScores\":{\"Java\":88,\"System Design\":84,\"Observability\":86},\"strengths\":[\"Concrete idempotency evidence\"],\"improvements\":[\"Quantify trade-offs\"],\"summary\":\"Strong backend reasoning grounded in the completed turns.\"}"));
    String state = com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
    for (int index = 0; index < responses.size(); index++) {
      String next = "response-" + (index + 1);
      LLM.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
          .inScenario(SCENARIO)
          .whenScenarioStateIs(state)
          .willSetStateTo(next)
          .withRequestBody(matchingJsonPath(
              "$.responseType", equalTo(responses.get(index).responseType().getName())))
          .withRequestBody(matchingJsonPath(
              "$.messages[0].content", containing(responses.get(index).systemMarker())))
          .willReturn(aResponse().withHeader("Content-Type", "application/json")
              .withBody(openAiResponse(responses.get(index).content()))));
      state = next;
    }
  }

  private static void verifyPromptContract(
      Class<?> responseType, String systemMarker, int expectedCount) {
    LLM.verify(expectedCount, postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
        .withRequestBody(matchingJsonPath("$.responseType", equalTo(responseType.getName())))
        .withRequestBody(matchingJsonPath("$.messages[0].content", containing(systemMarker)))
        .withRequestBody(matchingJsonPath("$.messages[1].content", containing("<"))));
  }

  private record Fixture(Class<?> responseType, String systemMarker, String content) {}

  private static String question(String question, String competency) {
    return "{\"question\":\"" + question + "\",\"targetCompetency\":\""
        + competency + "\"}";
  }

  private static String evaluation(String nextStep, String adjustment, String target) {
    return "{\"score\":85,\"feedback\":\"Evidence-based answer\","
        + "\"evidence\":[\"Concrete mechanism\"],\"missingPoints\":[\"Trade-offs\"],"
        + "\"suggestedDecision\":{\"nextStep\":\"" + nextStep
        + "\",\"difficultyAdjustment\":\"" + adjustment
        + "\",\"targetCompetency\":\"" + target
        + "\",\"probeFocus\":\"failure modes\",\"reason\":\"deterministic fixture\","
        + "\"confidence\":0.9}}";
  }

  private static String openAiResponse(String content) {
    return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
        + quote(content) + "}}]}";
  }

  private static String quote(String value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class WireMockGatewayConfiguration {
    @Bean
    @Primary
    AiGateway deterministicWireMockGateway(
        ObjectMapper objectMapper,
        @org.springframework.beans.factory.annotation.Value("${test.llm.base-url}") String baseUrl) {
      HttpClient client = HttpClient.newHttpClient();
      return request -> invoke(client, objectMapper, baseUrl, request);
    }

    private static AiResponse invoke(
        HttpClient client, ObjectMapper objectMapper, String baseUrl, AiRequest request) {
      try {
        String model = request.expectedModel() == null || request.expectedModel().isBlank()
            ? "journey-model" : request.expectedModel();
        String body = "{\"model\":" + objectMapper.writeValueAsString(model)
            + ",\"messages\":[{\"role\":\"system\",\"content\":"
            + objectMapper.writeValueAsString(request.systemPrompt())
            + "},{\"role\":\"user\",\"content\":"
            + objectMapper.writeValueAsString(request.userPrompt())
            + "}],\"responseType\":"
            + objectMapper.writeValueAsString(request.responseType().getName()) + "}";
        HttpRequest httpRequest = HttpRequest.newBuilder(
                URI.create(baseUrl + "/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
          throw new IllegalStateException("Deterministic LLM returned " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText();
        return new AiResponse(content, model, java.time.Duration.ZERO);
      } catch (Exception exception) {
        throw new IllegalStateException("Deterministic LLM invocation failed", exception);
      }
    }
  }
}
