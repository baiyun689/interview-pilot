package interview.pilot.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;

class KnowledgeDocumentEntityTest {
  @Test
  void reindexingUsesRevisionsToRejectStaleCompletions() {
    var base = KnowledgeBaseEntity.active(42L, "Java platform");
    var document = KnowledgeDocumentEntity.pending(
        base, "guide.txt", "a".repeat(64), "42/guide.txt");

    int firstRevision = document.beginReindex();
    int secondRevision = document.beginReindex();

    assertThat(firstRevision).isEqualTo(1);
    assertThat(secondRevision).isEqualTo(2);
    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);
    assertThatIllegalStateException().isThrownBy(() -> document.markReady(firstRevision, "Java", 1))
        .withMessage("Stale document index revision");

    document.markReady(secondRevision, "Java", 1);

    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.READY);
    assertThat(document.getParsedText()).isEqualTo("Java");
    assertThat(document.getChunkCount()).isEqualTo(1);
  }

  @Test
  void deletingDocumentCannotBeReindexed() {
    var base = KnowledgeBaseEntity.active(42L, "Java platform");
    var document = KnowledgeDocumentEntity.pending(
        base, "guide.txt", "a".repeat(64), "42/guide.txt");

    document.beginDeletion();

    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.DELETING);
    assertThatIllegalStateException().isThrownBy(document::beginReindex)
        .withMessage("Deleting document cannot be reindexed");
  }

  @Test
  void failedIndexingRecordsTheFailureForItsCurrentRevision() {
    var base = KnowledgeBaseEntity.active(42L, "Java platform");
    var document = KnowledgeDocumentEntity.pending(
        base, "guide.txt", "a".repeat(64), "42/guide.txt");

    int revision = document.beginReindex();
    document.markFailed(revision, "extractor timed out");

    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.FAILED);
    assertThat(document.getFailureReason()).isEqualTo("extractor timed out");
  }
}
