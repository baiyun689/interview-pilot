package interview.pilot.knowledge.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.knowledge.api.KnowledgeBaseResponse;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

@Service
public class KnowledgeBaseService {
  private final KnowledgeBaseRepository baseRepository;
  private final KnowledgeDocumentRepository documentRepository;

  public KnowledgeBaseService(
      KnowledgeBaseRepository baseRepository,
      KnowledgeDocumentRepository documentRepository) {
    this.baseRepository = baseRepository;
    this.documentRepository = documentRepository;
  }

  @Transactional
  public KnowledgeBaseResponse create(CurrentUser user, String name) {
    KnowledgeBaseEntity base = KnowledgeBaseEntity.active(user.databaseId(), name);
    base = baseRepository.save(base);
    return response(base, 0);
  }

  @Transactional(readOnly = true)
  public List<KnowledgeBaseResponse> list(CurrentUser user) {
    var bases = baseRepository.findAllByUserAccountIdOrderByCreatedAtDesc(user.databaseId());
    List<KnowledgeBaseResponse> responses = new ArrayList<>();
    for (var base : bases) {
      long ready = documentRepository.countByKnowledgeBaseIdAndStatus(
          base.getId(), KnowledgeDocumentStatus.READY);
      responses.add(response(base, (int) ready));
    }
    return List.copyOf(responses);
  }

  private KnowledgeBaseResponse response(KnowledgeBaseEntity base, int readyCount) {
    return new KnowledgeBaseResponse(
        base.getKnowledgeBaseId(), base.getName(), base.getStatus().name(),
        readyCount, base.getCreatedAt());
  }
}
