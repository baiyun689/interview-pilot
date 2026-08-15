package interview.pilot.resume.application;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

@Service
public class ResumeDeleteService {
  private final ResumeRepository resumeRepository;
  private final InterviewSessionRepository sessionRepository;
  private final AsyncTaskRepository taskRepository;
  private final TransactionTemplate transactionTemplate;

  public ResumeDeleteService(
      ResumeRepository resumeRepository,
      InterviewSessionRepository sessionRepository,
      AsyncTaskRepository taskRepository,
      PlatformTransactionManager transactionManager) {
    this.resumeRepository = resumeRepository;
    this.sessionRepository = sessionRepository;
    this.taskRepository = taskRepository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void delete(CurrentUser user, Long resumeId) {
    Long ownerId = requireOwner(user);
    transactionTemplate.executeWithoutResult(status -> {
      ResumeEntity resume = resumeRepository.findByIdAndUserAccountId(resumeId, ownerId)
          .orElseThrow(() -> new BusinessException(
              "RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));
      if (resume.getStatus() == ResumeStatus.PENDING
          || resume.getStatus() == ResumeStatus.ANALYZING) {
        throw new BusinessException(
            "RESUME_ANALYSIS_IN_PROGRESS",
            "Resume analysis is still in progress; wait until it finishes or fails",
            HttpStatus.CONFLICT);
      }
      // 面试在创建时已固化简历快照,解除引用后历史面试不受影响
      sessionRepository.clearResumeReference(resume.getId());
      taskRepository.deleteByTaskTypeAndBizKeyAndUserAccountId(
          AsyncTaskType.RESUME_ANALYSIS, bizKey(resume.getId()), ownerId);
      resumeRepository.delete(resume);
    });
  }

  private static String bizKey(Long resumeId) {
    return "resume:" + resumeId;
  }

  private static Long requireOwner(CurrentUser user) {
    return Objects.requireNonNull(Objects.requireNonNull(user, "user").databaseId(), "user.databaseId");
  }
}
