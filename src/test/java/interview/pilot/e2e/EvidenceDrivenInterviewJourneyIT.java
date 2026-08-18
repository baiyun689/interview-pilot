package interview.pilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.application.AnswerEvaluator;
import interview.pilot.interview.application.AnswerProcessingResult;
import interview.pilot.interview.application.QuestionGenerator;
import interview.pilot.interview.application.SubmitAnswerService;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.strategy.TurnDirective;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 无 RAG 的结构化证据驱动多轮旅程：验证证据缺口定向追问、同 stage 切换、
 * stage 门控切换与 FINISH 的结束原因/未完成证据摘要持久化。
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@Testcontainers
class EvidenceDrivenInterviewJourneyIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_evidence_journey");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean private RedissonClient redissonClient;
  @MockitoBean private AnswerEvaluator answerEvaluator;
  @MockitoBean private QuestionGenerator questionGenerator;
  @Autowired private SubmitAnswerService submitService;
  @Autowired private ResumeRepository resumes;
  @Autowired private JobProfileRepository jobs;
  @Autowired private InterviewSessionRepository sessions;
  @Autowired private InterviewTurnRepository turns;
  @Autowired private AnswerAttemptRepository attempts;
  @Autowired private AsyncTaskRepository tasks;
  @Autowired private ObjectMapper objectMapper;

  private static final CurrentUser LEGACY_USER = new CurrentUser(
      1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");

  private UUID sessionId;

  @BeforeEach
  void setUp() throws Exception {
    tasks.deleteAll();
    attempts.deleteAll();
    turns.deleteAll();
    sessions.deleteAll();
    jobs.deleteAll();
    resumes.deleteAll();
    reset(answerEvaluator, questionGenerator);

    ResumeEntity resume = ResumeEntity.pending(1L,
        "candidate.txt", UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""), "Java services");
    resume.setStatus(ResumeStatus.READY);
    resume.setSkillsSnapshot(objectMapper.writeValueAsString(new ResumeProfile(
        "Java engineer", List.of("Java"), List.of(), List.of(), List.of())));
    resume = resumes.saveAndFlush(resume);
    JobProfileEntity job = jobs.saveAndFlush(JobProfileEntity.create(
        "Backend", "Java and Spring",
        "{\"competencies\":[\"Java\",\"MySQL\",\"系统设计\"],\"preferredSkills\":[]}"));
    InterviewSessionEntity session = InterviewSessionEntity.create(
        1L, resume.getId(), job.getId(), Difficulty.MEDIUM, 6,
        "deepseek", "deepseek-chat", objectMapper.writeValueAsString(plan()));
    session.start();
    session = sessions.saveAndFlush(session);
    turns.saveAndFlush(InterviewTurnEntity.firstAsked(
        session.getId(), Difficulty.MEDIUM, "谈谈并发控制方案。", "Java"));
    sessionId = session.getSessionId();

    when(questionGenerator.nextQuestion(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          TurnDirective directive = invocation.getArgument(4);
          return new GeneratedQuestion(
              "请说明" + directive.competency() + "的实践细节", directive.competency());
        });
  }

  @Test
  void collectsEvidenceTargetsThenFinishesSettled() throws Exception {
    when(answerEvaluator.evaluate(any())).thenReturn(
        structured("Java", List.of(assessed("并发边界", false, ""))),
        structured("Java", List.of(assessed("并发边界", true, "用乐观锁版本号"))),
        structured("MySQL", List.of(assessed("索引依据", true, "联合索引"))),
        structured("系统设计", List.of(assessed("取舍分析", true, "容量换一致性"))));

    AnswerProcessingResult turn1 = submit("还没有深入细节");
    assertThat(turn1.decision().nextStep()).isEqualTo(NextStep.FOLLOW_UP);
    assertThat(turn1.nextDirective().probeFocus()).contains("并发边界");

    AnswerProcessingResult turn2 = submit("项目里用乐观锁版本号实现并发控制");
    assertThat(turn2.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(turn2.decision().targetCompetency()).isEqualTo("MySQL");

    AnswerProcessingResult turn3 = submit("我按查询条件设计了联合索引");
    assertThat(turn3.decision().nextStep()).isEqualTo(NextStep.NEXT_TOPIC);
    assertThat(turn3.decision().targetCompetency()).isEqualTo("系统设计");
    assertThat(turn3.nextDirective().reason()).isEqualTo("STAGE_COMPLETED_REQUIRED_EVIDENCE");

    AnswerProcessingResult turn4 = submit("用容量换一致性做的取舍");
    assertThat(turn4.decision().nextStep()).isEqualTo(NextStep.FINISH);
    assertThat(turn4.decision().reason()).isEqualTo("ALL_COMPETENCIES_SETTLED");
    assertThat(turn4.sessionStatus()).isEqualTo(SessionStatus.EVALUATING);

    var stored = turns.findAllBySessionIdOrderByTurnNo(
        sessions.findBySessionId(sessionId).orElseThrow().getId()).getLast();
    AnswerProcessingResult finished = objectMapper.readValue(
        stored.getEvaluationSnapshot(), AnswerProcessingResult.class);
    assertThat(finished.finishReason()).isEqualTo("ALL_COMPETENCIES_SETTLED");
    assertThat(finished.unfinishedEvidence()).isEmpty();
    assertThat(sessions.findBySessionId(sessionId).orElseThrow().getStatus())
        .isEqualTo(SessionStatus.EVALUATING);
    assertThat(tasks.findByTaskTypeAndBizKey(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId)).isPresent();
  }

  @Test
  void hardTurnLimitFinishesWithUnfinishedEvidencePersisted() throws Exception {
    when(answerEvaluator.evaluate(any())).thenReturn(
        structured("Java", List.of(assessed("并发边界", false, ""))),
        structured("Java", List.of(assessed("并发边界", true, "用乐观锁版本号"))),
        structured("MySQL", List.of(assessed("索引依据", true, "联合索引"))),
        structured("系统设计", List.of(assessed("取舍分析", false, ""))),
        structured("系统设计", List.of(assessed("取舍分析", false, ""))),
        structured("系统设计", List.of(assessed("取舍分析", false, ""))));

    submit("还没有深入细节");
    submit("项目里用乐观锁版本号实现并发控制");
    submit("我按查询条件设计了联合索引");
    submit("暂时没有做取舍分析");
    AnswerProcessingResult turn5 = submit("还是没有做取舍分析");
    assertThat(turn5.decision().nextStep()).isEqualTo(NextStep.FOLLOW_UP);

    AnswerProcessingResult turn6 = submit("仍然没有做取舍分析");
    assertThat(turn6.decision().nextStep()).isEqualTo(NextStep.FINISH);
    assertThat(turn6.decision().reason()).isEqualTo("TURN_BUDGET_EXHAUSTED");

    var stored = turns.findAllBySessionIdOrderByTurnNo(
        sessions.findBySessionId(sessionId).orElseThrow().getId()).getLast();
    AnswerProcessingResult finished = objectMapper.readValue(
        stored.getEvaluationSnapshot(), AnswerProcessingResult.class);
    assertThat(finished.finishReason()).isEqualTo("TURN_BUDGET_EXHAUSTED");
    assertThat(finished.unfinishedEvidence()).contains("系统设计").contains("取舍分析");
  }

  private AnswerProcessingResult submit(String answer) {
    return submitService.submit(LEGACY_USER, sessionId,
        new SubmitAnswerRequest(UUID.randomUUID(), answer));
  }

  private AnswerEvaluation structured(
      String competency, List<AnswerEvaluation.EvidenceAssessment> assessments) {
    return new AnswerEvaluation(
        70, "反馈", List.of(), List.of(), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            competency, "", "继续", 0.9),
        List.of(), List.of(), assessments);
  }

  private AnswerEvaluation.EvidenceAssessment assessed(
      String evidenceId, boolean observed, String claim) {
    return new AnswerEvaluation.EvidenceAssessment(evidenceId, observed, claim);
  }

  private InterviewPlan plan() {
    return InterviewPlan.execution(List.of(
        new InterviewPlanItem(
            "project", "java", "Java", PlanPriority.REQUIRED, 2,
            List.of("并发边界"), List.of(InterviewQuestionMode.PROJECT),
            "JD 必考", false, List.of("边界"), 1, ""),
        new InterviewPlanItem(
            "project", "mysql", "MySQL", PlanPriority.REQUIRED, 2,
            List.of("索引依据"), List.of(InterviewQuestionMode.PROJECT),
            "JD 必考", false, List.of("依据"), 1, ""),
        new InterviewPlanItem(
            "arch", "design", "系统设计", PlanPriority.REQUIRED, 2,
            List.of("取舍分析"), List.of(InterviewQuestionMode.PROJECT),
            "JD 必考", false, List.of("取舍"), 3, "")),
        6, List.of());
  }
}
