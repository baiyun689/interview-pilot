package interview.pilot.knowledge.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.knowledge.api.KnowledgeBaseResponse;
import interview.pilot.knowledge.domain.KnowledgeBaseStatus;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;

@Service
public class KnowledgeBaseService {
  private final KnowledgeBaseRepository baseRepository;
  private final KnowledgeDocumentRepository documentRepository;
  private final KnowledgeDocumentUploadService documentUploadService;
  private final InterviewKnowledgeBaseRepository kbAssociations;
  private final TransactionTemplate transactionTemplate;

  public KnowledgeBaseService(
      KnowledgeBaseRepository baseRepository,
      KnowledgeDocumentRepository documentRepository,
      KnowledgeDocumentUploadService documentUploadService,
      InterviewKnowledgeBaseRepository kbAssociations,
      PlatformTransactionManager transactionManager) {
    this.baseRepository = baseRepository;
    this.documentRepository = documentRepository;
    this.documentUploadService = documentUploadService;
    this.kbAssociations = kbAssociations;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
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

  /** 同步删除知识库:置 DELETING → 复用文档删除流程清理向量与文件 → 删关联行与 KB 行。中途失败可重试。 */
  public void delete(CurrentUser user, UUID knowledgeBaseId) {
    Long userAccountId = user.databaseId();
    // 事务①:归属校验并置 DELETING;已处于 DELETING 的重试请求幂等继续
    KnowledgeBaseEntity base = Objects.requireNonNull(transactionTemplate.execute(status -> {
      KnowledgeBaseEntity found = baseRepository
          .findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, userAccountId)
          .orElseThrow(() -> new BusinessException(
              "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.NOT_FOUND));
      if (found.getStatus() == KnowledgeBaseStatus.ACTIVE) {
        found.beginDeletion();
        baseRepository.save(found);
      }
      return found;
    }));

    // 逐个文档复用现有删除流程(向量与文件清理 best-effort,在事务外执行)
    var documents = documentRepository.findAllByKnowledgeBase(base);
    for (var document : documents) {
      if (document.getStatus() != KnowledgeDocumentStatus.DELETED) {
        documentUploadService.delete(user, knowledgeBaseId, document.getDocumentId());
      }
    }

    // 事务②:面试靠创建时的 knowledge_scope_snapshot 保留历史,解除关联后删除文档行与 KB 行
    transactionTemplate.executeWithoutResult(status -> {
      kbAssociations.deleteByKnowledgeBaseId(base.getId());
      documentRepository.deleteByKnowledgeBaseId(base.getId());
      baseRepository.deleteByKnowledgeBaseIdAndUserAccountId(
          base.getKnowledgeBaseId(), userAccountId);
    });
  }

  private KnowledgeBaseResponse response(KnowledgeBaseEntity base, int readyCount) {
    return new KnowledgeBaseResponse(
        base.getKnowledgeBaseId(), base.getName(), base.getStatus().name(),
        readyCount, base.getCreatedAt());
  }
}
