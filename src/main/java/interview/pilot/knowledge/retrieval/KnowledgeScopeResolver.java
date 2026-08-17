package interview.pilot.knowledge.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

import org.springframework.beans.factory.annotation.Value;

@Component
public class KnowledgeScopeResolver {
  private final KnowledgeBaseRepository baseRepository;
  private final KnowledgeDocumentRepository documentRepository;
  private final String embeddingVersion;

  public KnowledgeScopeResolver(
      KnowledgeBaseRepository baseRepository,
      KnowledgeDocumentRepository documentRepository,
      @Value("${app.knowledge.embedding.model:text-embedding-v3}") String embeddingVersion) {
    this.baseRepository = baseRepository;
    this.documentRepository = documentRepository;
    this.embeddingVersion = embeddingVersion;
  }

  public ValidatedKnowledgeScope resolveForCreation(
      CurrentUser user, List<UUID> knowledgeBaseIds) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
      throw new BusinessException(
          "KNOWLEDGE_BASE_REQUIRED",
          "At least one knowledge base must be selected",
          HttpStatus.BAD_REQUEST);
    }

    List<UUID> distinct = knowledgeBaseIds.stream().distinct().toList();
    for (UUID baseId : distinct) {
      if (baseRepository.findByKnowledgeBaseIdAndUserAccountId(
          baseId, user.databaseId()).isEmpty()) {
        throw new BusinessException(
            "KNOWLEDGE_BASE_NOT_FOUND",
            "Knowledge base not found or does not belong to the current user",
            HttpStatus.NOT_FOUND);
      }
    }

    var readyDocs = documentRepository.findReadyByKnowledgeBaseIdsAndUserAccountId(
        distinct, user.databaseId());
    if (readyDocs.isEmpty()) {
      throw new BusinessException(
          "NO_READY_DOCUMENTS",
          "No ready documents found in the selected knowledge bases",
          HttpStatus.CONFLICT);
    }

    List<ValidatedKnowledgeScope.DocumentRevision> revisions = new ArrayList<>();
    for (var doc : readyDocs) {
      revisions.add(new ValidatedKnowledgeScope.DocumentRevision(
          doc.getDocumentId(), doc.getActiveIndexRevision()));
    }

    return new ValidatedKnowledgeScope(user.userId(), distinct, revisions, embeddingVersion);
  }
}
