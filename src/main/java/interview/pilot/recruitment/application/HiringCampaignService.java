package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.CampaignModels.*;
import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import static interview.pilot.recruitment.infrastructure.AssessmentEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.*;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.async.domain.*;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.recruitment.infrastructure.*;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringCampaignService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;
  private final HiringPreparationEngine preparation;
  private final ObjectMapper json;
  private final int maxMembers;
  private final int evidenceRetentionDays;
  public HiringCampaignService(HiringStore store, HiringAccess access, OrganizationService organizations,
      HiringPreparationEngine preparation, ObjectMapper json,
      @Value("${app.hiring.max-batch-members:200}") int maxMembers,
      @Value("${app.hiring.evidence-retention-days:90}") int evidenceRetentionDays) {
    this.store = store; this.access = access; this.organizations = organizations;
    this.preparation = preparation; this.json = json; this.maxMembers = Math.min(200, Math.max(1, maxMembers));
    this.evidenceRetentionDays = Math.max(1, evidenceRetentionDays);
  }
  public BatchDetail create(CurrentUser user, Long orgId, Long jobId, BatchInput input) {
    access.job(user, orgId, jobId, true);
    String hash = hash(json.writeValueAsString(input));
    var existing = store.one(Batch.class, "from HiringBatch where organizationId=?1 and requestKey=?2", orgId, input.requestKey());
    if (existing.isPresent()) {
      if (!existing.get().jobId.equals(jobId) || !existing.get().requestHash.equals(hash)) throw conflict("幂等键已用于不同批次内容");
      return detailView(existing.get());
    }
    var reference = store.find(SchemeRevision.class, input.schemeRevisionId(), false).orElseThrow(HiringAccess::notFound);
    store.find(Scheme.class, reference.schemeId, false)
        .filter(s -> s.organizationId.equals(orgId) && s.jobId.equals(jobId)).orElseThrow(HiringAccess::notFound);
    var revision = store.find(SchemeRevision.class, reference.id, true).orElseThrow();
    if (revision.retired) throw conflict("此方案版本已停用");
    var definition = json.readValue(revision.definition, AssessmentModels.Definition.class);
    if (input.applicationIds().isEmpty() || input.applicationIds().size() > maxMembers
        || new HashSet<>(input.applicationIds()).size() != input.applicationIds().size()) throw conflict("候选人数超过限制或重复");
    try { ZoneId.of(input.timezone()); } catch (DateTimeException ex) { throw conflict("无效的展示时区"); }
    if (input.opensAt().isAfter(input.latestStartAt()) || !input.latestStartAt().isAfter(Instant.now())
        || input.latestStartAt().plusSeconds(definition.durationMinutes() * 60L).isAfter(input.closesAt()))
      throw conflict("开放窗口必须保证最晚开始时仍可获得完整面试时长");
    var applications = new ArrayList<Application>();
    for (Long id : input.applicationIds().stream().sorted().toList()) {
      var app = store.find(Application.class, id, true)
          .filter(a -> a.organizationId.equals(orgId) && a.jobId.equals(jobId)).orElseThrow(HiringAccess::notFound);
      requireActive(app);
      if (store.one(Invitation.class, "from HiringInterviewInvitation where applicationId=?1 and roundNo=?2 and status in ('CREATED','ISSUED','ACCEPTED','STARTED')", id, input.roundNo()).isPresent())
        throw conflict("所选候选人在本轮已有有效邀请，请先处理旧邀请");
      applications.add(app);
    }
    var batch = new Batch(); batch.organizationId = orgId; batch.jobId = jobId;
    batch.schemeRevisionId = revision.id; batch.name = input.name().trim(); batch.roundNo = input.roundNo();
    batch.requestKey = input.requestKey(); batch.requestHash = hash; batch.opensAt = input.opensAt();
    batch.latestStartAt = input.latestStartAt(); batch.closesAt = input.closesAt(); batch.timezone = input.timezone(); batch.createdAt = Instant.now();
    store.add(batch);
    var scope = revision.knowledgeScopeSnapshot == null ? null : json.readValue(revision.knowledgeScopeSnapshot, ValidatedKnowledgeScope.class);
    if (scope != null) for (var doc : scope.documents()) {
      var ref = new HiringKnowledgeReference(); ref.documentId = doc.documentId(); ref.indexRevision = doc.indexRevision();
      ref.referenceKey = "batch:" + batch.id; ref.expiresAt = batch.closesAt.plusSeconds(evidenceRetentionDays * 86400L); store.add(ref);
    }
    for (var app : applications) {
      var resume = store.find(ResumeRevision.class, app.resumeRevisionId, false).orElseThrow();
      var job = store.one(JobRevision.class, "from HiringJobRevision where jobId=?1 and revision=?2", app.jobId, app.jobRevision).orElseThrow();
      var snapshot = new PreparationInput(revision.providerId, revision.modelName, "hiring-preparation-v1", definition,
          HiringAssessmentService.fragments(job.description, "j", 50), HiringAssessmentService.fragments(resume.parsedText, "r", 100), scope);
      var task = store.add(AsyncTaskEntity.pending(user.databaseId(), AsyncTaskType.HIRING_WORK, "hiring:prepare:" + batch.id + ":" + app.id, "{}"));
      var work = new Work(); work.organizationId = orgId; work.jobId = jobId; work.applicationId = app.id;
      work.submissionNo = app.submissionNo; work.taskId = task.getId(); work.kind = "CANDIDATE_PREPARATION";
      work.status = "PENDING"; work.inputSnapshot = json.writeValueAsString(snapshot); work.createdAt = Instant.now(); work.nextAttemptAt = work.createdAt; store.add(work);
      var member = new BatchMember(); member.batchId = batch.id; member.applicationId = app.id; member.submissionNo = app.submissionNo; member.jobRevision = app.jobRevision; member.workId = work.id; store.add(member);
      var invitation = new Invitation(); invitation.publicId = UUID.randomUUID().toString(); invitation.batchMemberId = member.id;
      invitation.applicationId = app.id; invitation.candidateId = app.candidateId; invitation.roundNo = batch.roundNo; invitation.status = "CREATED"; store.add(invitation);
    }
    organizations.audit(user, orgId, "BATCH_CREATED", batch.id); return detailView(batch);
  }
  @Transactional(readOnly = true)
  public List<BatchView> list(CurrentUser user, Long orgId, Long jobId) {
    requireManager(user, orgId, jobId, false);
    return store.list(Batch.class, "from HiringBatch where organizationId=?1 and jobId=?2 order by id desc", 0, 100, orgId, jobId).stream().map(this::batchView).toList();
  }
  @Transactional(readOnly = true)
  public BatchDetail detail(CurrentUser user, Long orgId, Long id) { return detailView(authorized(user, orgId, id, false)); }

  public MemberView approve(CurrentUser user, Long orgId, Long batchId, Long memberId, ApprovalInput input) {
    var batch = authorized(user, orgId, batchId, true);
    var member = member(batch, memberId); var app = store.find(Application.class, member.applicationId, true).orElseThrow(); requireCurrent(app, member);
    var invite = invitation(member); if (!invite.status.equals("CREATED")) throw conflict("下发后不能修改题目");
    version(input.version(), member.version);
    var work = store.find(Work.class, member.workId, true).orElseThrow();
    if (!work.status.equals("COMPLETED")) throw conflict("题目尚未准备完成");
    var original = json.readValue(work.outputSnapshot, PreparedDeck.class);
    var approved = new PreparedDeck(input.deck().questions(), original.knowledgeEvidence());
    try { preparation.validate(json.readValue(work.inputSnapshot, PreparationInput.class), approved); }
    catch (IllegalArgumentException ex) { throw conflict(ex.getMessage()); }
    member.approvedSnapshot = json.writeValueAsString(approved); member.approvedBy = user.databaseId(); member.approvedAt = Instant.now();
    store.flush(); organizations.audit(user, orgId, "BATCH_MEMBER_APPROVED", member.id); return memberView(member);
  }
  public void retry(CurrentUser user, Long orgId, Long batchId, Long memberId) {
    var batch = authorized(user, orgId, batchId, true); var member = member(batch, memberId);
    requireCurrent(store.find(Application.class, member.applicationId, true).orElseThrow(), member);
    if (!invitation(member).status.equals("CREATED")) throw conflict("已下发成员不能重新准备");
    var work = store.find(Work.class, member.workId, true).orElseThrow();
    if (!work.status.equals("FAILED")) throw conflict("仅失败的准备任务可以重试");
    var task = store.find(AsyncTaskEntity.class, work.taskId, true).orElseThrow();
    work.status = "PENDING"; work.error = null; work.attempts = 0; work.nextAttemptAt = Instant.now(); work.leaseToken = null; work.leaseUntil = null;
    task.setExecutionEpoch(task.getExecutionEpoch() + 1); task.setStatus(AsyncTaskStatus.PENDING); task.setLastPublishedAt(null); task.setLastError(null);
    member.approvedSnapshot = null; member.approvedBy = null; member.approvedAt = null;
    organizations.audit(user, orgId, "BATCH_MEMBER_RETRIED", member.id);
  }
  public PublishResult publish(CurrentUser user, Long orgId, Long batchId) {
    var batch = authorized(user, orgId, batchId, true);
    if (!batch.latestStartAt.isAfter(Instant.now())) throw conflict("面试开放窗口已过期");
    var issued = new ArrayList<Long>(); var skipped = new ArrayList<Long>();
    for (var member : members(batch)) {
      var app = store.find(Application.class, member.applicationId, true).orElseThrow();
      var invite = invitation(member);
      if (invite.status.equals("ISSUED") || invite.status.equals("ACCEPTED") || invite.status.equals("STARTED") || invite.status.equals("COMPLETED")) { issued.add(member.id); continue; }
      if (member.approvedSnapshot == null || !invite.status.equals("CREATED") || !current(app, member)) { skipped.add(member.id); continue; }
      invite.status = "ISSUED"; invite.issuedAt = Instant.now(); app.status = "IN_PROCESS"; issued.add(member.id);
      HiringNotifications.enqueue(store,invite,"ISSUED",0,Instant.now());
    }
    organizations.audit(user, orgId, "BATCH_PUBLISHED", batch.id); return new PublishResult(issued, skipped);
  }
  private Batch authorized(CurrentUser user, Long orgId, Long id, boolean write) {
    var batch = store.find(Batch.class, id, false).filter(b -> b.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    requireManager(user, orgId, batch.jobId, write); return store.find(Batch.class, id, write).orElseThrow();
  }
  private void requireManager(CurrentUser user, Long orgId, Long jobId, boolean write) {
    if (access.member(user, orgId, write).role.equals("INTERVIEWER")) throw forbidden(); access.job(user, orgId, jobId, write);
  }
  private BatchMember member(Batch batch, Long id) { return store.find(BatchMember.class, id, true).filter(m -> m.batchId.equals(batch.id)).orElseThrow(HiringAccess::notFound); }
  private List<BatchMember> members(Batch batch) { return store.list(BatchMember.class, "from HiringBatchMember where batchId=?1 order by applicationId", 0, 200, batch.id); }
  private Invitation invitation(BatchMember member) { return store.one(Invitation.class, "from HiringInterviewInvitation where batchMemberId=?1", member.id).orElseThrow(); }
  private BatchDetail detailView(Batch batch) { return new BatchDetail(batchView(batch), members(batch).stream().map(this::memberView).toList()); }
  private BatchView batchView(Batch batch) {
    var counts = store.one(Object[].class, "select count(m), sum(case when w.status='COMPLETED' then 1 else 0 end), "
        + "sum(case when w.status in ('FAILED','CANCELLED') then 1 else 0 end), sum(case when m.approvedAt is not null then 1 else 0 end), "
        + "sum(case when i.issuedAt is not null then 1 else 0 end) from HiringBatchMember m, HiringWork w, HiringInterviewInvitation i "
        + "where m.batchId=?1 and m.workId=w.id and i.batchMemberId=m.id", batch.id).orElseThrow();
    return new BatchView(batch.id, batch.name, batch.jobId, batch.roundNo, store.find(SchemeRevision.class, batch.schemeRevisionId, false).orElseThrow().name,
        batch.opensAt, batch.latestStartAt, batch.closesAt, batch.timezone, number(counts[0]), number(counts[1]), number(counts[2]), number(counts[3]), number(counts[4]), batch.version);
  }
  private int number(Object value) { return value == null ? 0 : ((Number)value).intValue(); }
  private MemberView memberView(BatchMember member) {
    var app = store.find(Application.class, member.applicationId, false).orElseThrow();
    var work = store.find(Work.class, member.workId, false).orElseThrow();
    var deck = member.approvedSnapshot == null ? work.outputSnapshot : member.approvedSnapshot;
    return new MemberView(member.id, app.id, store.find(UserAccountEntity.class, app.candidateId, false).orElseThrow().getDisplayName(), work.status, work.error,
        deck == null ? null : json.readValue(deck, PreparedDeck.class), json.readValue(work.inputSnapshot, PreparationInput.class).resumeEvidence(), member.approvedSnapshot != null, invitation(member).status, member.version);
  }
  static void requireActive(Application app) { if (app.status.equals("WITHDRAWN") || app.status.equals("FINISHED")) throw conflict("投递已撤回或结束"); }
  static boolean current(Application app, BatchMember member) { return app.submissionNo == member.submissionNo && !app.status.equals("WITHDRAWN") && !app.status.equals("FINISHED"); }
  static void requireCurrent(Application app, BatchMember member) { if (!current(app, member)) throw conflict("投递版本或状态已变更"); }
  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }
}
