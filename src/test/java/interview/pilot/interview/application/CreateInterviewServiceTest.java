package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.skill.ClasspathInterviewSkillCatalog;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;

class CreateInterviewServiceTest {
  private static final CurrentUser LEGACY_USER = new CurrentUser(
      1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");

  @Test
  void resolvesProviderOnceAndUsesTheImmutableSnapshotForEveryAiStep() {
    ResumeRepository resumes = mock(ResumeRepository.class);
    AiProviderService providers = mock(AiProviderService.class);
    JobProfileExtractor extractor = mock(JobProfileExtractor.class);
    InterviewPlanner planner = mock(InterviewPlanner.class);
    QuestionGenerator questions = mock(QuestionGenerator.class);
    InterviewCreationStore store = mock(InterviewCreationStore.class);
    ObjectMapper objectMapper = new ObjectMapper();
    Validator validator = mock(Validator.class);
    ResumeEntity resume = readyResume();
    ResumeProfile profile = profile();
    JobRequirements job = new JobRequirements(List.of("Java", "Spring"), List.of("MySQL"));
    InterviewPlan plan = new InterviewPlan(List.of("Java", "Spring"), 8);
    GeneratedQuestion first = new GeneratedQuestion("Explain optimistic locking.", "Java");

    when(resumes.findByIdAndUserAccountId(7L, 1L)).thenReturn(java.util.Optional.of(resume));
    when(providers.resolveEnabled("deepseek"))
        .thenReturn(new AiProviderDescriptor("deepseek", "DeepSeek", "deepseek-chat", true, false));
    when(extractor.extract(eq("deepseek"), eq("Build reliable Java services"), any())).thenReturn(job);
    when(planner.plan(eq("deepseek"), eq(profile), eq(job), eq(Difficulty.MEDIUM), eq(8), any()))
        .thenReturn(plan);
    when(questions.firstQuestion(eq("deepseek"), eq(plan), eq(profile), eq(job), any(), any()))
        .thenReturn(first);
    when(validator.validate(any(ResumeProfile.class))).thenReturn(Set.of());
    when(store.create(any())).thenReturn(response("deepseek", "deepseek-chat"));

    var scopeResolver = mock(interview.pilot.knowledge.retrieval.KnowledgeScopeResolver.class);
    var retriever = mock(interview.pilot.knowledge.retrieval.KnowledgeRetriever.class);
    CreateInterviewService service = new CreateInterviewService(
        resumes, providers, extractor, planner, questions, store, objectMapper, validator,
        new ClasspathInterviewSkillCatalog(), scopeResolver, retriever);

    InterviewSessionResponse result = service.create(LEGACY_USER,new CreateInterviewRequest(
        7L, " Backend Engineer ", " Build reliable Java services ", Difficulty.MEDIUM, 8,
        "deepseek", "java-backend"));

    assertThat(result.providerId()).isEqualTo("deepseek");
    assertThat(result.modelName()).isEqualTo("deepseek-chat");
    assertThat(result.jobTitle()).isEqualTo("Backend Engineer");
    verify(providers).resolveEnabled("deepseek");
    verify(extractor).extract(eq("deepseek"), eq("Build reliable Java services"), any());
    verify(planner).plan(eq("deepseek"), eq(profile), eq(job), eq(Difficulty.MEDIUM), eq(8), any());
    verify(questions).firstQuestion(eq("deepseek"), eq(plan), eq(profile), eq(job), any(), any());
    verify(store).create(org.mockito.ArgumentMatchers.argThat(creation ->
        creation.skillSnapshot().id().equals("java-backend")
            && creation.skillSnapshot().rubric().contains("评分标准")));
  }

  @Test
  void rejectsResumeThatIsNotReadyBeforeCallingAi() {
    ResumeRepository resumes = mock(ResumeRepository.class);
    AiProviderService providers = mock(AiProviderService.class);
    JobProfileExtractor extractor = mock(JobProfileExtractor.class);
    InterviewPlanner planner = mock(InterviewPlanner.class);
    QuestionGenerator questions = mock(QuestionGenerator.class);
    InterviewCreationStore store = mock(InterviewCreationStore.class);
    ObjectMapper objectMapper = new ObjectMapper();
    Validator validator = mock(Validator.class);
    ResumeEntity pending = ResumeEntity.pending(1L, "resume.txt", "a".repeat(64), "Java");
    when(resumes.findByIdAndUserAccountId(7L, 1L)).thenReturn(java.util.Optional.of(pending));

    var scopeResolver = mock(interview.pilot.knowledge.retrieval.KnowledgeScopeResolver.class);
    var retriever = mock(interview.pilot.knowledge.retrieval.KnowledgeRetriever.class);
    CreateInterviewService service = new CreateInterviewService(
        resumes, providers, extractor, planner, questions, store, objectMapper, validator,
        new ClasspathInterviewSkillCatalog(), scopeResolver, retriever);

    assertThatThrownBy(() -> service.create(LEGACY_USER,new CreateInterviewRequest(
        7L, "Backend Engineer", "Build reliable Java services", Difficulty.MEDIUM, 8, null)))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(exception.code()).isEqualTo("RESUME_NOT_READY");
        });
    verify(providers, never()).resolveEnabled(any());
    verify(extractor, never()).extract(any(), any());
  }

  @Test
  void rejectsPlanThatChangesTheRequestedBudgetBeforePersistence() {
    ResumeRepository resumes = mock(ResumeRepository.class);
    AiProviderService providers = mock(AiProviderService.class);
    JobProfileExtractor extractor = mock(JobProfileExtractor.class);
    InterviewPlanner planner = mock(InterviewPlanner.class);
    QuestionGenerator questions = mock(QuestionGenerator.class);
    InterviewCreationStore store = mock(InterviewCreationStore.class);
    Validator validator = mock(Validator.class);
    JobRequirements job = new JobRequirements(List.of("Java"), List.of());
    when(resumes.findByIdAndUserAccountId(7L, 1L))
        .thenReturn(java.util.Optional.of(readyResume()));
    when(validator.validate(any(ResumeProfile.class))).thenReturn(Set.of());
    when(providers.resolveEnabled(null)).thenReturn(
        new AiProviderDescriptor("qwen", "Qwen", "qwen-plus", true, true));
    when(extractor.extract(eq("qwen"), eq("Build reliable Java services"), any())).thenReturn(job);
    when(planner.plan(eq("qwen"), eq(profile()), eq(job), eq(Difficulty.MEDIUM), eq(8), any()))
        .thenReturn(new InterviewPlan(List.of("Java"), 9));
    var scopeResolver = mock(interview.pilot.knowledge.retrieval.KnowledgeScopeResolver.class);
    var retriever = mock(interview.pilot.knowledge.retrieval.KnowledgeRetriever.class);
    var service = new CreateInterviewService(
        resumes, providers, extractor, planner, questions, store, new ObjectMapper(), validator,
        new ClasspathInterviewSkillCatalog(), scopeResolver, retriever);

    assertThatThrownBy(() -> service.create(LEGACY_USER,new CreateInterviewRequest(
        7L, "Backend Engineer", "Build reliable Java services", Difficulty.MEDIUM, 8, null)))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
          assertThat(exception.code()).isEqualTo("INVALID_AI_OUTPUT");
        });
    verify(store, never()).create(any());
    verify(questions, never()).firstQuestion(any(), any(), any(), any());
  }

  @Test
  void rejectsPlanThatOmitsARequiredJobCompetencyBeforeQuestionOrPersistence() {
    ResumeRepository resumes = mock(ResumeRepository.class);
    AiProviderService providers = mock(AiProviderService.class);
    JobProfileExtractor extractor = mock(JobProfileExtractor.class);
    InterviewPlanner planner = mock(InterviewPlanner.class);
    QuestionGenerator questions = mock(QuestionGenerator.class);
    InterviewCreationStore store = mock(InterviewCreationStore.class);
    Validator validator = mock(Validator.class);
    var scopeResolver = mock(interview.pilot.knowledge.retrieval.KnowledgeScopeResolver.class);
    var retriever = mock(interview.pilot.knowledge.retrieval.KnowledgeRetriever.class);
    JobRequirements job = new JobRequirements(List.of("Java", "Spring"), List.of());
    when(resumes.findByIdAndUserAccountId(7L, 1L))
        .thenReturn(java.util.Optional.of(readyResume()));
    when(validator.validate(any(ResumeProfile.class))).thenReturn(Set.of());
    when(providers.resolveEnabled(null)).thenReturn(
        new AiProviderDescriptor("qwen", "Qwen", "qwen-plus", true, true));
    when(extractor.extract(eq("qwen"), eq("Build reliable Java services"), any())).thenReturn(job);
    when(planner.plan(eq("qwen"), eq(profile()), eq(job), eq(Difficulty.MEDIUM), eq(8), any()))
        .thenReturn(new InterviewPlan(List.of("Java"), 8));
    var service = new CreateInterviewService(
        resumes, providers, extractor, planner, questions, store, new ObjectMapper(), validator,
        new ClasspathInterviewSkillCatalog(), scopeResolver, retriever);

    assertThatThrownBy(() -> service.create(LEGACY_USER,new CreateInterviewRequest(
        7L, "Backend Engineer", "Build reliable Java services", Difficulty.MEDIUM, 8, null)))
        .isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.code()).isEqualTo("INVALID_AI_OUTPUT"));
    verify(questions, never()).firstQuestion(any(), any(), any(), any());
    verify(store, never()).create(any());
  }

  private static ResumeEntity readyResume() {
    ResumeEntity resume = ResumeEntity.pending(1L, "resume.txt", "a".repeat(64), "Java Spring");
    resume.setStatus(ResumeStatus.READY);
    resume.setSkillsSnapshot("""
        {"summary":"Backend engineer","technicalSkills":["Java"],"projects":[],
         "strengths":["Reliable services"],"risks":[]}
        """);
    return resume;
  }

  private static ResumeProfile profile() {
    return new ResumeProfile(
        "Backend engineer", List.of("Java"), List.of(), List.of("Reliable services"), List.of());
  }

  private static InterviewSessionResponse response(String providerId, String modelName) {
    return new InterviewSessionResponse(
        UUID.randomUUID(), 7L, "Backend Engineer", "Build reliable Java services",
        interview.pilot.interview.domain.SessionStatus.INTERVIEWING,
        Difficulty.MEDIUM, 1, 8, providerId, modelName,
        new InterviewPlan(List.of("Java", "Spring"), 8), List.of());
  }
}
