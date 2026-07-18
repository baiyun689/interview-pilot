package interview.pilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.resume.infrastructure.ResumeTextExtractor;

class ResumeUploadRaceRecoveryTest {
  private static final String CLEANED_TEXT =
      "Senior Java engineer with Spring Boot, PostgreSQL, Redis, messaging, testing, and cloud experience.";

  @Test
  void uniqueConflictRollsBackCreationBeforeQueryingTheWinningRows() throws Exception {
    var extractor = mock(ResumeTextExtractor.class);
    var resumeRepository = mock(ResumeRepository.class);
    var taskRepository = mock(AsyncTaskRepository.class);
    var transactionManager = mock(PlatformTransactionManager.class);
    var creationStatus = mock(TransactionStatus.class);
    var recoveryStatus = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(creationStatus, recoveryStatus);
    when(extractor.extract(any(MultipartFile.class))).thenReturn(CLEANED_TEXT);

    var winningResume = ResumeEntity.pending("winner.txt", sha256(CLEANED_TEXT), CLEANED_TEXT);
    winningResume.setId(41L);
    var winningTask = AsyncTaskEntity.pending(
        AsyncTaskType.RESUME_ANALYSIS, "resume:41", "{\"resumeId\":41}");
    winningTask.setId(84L);
    UUID publicTaskId = UUID.randomUUID();
    winningTask.setTaskId(publicTaskId);

    when(resumeRepository.findByContentHash(anyString()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winningResume));
    when(resumeRepository.saveAndFlush(any(ResumeEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate content_hash"));
    when(taskRepository.findByTaskTypeAndBizKey(AsyncTaskType.RESUME_ANALYSIS, "resume:41"))
        .thenReturn(Optional.of(winningTask));

    var service = new ResumeUploadService(
        extractor, resumeRepository, taskRepository, transactionManager);
    var result = service.upload(txt("loser.txt"));

    assertThat(result.resumeId()).isEqualTo(41L);
    assertThat(result.analysisTaskId()).isEqualTo(publicTaskId);
    assertThat(result.duplicate()).isTrue();

    var ordered = inOrder(transactionManager, resumeRepository, taskRepository);
    ordered.verify(resumeRepository).findByContentHash(anyString());
    ordered.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    ordered.verify(resumeRepository).findByContentHash(anyString());
    ordered.verify(resumeRepository).saveAndFlush(any(ResumeEntity.class));
    ordered.verify(transactionManager).rollback(creationStatus);
    ordered.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    ordered.verify(resumeRepository).findByContentHash(anyString());
    ordered.verify(taskRepository).findByTaskTypeAndBizKey(
        AsyncTaskType.RESUME_ANALYSIS, "resume:41");
    ordered.verify(transactionManager).commit(recoveryStatus);
  }

  private static MockMultipartFile txt(String filename) {
    return new MockMultipartFile(
        "file", filename, "text/plain", CLEANED_TEXT.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(String content) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
  }
}
