package interview.pilot.interview.infrastructure;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.application.InterviewCreation;
import interview.pilot.interview.application.InterviewCreationStore;
import interview.pilot.interview.application.InterviewResponseMapper;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JpaInterviewCreationStore implements InterviewCreationStore {
  private final ResumeRepository resumes;
  private final JobProfileRepository jobs;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final ObjectMapper objectMapper;
  private final InterviewResponseMapper responseMapper;

  public JpaInterviewCreationStore(
      ResumeRepository resumes,
      JobProfileRepository jobs,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      ObjectMapper objectMapper,
      InterviewResponseMapper responseMapper) {
    this.resumes = resumes;
    this.jobs = jobs;
    this.sessions = sessions;
    this.turns = turns;
    this.objectMapper = objectMapper;
    this.responseMapper = responseMapper;
  }

  @Override
  @Transactional
  public InterviewSessionResponse create(InterviewCreation creation) {
    var resume = resumes.findByIdAndUserAccountId(creation.resumeId(), creation.userAccountId())
        .orElseThrow(() -> new BusinessException(
            "RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));
    if (resume.getStatus() != ResumeStatus.READY) {
      throw new BusinessException(
          "RESUME_NOT_READY", "Resume analysis is not ready", HttpStatus.CONFLICT);
    }
    try {
      var job = jobs.save(JobProfileEntity.create(
          creation.userAccountId(), creation.jobTitle(), creation.jdText(),
          objectMapper.writeValueAsString(creation.requirements()),
          objectMapper.writeValueAsString(creation.skillSnapshot())));
      var session = InterviewSessionEntity.create(
          creation.userAccountId(), creation.resumeId(), job.getId(), creation.difficulty(), creation.totalTurnBudget(),
          creation.providerId(), creation.modelName(),
          objectMapper.writeValueAsString(creation.plan()));
      session.start();
      session = sessions.saveAndFlush(session);
      var turn = turns.save(InterviewTurnEntity.firstAsked(
          session.getId(), creation.difficulty(), creation.firstQuestion().question(),
          creation.firstQuestion().targetCompetency()));
      return responseMapper.map(session, job, List.of(turn));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Interview snapshots could not be serialized");
    }
  }
}
