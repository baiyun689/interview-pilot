package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.application.HiringModels.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.recruitment.infrastructure.HiringStore;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class RecruitmentService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;

  public RecruitmentService(HiringStore store, HiringAccess access, OrganizationService organizations) {
    this.store = store; this.access = access; this.organizations = organizations;
  }

  public JobView createJob(CurrentUser user, Long orgId, JobInput input) {
    var member = access.member(user, orgId, true);
    if (member.role.equals("INTERVIEWER")) throw forbidden();
    var job = new Job(); job.organizationId = orgId; job.status = "DRAFT";
    job.createdAt = Instant.now(); copy(input, job); store.add(job);
    var assignment = new JobAssignment(); assignment.organizationId = orgId;
    assignment.jobId = job.id; assignment.userAccountId = user.databaseId(); store.add(assignment);
    organizations.audit(user, orgId, "JOB_CREATED", job.id);
    return view(job, true);
  }

  public JobView updateJob(CurrentUser user, Long orgId, Long jobId, JobInput input) {
    var job = access.job(user, orgId, jobId, true); version(input.version(), job.version);
    if (job.status.equals("CLOSED")) throw conflict("岗位已关闭，请创建新岗位");
    copy(input, job); store.flush();
    organizations.audit(user, orgId, "JOB_DRAFT_UPDATED", job.id);
    return view(job, true);
  }

  public JobView publishJob(CurrentUser user, Long orgId, Long jobId, long expectedVersion) {
    var job = access.job(user, orgId, jobId, true); version(expectedVersion, job.version);
    if (job.status.equals("CLOSED")) throw conflict("岗位已关闭，请创建新岗位");
    var revision = new JobRevision(); revision.jobId = job.id;
    revision.revision = ++job.publishedRevision; revision.title = job.title;
    revision.description = job.description; revision.location = job.location;
    revision.employmentType = job.employmentType; revision.publishedAt = Instant.now();
    store.add(revision); job.status = "PUBLISHED"; store.flush();
    organizations.audit(user, orgId, "JOB_PUBLISHED", job.id);
    return view(job, true);
  }

  public JobView closeJob(CurrentUser user, Long orgId, Long jobId, long expectedVersion) {
    var job = access.job(user, orgId, jobId, true); version(expectedVersion, job.version);
    if (!job.status.equals("PUBLISHED")) throw conflict("仅已发布岗位可以关闭");
    job.status = "CLOSED"; store.flush();
    organizations.audit(user, orgId, "JOB_CLOSED", job.id); return view(job, true);
  }

  public void assignJob(CurrentUser user, Long orgId, Long jobId, Long accountId, boolean assigned) {
    access.admin(user, orgId, true);
    store.find(Job.class, jobId, true).filter(j -> j.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    store.one(Membership.class,
        "from HiringMembership where organizationId=?1 and userAccountId=?2 and active=true", orgId, accountId)
        .orElseThrow(HiringAccess::notFound);
    var existing = store.one(JobAssignment.class,
        "from HiringJobAssignment where jobId=?1 and userAccountId=?2", jobId, accountId);
    if (assigned && existing.isEmpty()) {
      var assignment = new JobAssignment(); assignment.organizationId = orgId;
      assignment.jobId = jobId; assignment.userAccountId = accountId; store.add(assignment);
    } else if (!assigned) existing.ifPresent(store::remove);
    organizations.audit(user, orgId, assigned ? "JOB_ACCESS_GRANTED" : "JOB_ACCESS_REVOKED", jobId);
  }

  @Transactional(readOnly = true)
  public List<Long> assignments(CurrentUser user, Long orgId, Long jobId) {
    access.admin(user, orgId, false);
    store.find(Job.class, jobId, false).filter(j -> j.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    return store.list(JobAssignment.class, "from HiringJobAssignment where jobId=?1", 0, 200, jobId)
        .stream().map(a -> a.userAccountId).toList();
  }

  @Transactional(readOnly = true)
  public Page<JobView> companyJobs(CurrentUser user, Long orgId, int page) {
    var member = access.member(user, orgId, false);
    String query = "from HiringJob j where j.organizationId=?1";
    Object[] parameters;
    if (member.role.equals("ADMIN")) parameters = new Object[]{orgId};
    else {
      query += " and exists (select a.id from HiringJobAssignment a where a.jobId=j.id and a.userAccountId=?2)";
      parameters = new Object[]{orgId, user.databaseId()};
    }
    var rows = store.list(Job.class, query + " order by j.id desc", offset(page), 26, parameters);
    return new Page<>(rows.stream().limit(25).map(j -> view(j, !member.role.equals("INTERVIEWER"))).toList(), page, rows.size() > 25);
  }

  @Transactional(readOnly = true)
  public Page<JobView> publicJobs(int page) {
    var rows = store.list(Job.class, "from HiringJob j where status='PUBLISHED' and exists (select o.id from HiringOrganization o where o.id=j.organizationId and o.active=true) order by id desc", offset(page), 26);
    return new Page<>(rows.stream().limit(25).map(this::publishedView).toList(), page, rows.size() > 25);
  }

  @Transactional(readOnly = true)
  public JobView publicJob(Long jobId) {
    return publishedView(store.find(Job.class, jobId, false)
        .filter(j -> j.status.equals("PUBLISHED")).orElseThrow(HiringAccess::notFound));
  }

  public ApplicationView apply(CurrentUser user, Long jobId, ApplicationInput input) {
    // Lock the published job before checking revision/duplicate submission; no LLM work runs here.
    var reference = store.find(Job.class, jobId, false).orElseThrow(HiringAccess::notFound);
    if (!store.find(Organization.class, reference.organizationId, true).orElseThrow().active) throw notFound();
    var job = store.find(Job.class, jobId, true).orElseThrow(HiringAccess::notFound);
    var existing = store.one(Application.class,
        "from HiringApplication where jobId=?1 and candidateId=?2", jobId, user.databaseId());
    existing = existing.flatMap(a -> store.find(Application.class, a.id, true));
    if (existing.isPresent() && !existing.get().status.equals("WITHDRAWN")) {
      var snapshot = store.find(ResumeRevision.class, existing.get().resumeRevisionId, false).orElseThrow();
      if (!input.resubmit() && input.resumeId().equals(snapshot.sourceResumeId)
          && input.jobRevision() == existing.get().jobRevision) return applicationView(existing.get());
      throw conflict("已经投递此岗位，请在投递记录中查看进度");
    }
    if (!job.status.equals("PUBLISHED")) throw conflict("岗位已关闭，不能投递");
    if (input.jobRevision() != job.publishedRevision) throw conflict("岗位要求已更新，请重新阅读后投递");
    if (existing.isPresent() && !input.resubmit()) throw conflict("此前投递已撤回，请明确选择重新投递");
    var resume = store.find(ResumeEntity.class, input.resumeId(), true)
        .filter(r -> r.getUserAccountId().equals(user.databaseId())).orElseThrow(HiringAccess::notFound);
    if (resume.getParsedText() == null || resume.getParsedText().isBlank()) throw conflict("简历暂无可用文本，请重新上传");
    var snapshot = new ResumeRevision(); snapshot.userAccountId = user.databaseId();
    snapshot.sourceResumeId = resume.getId(); snapshot.filename = resume.getOriginalFilename();
    snapshot.contentHash = resume.getContentHash(); snapshot.parsedText = resume.getParsedText();
    snapshot.createdAt = Instant.now(); store.add(snapshot);
    var application = existing.orElseGet(Application::new);
    application.organizationId = job.organizationId; application.jobId = job.id;
    application.candidateId = user.databaseId(); application.jobRevision = job.publishedRevision;
    application.resumeRevisionId = snapshot.id; application.status = "SUBMITTED";
    application.submissionNo++; application.submittedAt = Instant.now();
    if (application.id == null) store.add(application);
    event(user, application, existing.isPresent() ? "RESUBMITTED" : "SUBMITTED");
    HiringNotifications.applicationSubmitted(store, application);
    store.flush(); return applicationView(application);
  }

  public ApplicationView withdraw(CurrentUser user, Long applicationId, long expectedVersion) {
    var reference = store.find(Application.class, applicationId, false)
        .filter(a -> a.candidateId.equals(user.databaseId())).orElseThrow(HiringAccess::notFound);
    store.find(Organization.class, reference.organizationId, true).orElseThrow();
    var application = store.find(Application.class, applicationId, true)
        .filter(a -> a.candidateId.equals(user.databaseId())).orElseThrow(HiringAccess::notFound);
    if (application.status.equals("WITHDRAWN")) return applicationView(application);
    version(expectedVersion, application.version);
    if (application.status.equals("FINISHED")) throw conflict("招聘流程已结束");
    application.status = "WITHDRAWN"; event(user, application, "WITHDRAWN");
    HiringNotifications.applicationWithdrawn(store, application);
    for (var invitation : store.list(interview.pilot.recruitment.infrastructure.CampaignEntities.Invitation.class,
        "from HiringInterviewInvitation where applicationId=?1 and status in ('CREATED','ISSUED','ACCEPTED','STARTED')", 0, 200, applicationId)) {
      HiringInterviewLifecycle.cancel(store, invitation.id);
      invitation.status = "CANCELLED"; invitation.scheduleRevision++;
    }
    store.flush(); return applicationView(application);
  }

  @Transactional(readOnly = true)
  public Page<ApplicationView> mine(CurrentUser user, int page) {
    var rows = store.list(Application.class,
        "from HiringApplication where candidateId=?1 order by submittedAt desc, id desc", offset(page), 26, user.databaseId());
    return new Page<>(rows.stream().limit(25).map(this::applicationView).toList(), page, rows.size() > 25);
  }

  @Transactional(readOnly = true)
  public Page<ApplicationView> applications(CurrentUser user, Long orgId, Long jobId, int page) {
    var member = access.member(user, orgId, false);
    // Interviewers receive only explicitly assigned interview reports in the later review module.
    if (member.role.equals("INTERVIEWER")) throw forbidden();
    access.job(user, orgId, jobId, false);
    var rows = store.list(Application.class,
        "from HiringApplication where organizationId=?1 and jobId=?2 order by submittedAt desc, id desc",
        offset(page), 26, orgId, jobId);
    return new Page<>(rows.stream().limit(25).map(this::applicationView).toList(), page, rows.size() > 25);
  }

  @Transactional(readOnly = true)
  public ApplicationDetail detail(CurrentUser user, Long orgId, Long id) {
    var application = store.find(Application.class, id, false).orElseThrow(HiringAccess::notFound);
    if (orgId == null) {
      if (!application.candidateId.equals(user.databaseId())) throw notFound();
    } else {
      if (!application.organizationId.equals(orgId)) throw notFound();
      if (access.member(user, orgId, false).role.equals("INTERVIEWER")) throw forbidden();
      access.job(user, orgId, application.jobId, false);
    }
    var resume = store.find(ResumeRevision.class, application.resumeRevisionId, false).orElseThrow();
    var revision = revision(application.jobId, application.jobRevision);
    var events = store.list(ApplicationEvent.class,
        "from HiringApplicationEvent where applicationId=?1 order by id desc", 0, 100, id).stream()
        .map(e -> new EventView(e.action, e.submissionNo, e.jobRevision, e.resumeRevisionId, e.createdAt)).toList();
    return new ApplicationDetail(applicationView(application), revision.description, resume.filename, resume.parsedText, events);
  }

  private void event(CurrentUser user, Application application, String action) {
    var event = new ApplicationEvent(); event.applicationId = application.id;
    event.actorId = user.databaseId(); event.action = action; event.submissionNo = application.submissionNo;
    event.jobRevision = application.jobRevision; event.resumeRevisionId = application.resumeRevisionId;
    event.createdAt = Instant.now(); store.add(event);
  }

  private ApplicationView applicationView(Application application) {
    var revision = revision(application.jobId, application.jobRevision);
    var organization = store.find(Organization.class, application.organizationId, false).orElseThrow();
    var candidate = store.find(UserAccountEntity.class, application.candidateId, false).orElseThrow();
    return new ApplicationView(application.id, application.organizationId, application.jobId,
        organization.name, revision.title, application.candidateId, candidate.getDisplayName(),
        application.status, application.submissionNo, application.jobRevision, application.resumeRevisionId,
        application.submittedAt, application.version);
  }

  private JobRevision revision(Long jobId, int revision) {
    return store.one(JobRevision.class, "from HiringJobRevision where jobId=?1 and revision=?2", jobId, revision).orElseThrow();
  }

  private JobView publishedView(Job job) {
    if (!store.find(Organization.class, job.organizationId, false).orElseThrow().active) throw notFound();
    var revision = revision(job.id, job.publishedRevision);
    return new JobView(job.id, job.organizationId,
        store.find(Organization.class, job.organizationId, false).orElseThrow().name,
        revision.title, revision.description, revision.location, revision.employmentType,
        job.status, revision.revision, 0, false);
  }

  private JobView view(Job job, boolean canManage) {
    return new JobView(job.id, job.organizationId,
        store.find(Organization.class, job.organizationId, false).orElseThrow().name,
        job.title, job.description, job.location, job.employmentType,
        job.status, job.publishedRevision, job.version, canManage);
  }

  private static void copy(JobInput input, Job job) {
    job.title = input.title().trim(); job.description = input.description().trim();
    job.location = input.location().trim(); job.employmentType = input.employmentType().trim();
  }
  private static int offset(int page) {
    if (page < 0 || page > 10000) throw conflict("页码超出范围");
    return page * 25;
  }
}
