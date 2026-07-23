package interview.pilot.resume.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.resume.application.ResumeQueryService;
import interview.pilot.resume.application.ResumeUploadService;
import interview.pilot.resume.application.ResumeUploadService.UploadResumeResult;
import interview.pilot.resume.domain.ResumeStatus;

class ResumeControllerTest {
  private ResumeUploadService uploadService;
  private ResumeQueryService queryService;
  private CurrentUserProvider currentUser;
  private MockMvc mockMvc;
  private final CurrentUser user = new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User");

  @BeforeEach
  void setUp() {
    uploadService = mock(ResumeUploadService.class);
    queryService = mock(ResumeQueryService.class);
    currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(user);
    mockMvc = MockMvcBuilders
        .standaloneSetup(new ResumeController(uploadService, queryService, currentUser))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void newUploadReturnsAcceptedWithResumeAndTaskIds() throws Exception {
    long resumeId = 101L;
    UUID taskId = UUID.randomUUID();
    when(uploadService.upload(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new UploadResumeResult(resumeId, taskId, false));

    mockMvc.perform(multipart("/api/resumes").file(txt("resume.txt")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.resumeId").value(resumeId))
        .andExpect(jsonPath("$.analysisTaskId").value(taskId.toString()))
        .andExpect(jsonPath("$.duplicate").value(false));
  }

  @Test
  void duplicateUploadReturnsOkWithExistingIds() throws Exception {
    long resumeId = 102L;
    UUID taskId = UUID.randomUUID();
    when(uploadService.upload(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new UploadResumeResult(resumeId, taskId, true));

    mockMvc.perform(multipart("/api/resumes").file(txt("duplicate.txt")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resumeId").value(resumeId))
        .andExpect(jsonPath("$.analysisTaskId").value(taskId.toString()))
        .andExpect(jsonPath("$.duplicate").value(true));
  }

  @Test
  void listsResumesWithAnalysisTaskId() throws Exception {
    long resumeId = 103L;
    UUID taskId = UUID.randomUUID();
    when(queryService.list(user)).thenReturn(List.of(response(resumeId, taskId)));

    mockMvc.perform(get("/api/resumes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(resumeId))
        .andExpect(jsonPath("$[0].analysisTaskId").value(taskId.toString()));
  }

  @Test
  void returnsResumeDetailWithNullProfileBeforeAnalysis() throws Exception {
    long resumeId = 104L;
    UUID taskId = UUID.randomUUID();
    when(queryService.get(user, resumeId)).thenReturn(response(resumeId, taskId));

    mockMvc.perform(get("/api/resumes/{id}", resumeId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(resumeId))
        .andExpect(jsonPath("$.analysisTaskId").value(taskId.toString()))
        .andExpect(jsonPath("$.profile").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void businessErrorsUseSemanticStatusAndSafeErrorShape() throws Exception {
    when(uploadService.upload(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new BusinessException(
            "UNSUPPORTED_FILE_TYPE", "Only PDF, DOCX, and TXT documents are supported",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE));

    mockMvc.perform(multipart("/api/resumes").file(txt("resume.txt")))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"))
        .andExpect(jsonPath("$.message").value("Only PDF, DOCX, and TXT documents are supported"))
        .andExpect(jsonPath("$.traceId", matchesPattern(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
  }

  @Test
  void missingResumeReturnsNotFoundError() throws Exception {
    long resumeId = 999L;
    when(queryService.get(user, resumeId)).thenThrow(
        new BusinessException("RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));

    mockMvc.perform(get("/api/resumes/{id}", resumeId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESUME_NOT_FOUND"));
  }

  @Test
  void unsupportedHttpMethodPreservesFrameworkStatusAndErrorShape() throws Exception {
    mockMvc.perform(patch("/api/resumes"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.message").value("Method Not Allowed"))
        .andExpect(jsonPath("$.traceId", matchesPattern(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
  }

  @Test
  void unsupportedContentTypePreservesFrameworkStatusAndErrorShape() throws Exception {
    mockMvc.perform(post("/api/resumes")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
        .andExpect(jsonPath("$.message").value("Unsupported Media Type"))
        .andExpect(jsonPath("$.traceId", matchesPattern(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
  }

  private static ResumeResponse response(long resumeId, UUID taskId) {
    return new ResumeResponse(
        resumeId,
        "resume.txt",
        ResumeStatus.PENDING,
        false,
        taskId,
        Instant.parse("2026-07-13T00:00:00Z"),
        null,
        null);
  }

  private static MockMultipartFile txt(String filename) {
    return new MockMultipartFile(
        "file", filename, "text/plain",
        "Senior Java engineer with Spring Boot, PostgreSQL, Redis, messaging, testing, and cloud experience."
            .getBytes(StandardCharsets.UTF_8));
  }
}
