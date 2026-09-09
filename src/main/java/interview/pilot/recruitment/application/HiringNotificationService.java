package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.infrastructure.NotificationEntities.*;
import static interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.recruitment.infrastructure.HiringStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class HiringNotificationService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;
  public HiringNotificationService(HiringStore store,HiringAccess access,OrganizationService organizations) {this.store=store;this.access=access;this.organizations=organizations;}
  public record View(Long id,String title,String message,String invitationId,Instant visibleAt,Instant readAt,String mailStatus,String error,int attempts,long version) {}
  public record Delivery(Long id,String token,String email,String title,String message) {}
  @Transactional(readOnly=true)
  public HiringModels.Page<View> mine(CurrentUser user,int page) {
    checkPage(page);
    var rows=store.list(Notification.class,"from HiringNotification where recipientId=?1 and visibleAt is not null order by visibleAt desc,id desc",page*25,26,user.databaseId());
    return new HiringModels.Page<>(rows.stream().limit(25).map(this::view).toList(),page,rows.size()>25);
  }
  public void read(CurrentUser user,Long id) {
    var n=store.find(Notification.class,id,true).filter(r->r.recipientId.equals(user.databaseId()) && r.visibleAt!=null).orElseThrow(HiringAccess::notFound);
    if(n.readAt==null) n.readAt=Instant.now();
  }
  @Transactional(readOnly=true)
  public HiringModels.Page<View> company(CurrentUser user,Long orgId,int page) {
    checkPage(page);var member=access.member(user,orgId,false);if(member.role.equals("INTERVIEWER")) throw forbidden();
    String permission=member.role.equals("ADMIN")?"":" and exists (select j.id from HiringJobAssignment j where j.jobId=a.jobId and j.userAccountId=?2)";
    Object[] params=member.role.equals("ADMIN")?new Object[]{orgId}:new Object[]{orgId,user.databaseId()};
    var rows=store.list(Notification.class,"select n from HiringNotification n, HiringInterviewInvitation i, HiringApplication a where n.invitationId=i.id and i.applicationId=a.id and a.organizationId=?1"+permission+" order by n.id desc",page*25,26,params);
    return new HiringModels.Page<>(rows.stream().limit(25).map(this::view).toList(),page,rows.size()>25);
  }
  public void retry(CurrentUser user,Long orgId,Long id,long expectedVersion) {
    var ref=store.find(Notification.class,id,false).orElseThrow(HiringAccess::notFound);
    var invite=store.find(Invitation.class,ref.invitationId,false).orElseThrow();
    var app=store.find(Application.class,invite.applicationId,false).filter(a->a.organizationId.equals(orgId)).orElseThrow(HiringAccess::notFound);
    access.job(user,orgId,app.jobId,true);
    var n=store.find(Notification.class,id,true).orElseThrow();version(expectedVersion,n.version);
    if(!List.of("FAILED","UNKNOWN","DISABLED").contains(n.mailStatus)) throw conflict("当前通知不能重试");
    if(!eligible(n,Instant.now())) throw conflict("通知对应的安排已失效");
    n.mailStatus="PENDING";n.nextAttemptAt=Instant.now();n.error=null;
    organizations.audit(user,orgId,"NOTIFICATION_RETRY",id);
  }
  @Transactional(readOnly=true)
  public List<Long> due() {
    return store.list(Long.class,"select id from HiringNotification where (mailStatus='PENDING' and nextAttemptAt<=?1) or (mailStatus='SENDING' and leaseUntil<=?1) order by nextAttemptAt,id",0,50,Instant.now());
  }
  public Delivery claim(Long id,boolean enabled) {
    var n=store.find(Notification.class,id,true).orElseThrow();var now=Instant.now();
    // A crashed SMTP sender may already have delivered; never automatically resend an expired lease.
    if(n.mailStatus.equals("SENDING") && !n.leaseUntil.isAfter(now)) {finishAttempt(n,"UNKNOWN",now);n.mailStatus="UNKNOWN";n.error="发送进程中断，服务商接收情况未知";n.leaseToken=null;n.leaseUntil=null;return null;}
    if(!n.mailStatus.equals("PENDING") || n.nextAttemptAt.isAfter(now)) return null;
    if(!eligible(n,now)) {n.mailStatus="SKIPPED";n.error="安排已变更、结束或提醒已过期";return null;}
    if(n.visibleAt==null) n.visibleAt=now;
    if(!enabled) {n.mailStatus="DISABLED";n.error="邮件通道未启用，站内通知已保留";return null;}
    n.mailStatus="SENDING";n.leaseToken=UUID.randomUUID().toString();n.leaseUntil=now.plusSeconds(120);n.attempts++;
    var a=new Attempt();a.notificationId=n.id;a.attemptNo=n.attempts;a.status="SENDING";a.startedAt=now;store.add(a);
    return new Delivery(n.id,n.leaseToken,store.find(UserAccountEntity.class,n.recipientId,false).orElseThrow().getEmail(),n.title,n.message);
  }
  public void finish(Delivery delivery,String result,String safeError) {
    var n=store.find(Notification.class,delivery.id(),true).orElseThrow();
    if(!n.mailStatus.equals("SENDING") || !Objects.equals(n.leaseToken,delivery.token())) return;
    finishAttempt(n,result,Instant.now());n.mailStatus=result;n.error=safeError;n.leaseToken=null;n.leaseUntil=null;
    if(result.equals("FAILED") && n.attempts<3) {n.mailStatus="PENDING";n.nextAttemptAt=Instant.now().plusSeconds(30L*(1L<<n.attempts));}
  }
  private void finishAttempt(Notification n,String status,Instant now) {
    store.one(Attempt.class,"from HiringDeliveryAttempt where notificationId=?1 and attemptNo=?2",n.id,n.attempts).ifPresent(a->{a.status=status;a.finishedAt=now;});
  }
  private boolean eligible(Notification n,Instant now) {
    var i=store.find(Invitation.class,n.invitationId,false).orElseThrow();var app=store.find(Application.class,i.applicationId,false).orElseThrow();
    if(!store.find(Organization.class,app.organizationId,false).orElseThrow().active) return false;
    if(n.kind.equals("FEEDBACK")) return true;
    if(n.kind.equals("CANCELLED")) return i.status.equals("CANCELLED");
    if(!List.of("ISSUED","ACCEPTED").contains(i.status)) return false;
    var batch=store.find(Batch.class,store.find(BatchMember.class,i.batchMemberId,false).orElseThrow().batchId,false).orElseThrow();
    if(!batch.latestStartAt.isAfter(now)) return false;
    if(n.kind.startsWith("REMINDER")) return i.status.equals("ACCEPTED") && i.scheduleRevision==n.scheduleRevision && i.plannedAt!=null && i.plannedAt.isAfter(now)
        && now.isBefore(n.dueAt.plusSeconds(300));
    return i.scheduleRevision==n.scheduleRevision;
  }
  private View view(Notification n) {return new View(n.id,n.title,n.message,store.find(Invitation.class,n.invitationId,false).orElseThrow().publicId,n.visibleAt,n.readAt,n.mailStatus,n.error,n.attempts,n.version);}
  private void checkPage(int page) {if(page<0 || page>10000) throw conflict("无效页码");}
}
