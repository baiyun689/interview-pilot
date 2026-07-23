package interview.pilot.resume.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.resume.infrastructure.ResumeTextExtractor;

@Service
public class ResumeUploadService {
  private final ResumeTextExtractor extractor;
  private final ResumeRepository resumeRepository;
  private final AsyncTaskRepository taskRepository;
  private final TransactionTemplate transactionTemplate;

  public ResumeUploadService(
      ResumeTextExtractor extractor,
      ResumeRepository resumeRepository,
      AsyncTaskRepository taskRepository,
      PlatformTransactionManager transactionManager) {
    this.extractor = extractor;
    this.resumeRepository = resumeRepository;
    this.taskRepository = taskRepository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public UploadResumeResult upload(CurrentUser user, MultipartFile file) {
    String cleanedText = extractor.extract(file);
    String contentHash = sha256(cleanedText);
    Long userAccountId = requireOwner(user);

    var existing = resumeRepository.findByUserAccountIdAndContentHash(userAccountId, contentHash);
    if (existing.isPresent()) {
      return existingResult(existing.orElseThrow());
    }

    try {
      return Objects.requireNonNull(transactionTemplate.execute(status ->
          createOrFindExisting(userAccountId, file.getOriginalFilename(), contentHash, cleanedText)));
    } catch (DataIntegrityViolationException conflict) {
      return recoverWinner(userAccountId, contentHash, conflict);
    }
  }

  private UploadResumeResult createOrFindExisting(
      Long userAccountId,
      String originalFilename,
      String contentHash,
      String cleanedText) {
    var existing = resumeRepository.findByUserAccountIdAndContentHash(userAccountId, contentHash);
    if (existing.isPresent()) {
      return existingResult(existing.orElseThrow());
    }

    ResumeEntity resume = resumeRepository.saveAndFlush(ResumeEntity.pending(
        userAccountId, originalFilename, contentHash, cleanedText));
    String bizKey = bizKey(resume.getId());
    AsyncTaskEntity task = taskRepository.save(AsyncTaskEntity.pending(
        userAccountId,
        AsyncTaskType.RESUME_ANALYSIS,
        bizKey,
        "{\"resumeId\":" + resume.getId() + "}"));
    return new UploadResumeResult(resume.getId(), task.getTaskId(), false);
  }

  private UploadResumeResult recoverWinner(
      Long userAccountId,
      String contentHash,
      DataIntegrityViolationException conflict) {
    return Objects.requireNonNull(transactionTemplate.execute(status ->
        resumeRepository.findByUserAccountIdAndContentHash(userAccountId, contentHash)
            .map(this::existingResult)
            .orElseThrow(() -> conflict)));
  }

  private UploadResumeResult existingResult(ResumeEntity resume) {
    AsyncTaskEntity task = taskRepository.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.RESUME_ANALYSIS, bizKey(resume.getId()), resume.getUserAccountId())
        .orElseThrow(() -> new BusinessException(
            "ANALYSIS_TASK_NOT_FOUND",
            "The resume analysis task could not be found",
            HttpStatus.INTERNAL_SERVER_ERROR));
    return new UploadResumeResult(resume.getId(), task.getTaskId(), true);
  }

  private static String bizKey(Long resumeId) {
    return "resume:" + resumeId;
  }

  private static Long requireOwner(CurrentUser user) {
    return Objects.requireNonNull(Objects.requireNonNull(user, "user").databaseId(), "user.databaseId");
  }

  private static String sha256(String cleanedText) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(cleanedText.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record UploadResumeResult(Long resumeId, UUID analysisTaskId, boolean duplicate) {}
}
