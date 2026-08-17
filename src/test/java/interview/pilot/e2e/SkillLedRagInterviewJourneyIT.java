package interview.pilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.application.AnswerGroundingValidator;
import interview.pilot.interview.application.InterviewDecisionContextFactory;
import interview.pilot.interview.application.InterviewPlanCompiler;
import interview.pilot.interview.application.PlanProposal;
import interview.pilot.interview.application.QuestionGroundingValidator;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.grounding.DefaultKnowledgeGrounding;
import interview.pilot.interview.grounding.GroundingDirective;
import interview.pilot.interview.grounding.GroundingStatus;
import interview.pilot.interview.skill.ClasspathInterviewSkillCatalog;
import interview.pilot.interview.strategy.DefaultInterviewStrategy;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeChunk;
import interview.pilot.knowledge.retrieval.RetrievalStatus;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;

class SkillLedRagInterviewJourneyIT {
  private final UUID documentId = UUID.randomUUID();

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

    var snapshot = grounding.ground(scope, GroundingDirective.from(directive, List.of()));
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

    var snapshot = grounding.ground(scope, GroundingDirective.from(directive, List.of()));
    var question = new QuestionGroundingValidator().validate(
        new GeneratedQuestion("解释事务传播边界", directive.competency()),
        snapshot.toRagContext());

    assertThat(snapshot.status()).isEqualTo(GroundingStatus.UNAVAILABLE);
    assertThat(question.groundingMode()).isEqualTo(GroundingMode.SKILL_GENERAL);
    assertThat(question.evidenceRefs()).isEmpty();
  }
}
