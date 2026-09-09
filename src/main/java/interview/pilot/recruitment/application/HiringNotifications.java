package interview.pilot.recruitment.application;

import interview.pilot.recruitment.infrastructure.*;
import interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import interview.pilot.recruitment.infrastructure.HiringEntities.*;
import interview.pilot.recruitment.infrastructure.NotificationEntities.Notification;
import java.time.Instant;

/** Called within the business transaction: notification failure never rolls back an issued invitation. */
public final class HiringNotifications {
  private HiringNotifications() {}
  public static void enqueue(HiringStore store,Invitation invite,String kind,int revision,Instant due) {
    String key=invite.id+":"+kind+":"+revision;
    if(store.one(Notification.class,"from HiringNotification where recipientId=?1 and eventKey=?2",invite.candidateId,key).isPresent()) return;
    var app=store.find(Application.class,invite.applicationId,false).orElseThrow();
    var member=store.find(BatchMember.class,invite.batchMemberId,false).orElseThrow();
    var batch=store.find(Batch.class,member.batchId,false).orElseThrow();
    String company=store.find(Organization.class,app.organizationId,false).orElseThrow().name;
    String title=store.one(JobRevision.class,"from HiringJobRevision where jobId=?1 and revision=?2",app.jobId,member.jobRevision).orElseThrow().title;
    var n=new Notification();n.invitationId=invite.id;n.recipientId=invite.candidateId;n.eventKey=key;n.kind=kind;n.scheduleRevision=invite.scheduleRevision;
    n.title=switch(kind) {case "ISSUED"->"你有一份新的面试邀请";case "SCHEDULED"->"面试日程已更新";case "CANCELLED"->"面试已取消";case "FEEDBACK"->"企业已发布面试反馈";default->"面试即将开始";};
    n.message=company+" · "+title+" · 第 "+invite.roundNo+" 轮。";
    if(!kind.equals("FEEDBACK") && !kind.equals("CANCELLED")) {
      var zone=java.time.ZoneId.of(batch.timezone);
      var format=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(zone);
      n.message+="允许开始："+format.format(batch.opensAt)+" 至 "+format.format(batch.latestStartAt)+"。";
      if(invite.plannedAt!=null) n.message+="计划开始："+format.format(invite.plannedAt)+"。";
    }
    n.message+="请登录查看最新安排。";n.dueAt=due;n.nextAttemptAt=due;n.mailStatus="PENDING";
    if(!kind.startsWith("REMINDER")) n.visibleAt=Instant.now();
    store.add(n);
  }
  public static void scheduled(HiringStore store,Invitation invite,int[] hours) {
    enqueue(store,invite,"SCHEDULED",invite.scheduleRevision,Instant.now());
    for(int h:java.util.Arrays.stream(hours).filter(h->h>0 && h<=720).distinct().toArray()) {
      var due=invite.plannedAt.minusSeconds(h*3600L);
      if(due.isAfter(Instant.now())) enqueue(store,invite,"REMINDER_"+h,invite.scheduleRevision,due);
    }
  }
}
