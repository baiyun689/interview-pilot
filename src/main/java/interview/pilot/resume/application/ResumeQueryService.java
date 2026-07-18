package interview.pilot.resume.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.resume.api.ResumeResponse;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

@Service
public class ResumeQueryService {
  private final ResumeRepository resumeRepository;
  private final AsyncTaskRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final Validator validator;

  public ResumeQueryService(
      ResumeRepository resumeRepository,
      AsyncTaskRepository taskRepository,
      ObjectMapper objectMapper,
      Validator validator) {
    this.resumeRepository = resumeRepository;
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
    this.validator = validator;
  }

  @Transactional(readOnly = true)
  public List<ResumeResponse> list() {
    return resumeRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional(readOnly = true)
  public ResumeResponse get(Long resumeId) {
    ResumeEntity resume = resumeRepository.findById(resumeId)
        .orElseThrow(() -> new BusinessException(
            "RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));
    return toResponse(resume);
  }

  private ResumeResponse toResponse(ResumeEntity resume) {
    var task = taskRepository.findByTaskTypeAndBizKey(
        AsyncTaskType.RESUME_ANALYSIS, "resume:" + resume.getId())
        .orElseThrow(() -> new BusinessException(
            "ANALYSIS_TASK_NOT_FOUND",
            "The resume analysis task could not be found",
            HttpStatus.INTERNAL_SERVER_ERROR));
    return new ResumeResponse(
        resume.getId(),
        resume.getOriginalFilename(),
        resume.getStatus(),
        false,
        task.getTaskId(),
        resume.getCreatedAt(),
        readProfile(resume.getSkillsSnapshot()),
        resume.getFailureReason());
  }

  private ResumeProfile readProfile(String snapshot) {
    if (snapshot == null) {
      return null;
    }
    try {
      ResumeProfile profile = objectMapper.readValue(snapshot, ResumeProfile.class);
      if (profile == null || !validator.validate(profile).isEmpty()) {
        throw new IllegalStateException("Stored resume profile is invalid");
      }
      return profile;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored resume profile is invalid");
    }
  }
}
