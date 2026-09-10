package interview.pilot.recruitment.application;

import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.recruitment.infrastructure.CampaignEntities.*;
import interview.pilot.recruitment.infrastructure.HiringEntities.*;
import interview.pilot.recruitment.infrastructure.HiringStore;
import interview.pilot.recruitment.infrastructure.NotificationEntities.Notification;
import interview.pilot.recruitment.infrastructure.ReviewEntities.Assignment;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Creates durable in-app and email notification work inside the business transaction.
 * Notification rows are addressed to concrete user accounts so candidate and company inboxes
 * cannot infer recipients from the related application alone.
 */
public final class HiringNotifications {
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

  private HiringNotifications() {}

  public static void applicationSubmitted(HiringStore store, Application application) {
    Instant now = Instant.now();
    for (Long recipientId : companyRecipients(store, application.organizationId, application.jobId)) {
      enqueue(store, null, application, recipientId, "APPLICATION_SUBMITTED",
          application.submissionNo, now);
    }
  }

  public static void applicationWithdrawn(HiringStore store, Application application) {
    Instant now = Instant.now();
    for (Long recipientId : companyRecipients(store, application.organizationId, application.jobId)) {
      enqueue(store, null, application, recipientId, "APPLICATION_WITHDRAWN",
          application.submissionNo, now);
    }
  }

  public static void enqueue(
      HiringStore store, Invitation invite, String kind, int revision, Instant due) {
    var application = store.find(Application.class, invite.applicationId, false).orElseThrow();
    enqueue(store, invite, application, invite.candidateId, kind, revision, due);
  }

  public static void scheduled(HiringStore store, Invitation invite, int[] hours) {
    var application = store.find(Application.class, invite.applicationId, false).orElseThrow();
    Instant now = Instant.now();
    enqueue(store, invite, application, invite.candidateId, "SCHEDULED",
        invite.scheduleRevision, now);
    for (Long recipientId : companyRecipients(store, application, invite)) {
      enqueue(store, invite, application, recipientId, "INTERVIEW_SCHEDULED",
          invite.scheduleRevision, now);
    }
    for (int h : Arrays.stream(hours).filter(value -> value > 0 && value <= 720).distinct().toArray()) {
      var due = invite.plannedAt.minusSeconds(h * 3600L);
      if (due.isAfter(now)) {
        enqueue(store, invite, application, invite.candidateId, "REMINDER_" + h,
            invite.scheduleRevision, due);
      }
    }
  }

  public static void interviewDeclined(HiringStore store, Invitation invite) {
    var application = store.find(Application.class, invite.applicationId, false).orElseThrow();
    Instant now = Instant.now();
    for (Long recipientId : companyRecipients(store, application, invite)) {
      enqueue(store, invite, application, recipientId, "INTERVIEW_DECLINED",
          invite.scheduleRevision, now);
    }
  }

  private static void enqueue(
      HiringStore store,
      Invitation invite,
      Application application,
      Long recipientId,
      String kind,
      int revision,
      Instant due) {
    String eventKey = eventKey(invite, application, kind, revision);
    if (store.one(Notification.class,
        "from HiringNotification where recipientId=?1 and eventKey=?2", recipientId, eventKey).isPresent()) {
      return;
    }
    var organization = store.find(Organization.class, application.organizationId, false).orElseThrow();
    var job = store.one(JobRevision.class,
        "from HiringJobRevision where jobId=?1 and revision=?2", application.jobId, application.jobRevision)
        .orElseThrow();
    var candidate = store.find(UserAccountEntity.class, application.candidateId, false).orElseThrow();

    var notification = new Notification();
    notification.invitationId = invite == null ? null : invite.id;
    notification.applicationId = application.id;
    notification.recipientId = recipientId;
    notification.eventKey = eventKey;
    notification.kind = kind;
    notification.scheduleRevision = revision;
    notification.title = title(kind);
    notification.message = message(store, invite, application, organization.name, job.title,
        candidate.getDisplayName(), kind);
    notification.dueAt = due;
    notification.nextAttemptAt = due;
    notification.mailStatus = "PENDING";
    if (!kind.startsWith("REMINDER")) {
      notification.visibleAt = Instant.now();
    }
    store.add(notification);
  }

  private static String eventKey(
      Invitation invite, Application application, String kind, int revision) {
    if (invite != null) {
      return invite.id + ":" + kind + ":" + revision;
    }
    return application.id + ":" + application.submissionNo + ":" + kind + ":" + revision;
  }

  private static String title(String kind) {
    return switch (kind) {
      case "ISSUED" -> "你有一份新的面试邀请";
      case "SCHEDULED" -> "面试日程已更新";
      case "INTERVIEW_SCHEDULED" -> "候选人已确认面试安排";
      case "INTERVIEW_DECLINED" -> "候选人拒绝了面试邀请";
      case "APPLICATION_SUBMITTED" -> "收到新的候选人投递";
      case "APPLICATION_WITHDRAWN" -> "候选人撤回了投递";
      case "CANCELLED" -> "面试已取消";
      case "FEEDBACK" -> "企业已发布面试反馈";
      default -> "面试即将开始";
    };
  }

  private static String message(
      HiringStore store,
      Invitation invite,
      Application application,
      String company,
      String jobTitle,
      String candidate,
      String kind) {
    boolean candidateMessage = kind.equals("ISSUED")
        || kind.equals("SCHEDULED")
        || kind.equals("CANCELLED")
        || kind.equals("FEEDBACK")
        || kind.startsWith("REMINDER");
    if (candidateMessage) {
      String message = company + " · " + jobTitle;
      if (invite != null) {
        message += " · 第 " + invite.roundNo + " 轮。";
        if (!kind.equals("FEEDBACK") && !kind.equals("CANCELLED")) {
          var batch = batch(store, invite);
          var zone = ZoneId.of(batch.timezone);
          message += "允许开始：" + TIME_FORMAT.withZone(zone).format(batch.opensAt)
              + " 至 " + TIME_FORMAT.withZone(zone).format(batch.latestStartAt) + "。";
          if (invite.plannedAt != null) {
            message += "计划开始：" + TIME_FORMAT.withZone(zone).format(invite.plannedAt) + "。";
          }
        }
      }
      return message + "请登录查看最新安排。";
    }
    return switch (kind) {
      case "APPLICATION_SUBMITTED" -> candidate + " 投递了岗位「" + jobTitle + "」，请登录查看简历。";
      case "APPLICATION_WITHDRAWN" -> candidate + " 撤回了岗位「" + jobTitle + "」的投递。";
      case "INTERVIEW_SCHEDULED" -> candidate + " 已确认「" + jobTitle + "」的面试安排。"
          + schedule(store, invite);
      case "INTERVIEW_DECLINED" -> candidate + " 拒绝了「" + jobTitle + "」的面试邀请。";
      default -> company + " · " + jobTitle + " · 第 " + invite.roundNo + " 轮。请登录查看最新安排。";
    };
  }

  private static String schedule(HiringStore store, Invitation invite) {
    var batch = batch(store, invite);
    var zone = ZoneId.of(batch.timezone);
    return invite.plannedAt == null
        ? ""
        : "计划开始：" + TIME_FORMAT.withZone(zone).format(invite.plannedAt) + "。";
  }

  private static Batch batch(HiringStore store, Invitation invite) {
    var member = store.find(BatchMember.class, invite.batchMemberId, false).orElseThrow();
    return store.find(Batch.class, member.batchId, false).orElseThrow();
  }

  private static Set<Long> companyRecipients(
      HiringStore store, Application application, Invitation invite) {
    var recipients = companyRecipients(store, application.organizationId, application.jobId);
    store.list(Assignment.class, "from HiringReviewAssignment where invitationId=?1", 0, 200, invite.id)
        .forEach(assignment -> recipients.add(assignment.reviewerId));
    return recipients;
  }

  private static Set<Long> companyRecipients(HiringStore store, Long organizationId, Long jobId) {
    return new LinkedHashSet<>(store.list(Membership.class,
        "from HiringMembership m where m.organizationId=?1 and m.active=true "
            + "and (m.role='ADMIN' or (m.role='RECRUITER' and exists "
            + "(select a.id from HiringJobAssignment a where a.jobId=?2 and a.userAccountId=m.userAccountId))) "
            + "order by m.id",
        0, 200, organizationId, jobId).stream().map(member -> member.userAccountId).toList());
  }
}
