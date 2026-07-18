package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "interview_report")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewReportEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "report_id", nullable = false, unique = true, length = 36)
  private UUID reportId;

  @Column(name = "session_id", nullable = false, unique = true)
  private Long sessionId;

  @Column(name = "summary_text", nullable = false, columnDefinition = "longtext")
  private String summaryText;

  @Column(name = "feedback_text", nullable = false, columnDefinition = "longtext")
  private String feedbackText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "score_snapshot", columnDefinition = "json")
  private String scoreSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "report_snapshot", columnDefinition = "json")
  private String reportSnapshot;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static InterviewReportEntity create(
      Long sessionId, int overallScore, String summary, String reportSnapshot) {
    var report = new InterviewReportEntity();
    report.sessionId = sessionId;
    report.summaryText = summary;
    report.feedbackText = summary;
    report.scoreSnapshot = "{\"overallScore\":" + overallScore + "}";
    report.reportSnapshot = reportSnapshot;
    return report;
  }
}
