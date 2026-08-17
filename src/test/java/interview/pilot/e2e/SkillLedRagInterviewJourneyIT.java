package interview.pilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.interview.application.AnswerGroundingValidator;
import interview.pilot.interview.application.InterviewDecisionContextFactory;
import interview.pilot.interview.application.InterviewPlanCompiler;
import interview.pilot.interview.application.PlanProposal;
import interview.pilot.interview.application.QuestionGroundingValidator;
import interview.pilot.interview.application.CreateInterviewService;
import interview.pilot.interview.application.SubmitAnswerService;
import interview.pilot.interview.application.InterviewReportHandler;
import interview.pilot.interview.application.JobProfileExtractor;
import interview.pilot.interview.application.InterviewPlanner;
import interview.pilot.interview.application.QuestionGenerator;
import interview.pilot.interview.application.AnswerEvaluator;
import interview.pilot.interview.application.ReportGenerator;
import interview.pilot.interview.application.ReportEvidence;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.domain.QuestionContext;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.grounding.DefaultKnowledgeGrounding;
import interview.pilot.interview.grounding.GroundingDirective;
import interview.pilot.interview.grounding.GroundingStatus;
import interview.pilot.interview.grounding.KnowledgeRole;
import interview.pilot.interview.skill.ClasspathInterviewSkillCatalog;
import interview.pilot.interview.strategy.DefaultInterviewStrategy;
import interview.pilot.interview.strategy.TurnDirective;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeChunk;
import interview.pilot.knowledge.retrieval.RetrievalStatus;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.knowledge.application.KnowledgeBaseService;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

@SpringBootTest(properties = {
    "app.async.rabbit.dispatch-initial-delay=1h",
    "app.knowledge.revision-cleanup-initial-delay=1h"
})
@Testcontainers
@Import(SkillLedRagInterviewJourneyIT.DeterministicConfiguration.class)
class SkillLedRagInterviewJourneyIT {
  private final UUID documentId = UUID.randomUUID();
  private static final CurrentUser USER = new CurrentUser(
      1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  private static final AtomicReference<List<ReportEvidence>> REPORT_INPUT = new AtomicReference<>();

  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("skill_led_rag_journey");
  @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(
      DockerImageName.parse("rabbitmq:4-management"));
  @Container static final GenericContainer<?> REDIS = new GenericContainer<>(
      DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
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
    registry.add("app.ai.providers.journey.display-name", () -> "Journey");
    registry.add("app.ai.providers.journey.base-url", () -> "http://localhost");
    registry.add("app.ai.providers.journey.api-key", () -> "test-key");
    registry.add("app.ai.providers.journey.model", () -> "journey-model");
    registry.add("app.ai.providers.journey.enabled", () -> true);
    registry.add("app.ai.providers.journey.timeout", () -> "5s");
  }

  @Autowired CreateInterviewService interviewCreator;
  @Autowired SubmitAnswerService answerService;
  @Autowired InterviewReportHandler reportHandler;
  @Autowired KnowledgeBaseService knowledgeBases;
  @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
  @Autowired KnowledgeDocumentRepository knowledgeDocuments;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewTurnRepository turns;
  @Autowired AnswerAttemptRepository attempts;
  @Autowired InterviewReportRepository reports;
  @Autowired JobProfileRepository jobs;
  @Autowired AsyncTaskRepository tasks;

  @Test
  void skillPlanStrategyGroundingQuestionEvaluationAndNextTurnRemainTraceable() {
    var skill = new ClasspathInterviewSkillCatalog().require("java-backend").snapshot();
    var plan = new InterviewPlanCompiler().compile(
        new PlanProposal(List.of(new PlanProposal.Item(
            "Spring 与事务", 95, "", "JD 核心能力"))),
        ResumeProfile.empty(), new JobRequirements(List.of("Spring 与事务"), List.of()),
        Difficulty.MEDIUM, 5, skill);
    var strategy = new DefaultInterviewStrategy();
    var directive = strategy.firstTurn(plan, Difficulty.MEDIUM);
    var scope = new ValidatedKnowledgeScope(
        UUID.randomUUID(), List.of(UUID.randomUUID()),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(documentId, 2)), "embed-v1");
    var grounding = new DefaultKnowledgeGrounding((validatedScope, intent) ->
        new RetrievedKnowledge(RetrievalStatus.RETRIEVED, intent.query(), "embed-v1",
            List.of(new KnowledgeChunk(
                "source-spring", documentId, "spring.md", 2, 0, "事务传播", 0.93,
                "REQUIRES_NEW 挂起当前事务并开启独立事务", 8)),
            Duration.ofMillis(5), null),
        KnowledgeProperties.testDefaults(4, 12, 0.72, 2_000));

    var snapshot = grounding.ground(scope, GroundingDirective.from(directive));
    var question = new QuestionGroundingValidator().validate(
        new GeneratedQuestion(
            "REQUIRES_NEW 在内部调用失败时会发生什么？", directive.competency(),
            GroundingMode.KNOWLEDGE_ASSISTED, List.of("source-spring")),
        snapshot.toRagContext());
    var evaluation = new AnswerGroundingValidator().validate(
        new AnswerEvaluation(
            78, "说明了代理边界", List.of("候选人指出自调用绕过代理"),
            List.of("缺少回滚规则"), List.of(),
            new InterviewDecision(
                NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP, directive.competency(),
                "回滚规则", "证据尚不完整", 0.9),
            List.of(new AnswerEvaluation.ReferenceFact(
                "source-spring", "REQUIRES_NEW 开启独立事务")), List.of()),
        snapshot.toRagContext().withQuestion(question));
    var decisionContext = new InterviewDecisionContextFactory().create(
        directive.difficulty(), directive.competency(), plan.competencies(),
        1, plan.totalTurnBudget(), 0.55, List.of(), evaluation);
    var next = strategy.nextTurn(plan, decisionContext, evaluation);

    assertThat(directive.retrievalPolicy().allowedUses()).isNotEmpty();
    assertThat(snapshot.status()).isEqualTo(GroundingStatus.RETRIEVED);
    assertThat(question.evidenceRefs()).containsExactly("source-spring");
    assertThat(evaluation.evidence()).containsExactly("候选人指出自调用绕过代理");
    assertThat(evaluation.referenceFacts()).hasSize(1);
    assertThat(next.nextDirective().stageId()).isEqualTo(directive.stageId());
    assertThat(next.nextDirective().probeFocus()).isNotBlank();
  }

  @Test
  void unavailableKnowledgeFallsBackToSkillGeneralQuestion() {
    var grounding = new DefaultKnowledgeGrounding((scope, intent) ->
        RetrievedKnowledge.unavailable(intent.query(), "embed-v1", "QDRANT_DOWN", Duration.ZERO),
        KnowledgeProperties.testDefaults(4, 12, 0.72, 2_000));
    var skill = new ClasspathInterviewSkillCatalog().require("java-backend").snapshot();
    var plan = new InterviewPlanCompiler().compile(
        new PlanProposal(List.of(new PlanProposal.Item("Spring 与事务", 90, "", "核心"))),
        ResumeProfile.empty(), new JobRequirements(List.of("Spring 与事务"), List.of()),
        Difficulty.MEDIUM, 5, skill);
    var directive = new DefaultInterviewStrategy().firstTurn(plan, Difficulty.MEDIUM);
    var scope = new ValidatedKnowledgeScope(
        UUID.randomUUID(), List.of(UUID.randomUUID()),
        List.of(new ValidatedKnowledgeScope.DocumentRevision(documentId, 1)), "embed-v1");

    var snapshot = grounding.ground(scope, GroundingDirective.from(directive));
    var question = new QuestionGroundingValidator().validate(
        new GeneratedQuestion("解释事务传播边界", directive.competency()),
        snapshot.toRagContext());

    assertThat(snapshot.status()).isEqualTo(GroundingStatus.UNAVAILABLE);
    assertThat(question.groundingMode()).isEqualTo(GroundingMode.SKILL_GENERAL);
    assertThat(question.evidenceRefs()).isEmpty();
  }

  @Test
  void persistedApplicationJourneyCarriesSavedCitationsIntoFinalReport() {
    reports.deleteAll();
    attempts.deleteAll();
    tasks.deleteAll();
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    REPORT_INPUT.set(null);

    var baseResponse = knowledgeBases.create(USER, "Spring references");
    var base = knowledgeBaseRepository.findByKnowledgeBaseIdAndUserAccountId(
        baseResponse.knowledgeBaseId(), USER.databaseId()).orElseThrow();
    KnowledgeDocumentEntity document = KnowledgeDocumentEntity.pending(
        base, "spring.md", "a".repeat(64), "test/spring.md");
    int revision = document.beginReindex();
    document.markReady(revision, "transaction reference", 1);
    document = knowledgeDocuments.save(document);

    var created = interviewCreator.create(USER, new CreateInterviewRequest(
        null, "Java backend", "Spring transaction reliability", Difficulty.MEDIUM,
        5, "journey", "java-backend", List.of(baseResponse.knowledgeBaseId())));
    for (int attempt = 0; attempt < 5; attempt++) {
      var session = sessions.findBySessionId(created.sessionId()).orElseThrow();
      if (session.getStatus() == SessionStatus.EVALUATING) break;
      answerService.submit(USER, created.sessionId(), new SubmitAnswerRequest(
          UUID.randomUUID(), "候选人回答包含代理边界、回滚条件和失败处理"));
    }
    assertThat(sessions.findBySessionId(created.sessionId()).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
    var reportTask = tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + created.sessionId()).orElseThrow();

    assertThat(reportHandler.handle(reportTask.getTaskId()))
        .isEqualTo(InterviewReportHandler.Outcome.TERMINAL);
    assertThat(REPORT_INPUT.get()).isNotEmpty().anySatisfy(evidence -> {
      assertThat(evidence.stageId()).isNotBlank();
      assertThat(evidence.planRationale()).isNotBlank();
      assertThat(evidence.evidenceRefs()).contains("source-spring");
      assertThat(evidence.sources()).extracting(ReportEvidence.SourceReference::sourceId)
          .contains("source-spring");
    });
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DeterministicConfiguration {
    @Bean @Primary
    JobProfileExtractor deterministicJobProfileExtractor() {
      return (provider, jd) -> new JobRequirements(List.of("Spring 与事务"), List.of());
    }

    @Bean @Primary
    InterviewPlanner deterministicPlanner(InterviewPlanCompiler compiler) {
      return new InterviewPlanner() {
        @Override
        public interview.pilot.interview.domain.InterviewPlan plan(
            String providerId, ResumeProfile resume, JobRequirements job,
            Difficulty difficulty, int turns) {
          throw new UnsupportedOperationException();
        }

        @Override
        public interview.pilot.interview.domain.InterviewPlan plan(
            String providerId, ResumeProfile resume, JobRequirements job,
            Difficulty difficulty, int turns,
            interview.pilot.interview.skill.SkillSnapshot skill) {
          return compiler.compile(new PlanProposal(List.of(
              new PlanProposal.Item("Spring 与事务", 100, "", "JD 核心能力"))),
              resume, job, difficulty, turns, skill);
        }
      };
    }

    @Bean @Primary
    interview.pilot.interview.grounding.KnowledgeGrounding deterministicGrounding() {
      return (scope, directive) -> {
        if (!directive.enabled()) return new interview.pilot.interview.grounding.GroundingSnapshot(
            GroundingStatus.DISABLED, "", "", List.of(), null);
        var revision = scope.documents().getFirst();
        return new interview.pilot.interview.grounding.GroundingSnapshot(
            GroundingStatus.RETRIEVED, directive.competency(), scope.embeddingVersion(),
            List.of(new interview.pilot.interview.grounding.GroundingSnapshot.Chunk(
                "source-spring", KnowledgeRole.TECHNICAL_REFERENCE,
                revision.documentId(), revision.indexRevision(), "spring.md", 0,
                "transaction", 1, 0.94, "saved reference")), null);
      };
    }

    @Bean @Primary
    QuestionGenerator deterministicQuestions() {
      return new QuestionGenerator() {
        @Override public GeneratedQuestion firstQuestion(
            String providerId, interview.pilot.interview.domain.InterviewPlan plan,
            ResumeProfile resume, JobRequirements job) {
          throw new UnsupportedOperationException();
        }
        @Override public GeneratedQuestion firstQuestion(
            String providerId, interview.pilot.interview.domain.InterviewPlan plan,
            ResumeProfile resume, JobRequirements job,
            interview.pilot.interview.skill.SkillSnapshot skill,
            interview.pilot.interview.rag.RagContextSnapshot rag, TurnDirective directive) {
          return question(directive, rag);
        }
        @Override public GeneratedQuestion nextQuestion(
            String providerId, QuestionContext context,
            interview.pilot.interview.domain.InterviewDecision decision) {
          throw new UnsupportedOperationException();
        }
        @Override public GeneratedQuestion nextQuestion(
            String providerId, String model, QuestionContext context,
            interview.pilot.interview.domain.InterviewDecision decision,
            TurnDirective directive) {
          return question(directive, context.ragSnapshot());
        }
        private GeneratedQuestion question(
            TurnDirective directive, interview.pilot.interview.rag.RagContextSnapshot rag) {
          if (rag.chunks().isEmpty()) {
            return new GeneratedQuestion("请说明项目证据", directive.competency());
          }
          return new GeneratedQuestion("请说明事务代理边界", directive.competency(),
              GroundingMode.KNOWLEDGE_ASSISTED,
              List.of(rag.chunks().getFirst().pointId()));
        }
      };
    }

    @Bean @Primary
    AnswerEvaluator deterministicEvaluator() {
      return request -> new AnswerEvaluation(
          82, "证据有效", List.of("候选人说明了代理边界"), List.of("更多指标"), List.of(),
          new InterviewDecision(
              NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP, request.competency(),
              "失败边界", "继续收集证据", 0.9),
          request.ragContext().status() == interview.pilot.interview.rag.RagStatus.RETRIEVED
              ? List.of(new AnswerEvaluation.ReferenceFact(
                  "source-spring", "事务代理存在调用边界")) : List.of(),
          List.of());
    }

    @Bean @Primary
    ReportGenerator deterministicReportGenerator() {
      return (provider, model, evidence) -> {
        REPORT_INPUT.set(List.copyOf(evidence));
        return new InterviewReport(
            82, Map.of("Spring 与事务", 82), List.of("有工程证据"),
            List.of("补充指标"), "证据与引用链完整");
      };
    }
  }
}
