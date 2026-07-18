package interview.pilot.interview.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.ai.AiGatewayException;
import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.ai.provider.AiProviderException;
import interview.pilot.interview.application.CreateInterviewService;
import interview.pilot.interview.application.InterviewQueryService;
import interview.pilot.interview.application.InterviewSseService;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.interview.application.InterviewQueryService.ReportQueryResult;
import java.util.Map;

class InterviewControllerTest {
  private CreateInterviewService createService;
  private InterviewQueryService queryService;
  private InterviewSseService sseService;
  private CurrentUserProvider currentUser;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    createService = mock(CreateInterviewService.class);
    queryService = mock(InterviewQueryService.class);
    sseService = mock(InterviewSseService.class);
    currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(new CurrentUser(1L, new UUID(0L, 1L), "test@example.com", "Test"));
    mockMvc = MockMvcBuilders
        .standaloneSetup(new InterviewController(createService, queryService, sseService, currentUser))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void createsSessionAndReturnsFirstAskedTurn() throws Exception {
    InterviewSessionResponse response = response();
    when(createService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(response);

    mockMvc.perform(post("/api/interviews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"resumeId":7,"jobTitle":" Backend Engineer ",
                 "jdText":" Build reliable Java services ","difficulty":"MEDIUM",
                 "totalTurnBudget":8,"providerId":"deepseek"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sessionId").value(response.sessionId().toString()))
        .andExpect(jsonPath("$.providerId").value("deepseek"))
        .andExpect(jsonPath("$.modelName").value("deepseek-chat"))
        .andExpect(jsonPath("$.turns", hasSize(1)))
        .andExpect(jsonPath("$.turns[0].status").value("ASKED"));
  }

  @Test
  void getsStableSessionSnapshotWithoutResumeText() throws Exception {
    InterviewSessionResponse response = response();
    when(queryService.get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(response.sessionId()))).thenReturn(response);

    mockMvc.perform(get("/api/interviews/{id}", response.sessionId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerId").value("deepseek"))
        .andExpect(jsonPath("$.modelName").value("deepseek-chat"))
        .andExpect(jsonPath("$.plan.competencies[0]").value("Java"))
        .andExpect(jsonPath("$.parsedText").doesNotExist())
        .andExpect(jsonPath("$.apiKey").doesNotExist());
  }

  @Test
  void rejectsInvalidCreationRequestAndPreservesFrameworkContracts() throws Exception {
    String invalid = """
        {"resumeId":7,"jobTitle":" ","jdText":"JD","difficulty":"MEDIUM",
         "totalTurnBudget":4,"providerId":""}
        """;
    mockMvc.perform(post("/api/interviews").contentType(MediaType.APPLICATION_JSON).content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mockMvc.perform(patch("/api/interviews"))
        .andExpect(status().isMethodNotAllowed());
    mockMvc.perform(post("/api/interviews").contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType());
    mockMvc.perform(post("/api/interviews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{malformed"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void providerErrorsAreSanitizedBadRequests() throws Exception {
    when(createService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new AiProviderException("secret-provider-detail"));

    mockMvc.perform(post("/api/interviews")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"resumeId":7,"jobTitle":"Backend Engineer","jdText":"Java services",
                 "difficulty":"MEDIUM","totalTurnBudget":8,"providerId":"missing"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"))
        .andExpect(jsonPath("$.message").value("AI provider is unavailable"));
  }

  @Test
  void providerCallFailuresAreSanitizedBadGatewayResponses() throws Exception {
    when(createService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new AiGatewayException("secret-provider-response"));

    performValidCreate()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AI_PROVIDER_CALL_FAILED"))
        .andExpect(jsonPath("$.message").value("AI provider call failed"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("secret"))));
  }

  @Test
  void invalidStructuredAiResponsesAreSanitizedBadGatewayResponses() throws Exception {
    when(createService.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new AiStructuredOutputException("secret-provider-response"));

    performValidCreate()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("INVALID_AI_OUTPUT"))
        .andExpect(jsonPath("$.message").value("AI returned an invalid structured response"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void listsHistoryAndReturnsAcceptedWhileReportIsEvaluating() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    when(queryService.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(new InterviewHistoryResponse(
        sessionId, "Backend", SessionStatus.EVALUATING, Difficulty.MEDIUM,
        5, 5, "deepseek", "deepseek-chat", Instant.parse("2026-07-13T00:00:00Z"), null)));
    when(queryService.report(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(sessionId))).thenReturn(new ReportQueryResult(
        org.springframework.http.HttpStatus.ACCEPTED,
        new InterviewReportStatusResponse(
            sessionId, SessionStatus.EVALUATING, taskId, AsyncTaskStatus.DEAD,
            "Interview report generation retries exhausted", true)));

    mockMvc.perform(get("/api/interviews"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sessionId").value(sessionId.toString()))
        .andExpect(jsonPath("$[0].jobTitle").value("Backend"));
    mockMvc.perform(get("/api/interviews/{id}/report", sessionId))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.taskId").value(taskId.toString()))
        .andExpect(jsonPath("$.taskStatus").value("DEAD"))
        .andExpect(jsonPath("$.retryable").value(true));
  }

  @Test
  void returnsTypedCompletedReportWithoutRawSnapshots() throws Exception {
    UUID sessionId = UUID.randomUUID();
    var report = new InterviewReport(
        90, Map.of("Java", 91), List.of("Evidence"), List.of("Depth"), "Summary");
    when(queryService.report(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(sessionId))).thenReturn(new ReportQueryResult(
        org.springframework.http.HttpStatus.OK,
        new InterviewReportResponse(
            sessionId, UUID.randomUUID(), report, Instant.parse("2026-07-13T00:00:00Z"))));

    mockMvc.perform(get("/api/interviews/{id}/report", sessionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.report.overallScore").value(90))
        .andExpect(jsonPath("$.report.competencyScores.Java").value(91))
        .andExpect(jsonPath("$.reportSnapshot").doesNotExist())
        .andExpect(jsonPath("$.scoreSnapshot").doesNotExist());
  }

  private static InterviewSessionResponse response() {
    return new InterviewSessionResponse(
        UUID.randomUUID(), 7L, "Backend Engineer", "Build reliable Java services",
        SessionStatus.INTERVIEWING, Difficulty.MEDIUM, 1, 8,
        "deepseek", "deepseek-chat", new InterviewPlan(List.of("Java", "Spring"), 8),
        List.of(new InterviewSessionResponse.TurnResponse(
            null, 1, TurnStatus.ASKED, Difficulty.MEDIUM,
            "Explain optimistic locking.", "Java", Instant.parse("2026-07-13T00:00:00Z"))));
  }

  private org.springframework.test.web.servlet.ResultActions performValidCreate() throws Exception {
    return mockMvc.perform(post("/api/interviews")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"resumeId":7,"jobTitle":"Backend Engineer","jdText":"Java services",
             "difficulty":"MEDIUM","totalTurnBudget":8,"providerId":"deepseek"}
            """));
  }
}
