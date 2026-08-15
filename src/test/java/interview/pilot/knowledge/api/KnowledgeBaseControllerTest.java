package interview.pilot.knowledge.api;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.knowledge.application.KnowledgeBaseService;
import interview.pilot.knowledge.application.KnowledgeDocumentUploadService;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;

class KnowledgeBaseControllerTest {
  private KnowledgeBaseService baseService;
  private KnowledgeDocumentUploadService documentUploadService;
  private CurrentUserProvider currentUser;
  private MockMvc mockMvc;
  private final CurrentUser user = new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User");
  private final UUID baseId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    baseService = mock(KnowledgeBaseService.class);
    documentUploadService = mock(KnowledgeDocumentUploadService.class);
    currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(user);
    mockMvc = MockMvcBuilders
        .standaloneSetup(new KnowledgeBaseController(baseService, documentUploadService, currentUser))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void createKnowledgeBaseReturnsCreated() throws Exception {
    KnowledgeBaseResponse response = new KnowledgeBaseResponse(
        baseId, "Spring八股", "ACTIVE", 0, Instant.now());
    when(baseService.create(user, "Spring八股"))
        .thenReturn(response);

    mockMvc.perform(post("/api/knowledge-bases")
            .contentType("application/json")
            .content("{\"name\":\"Spring八股\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.knowledgeBaseId").value(baseId.toString()))
        .andExpect(jsonPath("$.name").value("Spring八股"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void listsKnowledgeBasesForCurrentUser() throws Exception {
    KnowledgeBaseResponse kb = new KnowledgeBaseResponse(
        baseId, "Spring八股", "ACTIVE", 3, Instant.now());
    when(baseService.list(user)).thenReturn(List.of(kb));

    mockMvc.perform(get("/api/knowledge-bases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].knowledgeBaseId").value(baseId.toString()))
        .andExpect(jsonPath("$[0].readyDocumentCount").value(3));
  }

  @Test
  void uploadCreatesProcessingDocumentAndReturnsAccepted() throws Exception {
    KnowledgeDocumentResponse response = KnowledgeDocumentResponse.processing(documentId);
    when(documentUploadService.upload(
        org.mockito.ArgumentMatchers.eq(user),
        org.mockito.ArgumentMatchers.eq(baseId),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(response);

    mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", baseId)
            .file(mdFile("spring-core.md")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.status").value("PROCESSING"));
  }

  @Test
  void listsDocumentsInKnowledgeBase() throws Exception {
    KnowledgeDocumentResponse processing = KnowledgeDocumentResponse.processing(documentId);
    KnowledgeDocumentResponse failed = new KnowledgeDocumentResponse(
        UUID.randomUUID(), "broken.md", KnowledgeDocumentStatus.FAILED.name(),
        1, 0, "parse failed", Instant.now());
    when(documentUploadService.listDocuments(user, baseId)).thenReturn(List.of(processing, failed));

    mockMvc.perform(get("/api/knowledge-bases/{id}/documents", baseId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].documentId").value(documentId.toString()))
        .andExpect(jsonPath("$[0].status").value("PROCESSING"))
        .andExpect(jsonPath("$[1].status").value("FAILED"))
        .andExpect(jsonPath("$[1].failureReason").value("parse failed"));
  }

  @Test
  void reindexReturnsAccepted() throws Exception {
    KnowledgeDocumentResponse response = KnowledgeDocumentResponse.processing(documentId);
    when(documentUploadService.reindex(user, baseId, documentId)).thenReturn(response);

    mockMvc.perform(post("/api/knowledge-bases/{baseId}/documents/{docId}/reindex",
            baseId, documentId))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PROCESSING"));
  }

  @Test
  void deleteDocumentReturnsNoContent() throws Exception {
    mockMvc.perform(delete("/api/knowledge-bases/{baseId}/documents/{docId}",
            baseId, documentId))
        .andExpect(status().isNoContent());

    verify(documentUploadService).delete(user, baseId, documentId);
  }

  @Test
  void deleteKnowledgeBaseReturnsNoContent() throws Exception {
    mockMvc.perform(delete("/api/knowledge-bases/{baseId}", baseId))
        .andExpect(status().isNoContent());

    verify(baseService).delete(user, baseId);
  }

  @Test
  void knowledgeBaseNotFoundReturns404() throws Exception {
    when(baseService.list(user)).thenThrow(
        new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found",
            HttpStatus.NOT_FOUND));

    mockMvc.perform(get("/api/knowledge-bases"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));
  }

  @Test
  void unsupportedMethodReturns405() throws Exception {
    mockMvc.perform(patch("/api/knowledge-bases"))
        .andExpect(status().isMethodNotAllowed());
  }

  private static MockMultipartFile mdFile(String filename) {
    return new MockMultipartFile(
        "file", filename, "text/markdown",
        "# Spring\nSpring is a framework.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
