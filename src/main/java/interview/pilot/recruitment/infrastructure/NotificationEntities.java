package interview.pilot.recruitment.infrastructure;

import jakarta.persistence.*;
import java.time.Instant;

public final class NotificationEntities {
  private NotificationEntities() {}
  @Entity(name="HiringNotification") @Table(name="hiring_notification")
  public static class Notification {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="invitation_id") public Long invitationId;
    @Column(name="application_id", nullable=false) public Long applicationId;
    @Column(name="recipient_id") public Long recipientId;
    @Column(name="event_key") public String eventKey;
    public String kind;
    @Column(name="schedule_revision") public int scheduleRevision;
    public String title;
    @Column(columnDefinition="text") public String message;
    @Column(name="due_at") public Instant dueAt;
    @Column(name="visible_at") public Instant visibleAt;
    @Column(name="read_at") public Instant readAt;
    @Column(name="mail_status") public String mailStatus;
    public int attempts;
    @Column(name="next_attempt_at") public Instant nextAttemptAt;
    @Column(name="lease_token") public String leaseToken;
    @Column(name="lease_until") public Instant leaseUntil;
    public String error;
    @Version public long version;
  }
  @Entity(name="HiringDeliveryAttempt") @Table(name="hiring_delivery_attempt")
  public static class Attempt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="notification_id") public Long notificationId;
    @Column(name="attempt_no") public int attemptNo;
    public String status;
    @Column(name="started_at") public Instant startedAt;
    @Column(name="finished_at") public Instant finishedAt;
  }
}
