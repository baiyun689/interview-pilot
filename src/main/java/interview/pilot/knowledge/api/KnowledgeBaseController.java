package interview.pilot.knowledge.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import interview.pilot.knowledge.application.KnowledgeBaseService;
import interview.pilot.knowledge.application.KnowledgeDocumentUploadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
  private final KnowledgeBaseService baseService;
  private final KnowledgeDocumentUploadService documentUploadService;
  private final CurrentUserProvider currentUser;

  public KnowledgeBaseController(
      KnowledgeBaseService baseService,
      KnowledgeDocumentUploadService documentUploadService,
      CurrentUserProvider currentUser) {
    this.baseService = baseService;
    this.documentUploadService = documentUploadService;
    this.currentUser = currentUser;
  }

  @PostMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 20)
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public ResponseEntity<KnowledgeBaseResponse> create(
      @Valid @RequestBody CreateKnowledgeBaseRequest request) {
    var response = baseService.create(currentUser.require(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<KnowledgeBaseResponse> list() {
    return baseService.list(currentUser.require());
  }

  @PostMapping(path = "/{knowledgeBaseId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public ResponseEntity<KnowledgeDocumentResponse> uploadDocument(
      @PathVariable UUID knowledgeBaseId,
      @RequestPart("file") MultipartFile file) {
    var response = documentUploadService.upload(
        currentUser.require(), knowledgeBaseId, file);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  @GetMapping("/{knowledgeBaseId}/documents")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<KnowledgeDocumentResponse> listDocuments(
      @PathVariable UUID knowledgeBaseId) {
    return documentUploadService.listDocuments(currentUser.require(), knowledgeBaseId);
  }

  @PostMapping("/{knowledgeBaseId}/documents/{documentId}/reindex")
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public ResponseEntity<KnowledgeDocumentResponse> reindex(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId) {
    var response = documentUploadService.reindex(
        currentUser.require(), knowledgeBaseId, documentId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
  @RateLimit(scope = RateLimitScope.IP, capacity = 20)
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public ResponseEntity<Void> deleteDocument(
      @PathVariable UUID knowledgeBaseId,
      @PathVariable UUID documentId) {
    documentUploadService.delete(currentUser.require(), knowledgeBaseId, documentId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{knowledgeBaseId}")
  @RateLimit(scope = RateLimitScope.IP, capacity = 20)
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public ResponseEntity<Void> delete(@PathVariable UUID knowledgeBaseId) {
    baseService.delete(currentUser.require(), knowledgeBaseId);
    return ResponseEntity.noContent().build();
  }

  public record CreateKnowledgeBaseRequest(
      @NotBlank @Size(max = 255) String name) {}
}
