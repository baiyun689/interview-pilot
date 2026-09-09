package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.CampaignModels.*;
import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import interview.pilot.recruitment.infrastructure.AssessmentEntities.SchemeRevision;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.recruitment.infrastructure.HiringStore;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringInvitationService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;
  private final ObjectMapper json;
  @org.springframework.beans.factory.annotation.Value("${app.hiring.reminder-hours:24,1}")
  private int[] reminderHours = {24,1};
  public HiringInvitationService(HiringStore store, HiringAccess access, OrganizationService organizations, ObjectMapper json) {
    this.store=store; this.access=access; this.organizations=organizations; this.json=json;
  }
  @Transactional(readOnly = true)
  public HiringModels.Page<InvitationView> mine(CurrentUser user, int page) {
    if (page < 0 || page > 10000) throw conflict("无效页码");
    var rows = store.list(Invitation.class, "from HiringInterviewInvitation where candidateId=?1 and issuedAt is not null order by issuedAt desc,id desc", page*25, 26, user.databaseId());
    return new HiringModels.Page<>(rows.stream().limit(25).map(this::view).toList(), page, rows.size()>25);
  }
  public InvitationView schedule(CurrentUser user, String id, ScheduleInput input) {
    var invite = owned(user, id); var batch = batch(invite);
    var app = store.find(Application.class, invite.applicationId, false).orElseThrow();
    HiringCampaignService.requireCurrent(app, store.find(BatchMember.class, invite.batchMemberId, false).orElseThrow());
    if (!invite.status.equals("ISSUED") && !invite.status.equals("ACCEPTED")) throw conflict("当前邀请不能安排日程");
    if (input.plannedAt().equals(invite.plannedAt)) return view(invite);
    version(input.version(), invite.version);
    if (input.plannedAt().isBefore(batch.opensAt) || input.plannedAt().isAfter(batch.latestStartAt) || !input.plannedAt().isAfter(Instant.now()))
      throw conflict("计划时间须在面试窗口内且晚于当前时间");
    invite.status="ACCEPTED"; invite.plannedAt=input.plannedAt(); invite.scheduleRevision++;
    HiringNotifications.scheduled(store,invite,reminderHours);
    organizations.audit(user, batch.organizationId, "INVITATION_SCHEDULED", invite.id); store.flush(); return view(invite);
  }
  @Transactional(readOnly = true)
  public byte[] calendar(CurrentUser user, String id) {
    var invitation = store.one(Invitation.class,
        "from HiringInterviewInvitation where publicId=?1 and candidateId=?2 and issuedAt is not null",
        id, user.databaseId()).orElseThrow(HiringAccess::notFound);
    return HiringCalendar.render(view(invitation), Instant.now());
  }
  public InvitationView decline(CurrentUser user, String id, long expectedVersion) {
    var invite = owned(user, id);
    if (invite.status.equals("DECLINED")) return view(invite);
    version(expectedVersion, invite.version);
    if (!invite.status.equals("ISSUED") && !invite.status.equals("ACCEPTED")) throw conflict("已开始或已结束的邀请不能拒绝");
    invite.status="DECLINED"; invite.scheduleRevision++;
    organizations.audit(user, batch(invite).organizationId, "INVITATION_DECLINED", invite.id); store.flush(); return view(invite);
  }
  public void cancel(CurrentUser user, Long orgId, Long memberId) {
    var member = store.find(BatchMember.class, memberId, false).orElseThrow(HiringAccess::notFound);
    var batch = store.find(Batch.class, member.batchId, false).filter(b -> b.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    access.job(user, orgId, batch.jobId, true);
    store.find(Application.class, member.applicationId, true).orElseThrow();
    var reference = store.one(Invitation.class, "from HiringInterviewInvitation where batchMemberId=?1", memberId).orElseThrow();
    var invite = store.find(Invitation.class, reference.id, true).orElseThrow();
    if (invite.status.equals("CANCELLED")) return;
    if (!List.of("CREATED","ISSUED","ACCEPTED","STARTED").contains(invite.status)) throw conflict("已完成的邀请不能撤销");
    HiringInterviewLifecycle.cancel(store, invite.id);
    invite.status="CANCELLED"; invite.scheduleRevision++;
    if(invite.issuedAt!=null) HiringNotifications.enqueue(store,invite,"CANCELLED",invite.scheduleRevision,Instant.now());
    organizations.audit(user, orgId, "INVITATION_CANCELLED", invite.id);
  }
  @Transactional(readOnly = true)
  public List<Long> expiredCandidates() {
    return store.list(Long.class, "select i.id from HiringInterviewInvitation i, HiringBatchMember m, HiringBatch b "
        + "where i.batchMemberId=m.id and m.batchId=b.id and i.status in ('CREATED','ISSUED','ACCEPTED') "
        + "and b.latestStartAt < ?1 order by i.id", 0, 100, Instant.now());
  }
  public void expire(Long id) {
    var reference = store.find(Invitation.class, id, false).orElseThrow(); var batch = batch(reference);
    store.find(Organization.class, batch.organizationId, true).orElseThrow();
    store.find(Application.class, reference.applicationId, true).orElseThrow();
    var invitation = store.find(Invitation.class, id, true).orElseThrow();
    if (List.of("CREATED","ISSUED","ACCEPTED").contains(invitation.status) && batch.latestStartAt.isBefore(Instant.now())) {
      invitation.status="EXPIRED"; invitation.scheduleRevision++;
    }
  }
  private Invitation owned(CurrentUser user, String id) {
    var ref = store.one(Invitation.class, "from HiringInterviewInvitation where publicId=?1 and candidateId=?2 and issuedAt is not null", id,user.databaseId()).orElseThrow(HiringAccess::notFound);
    var batch = batch(ref);
    store.find(Organization.class, batch.organizationId, true).filter(o -> o.active).orElseThrow(HiringAccess::notFound);
    store.find(Application.class, ref.applicationId, true).orElseThrow();
    return store.find(Invitation.class, ref.id, true).orElseThrow();
  }
  private Batch batch(Invitation invite) { return store.find(Batch.class, store.find(BatchMember.class, invite.batchMemberId, false).orElseThrow().batchId, false).orElseThrow(); }
  private InvitationView view(Invitation invite) {
    var batch = batch(invite); var app = store.find(Application.class, invite.applicationId, false).orElseThrow();
    var revision = store.find(SchemeRevision.class, batch.schemeRevisionId, false).orElseThrow();
    var definition = json.readValue(revision.definition, AssessmentModels.Definition.class);
    var member = store.find(BatchMember.class, invite.batchMemberId, false).orElseThrow();
    String title = store.one(JobRevision.class, "from HiringJobRevision where jobId=?1 and revision=?2", batch.jobId, member.jobRevision).orElseThrow().title;
    return new InvitationView(invite.publicId, store.find(Organization.class, batch.organizationId, false).orElseThrow().name, title,
        invite.roundNo, invite.status, batch.opensAt, batch.latestStartAt, batch.closesAt, definition.durationMinutes(), invite.plannedAt, batch.timezone, invite.scheduleRevision, invite.version);
  }
}
