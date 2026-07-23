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

  @Test
  void reindexingClearsThePreviousFailure() {
    var document = pendingDocument();
    document.markFailed(document.beginReindex(), "extractor timed out");

    int revision = document.beginReindex();

    assertThat(revision).isEqualTo(2);
    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);
    assertThat(document.getFailureReason()).isNull();
  }

  @Test
  void staleFailedCompletionIsRejected() {
    var document = pendingDocument();
    int staleRevision = document.beginReindex();
    document.beginReindex();

    assertThatIllegalStateException().isThrownBy(
        () -> document.markFailed(staleRevision, "stale worker failure"))
        .withMessage("Stale document index revision");
  }

  @Test
  void deletionRejectsOldReadyAndFailedCompletions() {
    var document = pendingDocument();
    int processingRevision = document.beginReindex();
    document.beginDeletion();

    assertThatIllegalStateException().isThrownBy(
        () -> document.markReady(processingRevision, "stale", 1))
        .withMessage("Stale document index revision");
    assertThatIllegalStateException().isThrownBy(
        () -> document.markFailed(processingRevision, "stale"))
        .withMessage("Stale document index revision");
  }

  private KnowledgeDocumentEntity pendingDocument() {
    return KnowledgeDocumentEntity.pending(
        KnowledgeBaseEntity.active(42L, "Java platform"),
        "guide.txt", "a".repeat(64), "42/guide.txt");
  }
}
