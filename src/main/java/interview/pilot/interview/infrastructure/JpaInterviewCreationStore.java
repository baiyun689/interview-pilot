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
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
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
  private final KnowledgeBaseRepository knowledgeBases;
  private final InterviewKnowledgeBaseRepository kbAssociations;
  private final ObjectMapper objectMapper;
  private final InterviewResponseMapper responseMapper;

  public JpaInterviewCreationStore(
      ResumeRepository resumes,
      JobProfileRepository jobs,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      KnowledgeBaseRepository knowledgeBases,
      InterviewKnowledgeBaseRepository kbAssociations,
      ObjectMapper objectMapper,
      InterviewResponseMapper responseMapper) {
    this.resumes = resumes;
    this.jobs = jobs;
    this.sessions = sessions;
    this.turns = turns;
    this.knowledgeBases = knowledgeBases;
    this.kbAssociations = kbAssociations;
    this.objectMapper = objectMapper;
    this.responseMapper = responseMapper;
  }

  @Override
  @Transactional
  public InterviewSessionResponse create(InterviewCreation creation) {
    if (creation.resumeId() != null) {
      var resume = resumes.findByIdAndUserAccountId(creation.resumeId(), creation.userAccountId())
          .orElseThrow(() -> new BusinessException(
              "RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));
      if (resume.getStatus() != ResumeStatus.READY) {
        throw new BusinessException(
            "RESUME_NOT_READY", "Resume analysis is not ready", HttpStatus.CONFLICT);
      }
    }
    try {
      var job = jobs.save(JobProfileEntity.create(
          creation.userAccountId(), creation.jobTitle(), creation.jdText(),
          objectMapper.writeValueAsString(creation.requirements()),
          objectMapper.writeValueAsString(creation.skillSnapshot())));
      var session = InterviewSessionEntity.create(
          creation.userAccountId(), creation.resumeId(), job.getId(), creation.difficulty(),
          creation.totalTurnBudget(), creation.providerId(), creation.modelName(),
          objectMapper.writeValueAsString(creation.plan()));
      if (creation.knowledgeScope() != null) {
        session.setKnowledgeScopeSnapshot(
            objectMapper.writeValueAsString(creation.knowledgeScope()));
      }
      session.start();
      session = sessions.saveAndFlush(session);

      if (creation.knowledgeScope() != null) {
        for (var kbUuid : creation.knowledgeScope().knowledgeBaseIds()) {
          KnowledgeBaseEntity kb = knowledgeBases.findByKnowledgeBaseIdAndUserAccountId(
              kbUuid, creation.userAccountId()).orElseThrow(() ->
              new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "KB not found", HttpStatus.NOT_FOUND));
          kbAssociations.save(InterviewKnowledgeBaseEntity.create(session.getId(), kb.getId()));
        }
      }

      var turn = InterviewTurnEntity.firstAsked(
          session.getId(), creation.difficulty(), creation.firstQuestion().question(),
          creation.firstQuestion().targetCompetency());
      if (!creation.firstRagSnapshot().equals(RagContextSnapshot.notConfigured())) {
        turn.setRagStatus(creation.firstRagSnapshot().status());
        turn.setRagContextSnapshot(objectMapper.writeValueAsString(creation.firstRagSnapshot()));
      }
      turn = turns.save(turn);

      var kbSummaries = creation.knowledgeScope() == null ? List.<InterviewSessionResponse.KnowledgeBaseSummary>of()
          : creation.knowledgeScope().knowledgeBaseIds().stream()
              .map(uuid -> {
                var kb = knowledgeBases.findByKnowledgeBaseIdAndUserAccountId(
                    uuid, creation.userAccountId()).orElse(null);
                return kb == null ? null : new InterviewSessionResponse.KnowledgeBaseSummary(
                    kb.getKnowledgeBaseId(), kb.getName());
              }).filter(s -> s != null).toList();

      return responseMapper.map(session, job, List.of(turn), kbSummaries);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Interview snapshots could not be serialized");
    }
  }
}
