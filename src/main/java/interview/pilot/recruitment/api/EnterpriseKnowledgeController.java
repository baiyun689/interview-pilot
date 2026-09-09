package interview.pilot.recruitment.api;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.EnterpriseKnowledgeService;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api/organizations/{orgId}/knowledge")
public class EnterpriseKnowledgeController {
  private final EnterpriseKnowledgeService service;
  private final CurrentUserProvider users;
  public EnterpriseKnowledgeController(EnterpriseKnowledgeService service, CurrentUserProvider users) { this.service = service; this.users = users; }
  public record NameInput(@NotBlank @Size(max = 255) String name) {}
  public record SearchInput(@NotEmpty @Size(max = 5) List<@NotNull UUID> knowledgeBaseIds, @NotBlank @Size(max = 1000) String query) {}
  @GetMapping
  public List<EnterpriseKnowledgeService.BaseView> bases(@PathVariable Long orgId) { return service.bases(users.require(), orgId); }
  @PostMapping
  @RateLimit(scope = RateLimitScope.USER, capacity = 10)
  public EnterpriseKnowledgeService.BaseView create(@PathVariable Long orgId, @Valid @RequestBody NameInput input) { return service.create(users.require(), orgId, input.name()); }
  @GetMapping("/{baseId}/documents")
  public List<EnterpriseKnowledgeService.DocumentView> documents(@PathVariable Long orgId, @PathVariable UUID baseId) { return service.documents(users.require(), orgId, baseId); }
  @PostMapping(value = "/{baseId}/documents", consumes = "multipart/form-data")
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public EnterpriseKnowledgeService.DocumentView upload(@PathVariable Long orgId, @PathVariable UUID baseId, @RequestPart("file") MultipartFile file) {
    return service.upload(users.require(), orgId, baseId, file);
  }
  @PostMapping("/{baseId}/documents/{documentId}/reindex")
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public EnterpriseKnowledgeService.DocumentView reindex(@PathVariable Long orgId, @PathVariable UUID baseId, @PathVariable UUID documentId) {
    return service.reindex(users.require(), orgId, baseId, documentId);
  }
  @DeleteMapping("/{baseId}/documents/{documentId}")
  public EnterpriseKnowledgeService.DocumentView delete(@PathVariable Long orgId, @PathVariable UUID baseId, @PathVariable UUID documentId) {
    return service.delete(users.require(), orgId, baseId, documentId);
  }
  @PostMapping("/search")
  @RateLimit(scope = RateLimitScope.USER, capacity = 20, expensive = true)
  public RetrievedKnowledge search(@PathVariable Long orgId, @Valid @RequestBody SearchInput input) {
    return service.search(users.require(), orgId, input.knowledgeBaseIds(), input.query());
  }
}
