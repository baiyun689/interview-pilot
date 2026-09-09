package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.AssessmentModels.*;
import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import static interview.pilot.recruitment.infrastructure.AssessmentEntities.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.recruitment.infrastructure.HiringStore;
import interview.pilot.interview.domain.InterviewPhase;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringAssessmentService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;
  private final AiProviderService providers;
  private final ObjectMapper json;
  private final Validator validator;
  private final EnterpriseKnowledgeService knowledge;
  public HiringAssessmentService(HiringStore store, HiringAccess access, OrganizationService organizations,
      AiProviderService providers, ObjectMapper json, Validator validator, EnterpriseKnowledgeService knowledge) {
    this.store = store; this.access = access; this.organizations = organizations;
    this.providers = providers; this.json = json; this.validator = validator;
    this.knowledge = knowledge;
  }

  public WorkView analyze(CurrentUser user, Long orgId, Long applicationId, String providerId) {
    var application = authorizedApplication(user, orgId, applicationId);
    if (application.status.equals("WITHDRAWN") || application.status.equals("FINISHED")) throw conflict("当前投递不能发起分析");
    var existing = store.one(Work.class,
        "from HiringWork where applicationId=?1 and submissionNo=?2 and kind='APPLICATION_ANALYSIS' order by id desc",
        application.id, application.submissionNo);
    if (existing.isPresent()) return view(existing.get());
    var provider = providers.resolveEnabled(providerId);
    var resume = store.find(ResumeRevision.class, application.resumeRevisionId, false).orElseThrow();
    var job = store.one(JobRevision.class, "from HiringJobRevision where jobId=?1 and revision=?2", application.jobId, application.jobRevision).orElseThrow();
    var input = new AnalysisInput(provider.id(), provider.model(), "hiring-analysis-v1",
        fragments(job.description, "j", 50), fragments(resume.parsedText, "r", 100));
    var task = store.add(AsyncTaskEntity.pending(user.databaseId(), AsyncTaskType.HIRING_WORK,
        "hiring:analysis:" + application.id + ":" + application.submissionNo, "{}"));
    var work = new Work(); work.organizationId = orgId; work.jobId = application.jobId;
    work.applicationId = application.id; work.submissionNo = application.submissionNo;
    work.taskId = task.getId(); work.kind = "APPLICATION_ANALYSIS"; work.status = "PENDING";
    work.inputSnapshot = json.writeValueAsString(input); work.createdAt = Instant.now(); work.nextAttemptAt = work.createdAt;
    store.add(work); organizations.audit(user, orgId, "APPLICATION_ANALYSIS_REQUESTED", applicationId);
    return view(work);
  }

  @Transactional(readOnly = true)
  public WorkView analysis(CurrentUser user, Long orgId, Long applicationId) {
    var application = store.find(Application.class, applicationId, false).orElseThrow(HiringAccess::notFound);
    if (!application.organizationId.equals(orgId)) throw notFound();
    if (access.member(user, orgId, false).role.equals("INTERVIEWER")) throw forbidden();
    access.job(user, orgId, application.jobId, false);
    return store.one(Work.class,
        "from HiringWork where applicationId=?1 and submissionNo=?2 and kind='APPLICATION_ANALYSIS' order by id desc",
        application.id, application.submissionNo).map(this::view).orElse(null);
  }

  public WorkView retryAnalysis(CurrentUser user, Long orgId, Long workId) {
    var reference = store.find(Work.class, workId, false).orElseThrow(HiringAccess::notFound);
    if (!reference.organizationId.equals(orgId) || !reference.kind.equals("APPLICATION_ANALYSIS")) throw notFound();
    var application = authorizedApplication(user, orgId, reference.applicationId);
    var work = store.find(Work.class, workId, true).orElseThrow();
    if (!work.status.equals("FAILED")) throw conflict("只有失败的任务可以重试");
    if (application.submissionNo != work.submissionNo || application.status.equals("WITHDRAWN") || application.status.equals("FINISHED")) throw conflict("投递状态已变化，不能重试旧分析");
    var task = store.find(AsyncTaskEntity.class, work.taskId, true).orElseThrow();
    work.status = "PENDING"; work.error = null; work.attempts = 0; work.leaseToken = null; work.leaseUntil = null; work.nextAttemptAt = Instant.now();
    task.setStatus(interview.pilot.async.domain.AsyncTaskStatus.PENDING); task.setExecutionEpoch(task.getExecutionEpoch() + 1);
    task.setLastPublishedAt(null); task.setLastError(null);
    organizations.audit(user, orgId, "APPLICATION_ANALYSIS_RETRIED", application.id);
    return view(work);
  }

  public SchemeView saveScheme(CurrentUser user, Long orgId, Long jobId, Long schemeId, SchemeInput input) {
    access.job(user, orgId, jobId, true); validateDefinition(input.definition(), false);
    var scheme = schemeId == null ? new Scheme() : store.find(Scheme.class, schemeId, true)
        .filter(s -> s.organizationId.equals(orgId) && s.jobId.equals(jobId)).orElseThrow(HiringAccess::notFound);
    version(input.version(), scheme.version);
    scheme.organizationId = orgId; scheme.jobId = jobId; scheme.name = input.name().trim(); scheme.definition = json.writeValueAsString(input.definition());
    if (scheme.id == null) store.add(scheme); store.flush();
    organizations.audit(user, orgId, "SCHEME_SAVED", scheme.id); return schemeView(scheme);
  }

  public SchemeRevisionView publishScheme(CurrentUser user, Long orgId, Long schemeId, long expectedVersion) {
    var reference = store.find(Scheme.class, schemeId, false).filter(s -> s.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    access.job(user, orgId, reference.jobId, true);
    var scheme = store.find(Scheme.class, schemeId, true).orElseThrow(); version(expectedVersion, scheme.version);
    var definition = json.readValue(scheme.definition, Definition.class); validateDefinition(definition, true);
    var provider = providers.resolveEnabled(definition.providerId());
    var scope = definition.knowledgeBaseIds().isEmpty() ? null : knowledge.scope(user, orgId, definition.knowledgeBaseIds());
    var revision = new SchemeRevision(); revision.schemeId = scheme.id; revision.revision = ++scheme.publishedRevision;
    revision.name = scheme.name; revision.definition = scheme.definition; revision.providerId = provider.id(); revision.modelName = provider.model(); revision.publishedAt = Instant.now();
    revision.knowledgeScopeSnapshot = scope == null ? null : json.writeValueAsString(scope);
    store.add(revision);
    if (scope != null) for (var document : scope.documents()) {
      var ref = new interview.pilot.recruitment.infrastructure.HiringKnowledgeReference();
      ref.documentId = document.documentId(); ref.indexRevision = document.indexRevision();
      ref.referenceKey = "scheme:" + revision.id; store.add(ref);
    }
    organizations.audit(user, orgId, "SCHEME_PUBLISHED", scheme.id); return revisionView(revision);
  }

  public void retireRevision(CurrentUser user, Long orgId, Long schemeId, int revisionNo) {
    var scheme = store.find(Scheme.class, schemeId, false).filter(s -> s.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    access.job(user, orgId, scheme.jobId, true);
    var revision = store.one(SchemeRevision.class, "from HiringSchemeRevision where schemeId=?1 and revision=?2", schemeId, revisionNo).orElseThrow(HiringAccess::notFound);
    revision.retired = true;
    store.list(interview.pilot.recruitment.infrastructure.HiringKnowledgeReference.class,
        "from HiringKnowledgeReference where referenceKey=?1", 0, 1000, "scheme:" + revision.id).forEach(store::remove);
    organizations.audit(user, orgId, "SCHEME_REVISION_RETIRED", revision.id);
  }

  @Transactional(readOnly = true)
  public List<SchemeView> schemes(CurrentUser user, Long orgId, Long jobId) {
    access.job(user, orgId, jobId, false);
    return store.list(Scheme.class, "from HiringScheme where organizationId=?1 and jobId=?2 order by id desc", 0, 100, orgId, jobId)
        .stream().map(this::schemeView).toList();
  }

  @Transactional(readOnly = true)
  public List<SchemeRevisionView> revisions(CurrentUser user, Long orgId, Long schemeId) {
    var scheme = store.find(Scheme.class, schemeId, false).filter(s -> s.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    access.job(user, orgId, scheme.jobId, false);
    return store.list(SchemeRevision.class, "from HiringSchemeRevision where schemeId=?1 order by revision desc", 0, 100, schemeId)
        .stream().map(this::revisionView).toList();
  }

  public void validateDefinition(Definition definition, boolean publishing) {
    if (definition == null || !validator.validate(definition).isEmpty()) throw conflict("面试方案配置不完整");
    var phases = new HashSet<InterviewPhase>();
    int total = 0;
    for (var stage : definition.stages()) {
      if (!phases.add(stage.phase())) throw conflict("面试阶段不能重复");
      if (stage.phase() == InterviewPhase.SELF_INTRODUCTION && (stage.questionCount() != 1 || stage.followUpLimit() != 0)) throw conflict("自我介绍只能有一题且不追问");
      total += stage.questionCount();
    }
    if (total < 2 || total > 20) throw conflict("主问题总数应在 2 到 20 题之间");
    var questionIds = new HashSet<String>();
    var rubricIds = new HashSet<String>();
    for (var question : definition.commonQuestions()) {
      if (!phases.contains(question.phase()) || !questionIds.add(question.id())) throw conflict("公共题编号重复或阶段不存在");
      for (var rubric : question.rubric()) if (!rubricIds.add(rubric.id())) throw conflict("考察点编号必须在方案内唯一");
    }
    for (var stage : definition.stages()) {
      long common = definition.commonQuestions().stream().filter(q -> q.phase() == stage.phase()).count();
      if (common > stage.questionCount()) throw conflict("公共题数量超过阶段题量");
    }
    if (publishing && definition.commonQuestions().isEmpty()) throw conflict("发布前至少确认一道公共题和评分标准");
  }

  private Application authorizedApplication(CurrentUser user, Long orgId, Long applicationId) {
    var reference = store.find(Application.class, applicationId, false).filter(a -> a.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    access.job(user, orgId, reference.jobId, true);
    return store.find(Application.class, applicationId, true).orElseThrow();
  }
  private WorkView view(Work work) {
    return new WorkView(work.id, work.kind, work.status, work.error, work.attempts, work.createdAt,
        json.readValue(work.inputSnapshot, AnalysisInput.class), work.outputSnapshot == null ? null : json.readValue(work.outputSnapshot, AnalysisResult.class));
  }
  private SchemeView schemeView(Scheme scheme) { return new SchemeView(scheme.id, scheme.jobId, scheme.name, json.readValue(scheme.definition, Definition.class), scheme.publishedRevision, scheme.version); }
  private SchemeRevisionView revisionView(SchemeRevision revision) { return new SchemeRevisionView(revision.id, revision.revision, revision.name, json.readValue(revision.definition, Definition.class), revision.providerId, revision.modelName, revision.publishedAt, revision.retired); }

  static List<Fragment> fragments(String text, String prefix, int max) {
    var fragments = new ArrayList<Fragment>();
    for (String paragraph : text.split("\\R+")) {
      String value = paragraph.trim();
      for (int offset = 0; offset < value.length(); offset += 800) {
        if (fragments.size() == max) throw conflict("材料过长，请精简后重新提交");
        fragments.add(new Fragment(prefix + (fragments.size() + 1), value.substring(offset, Math.min(value.length(), offset + 800))));
      }
    }
    if (fragments.isEmpty()) throw conflict("材料没有可分析的内容");
    return List.copyOf(fragments);
  }
}
