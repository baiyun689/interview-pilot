package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "interview_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewReportEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @UuidGenerator @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "report_id", nullable = false, unique = true, length = 36, updatable = false)
  private UUID reportId;
  @Column(name = "session_id", nullable = false, unique = true, updatable = false)
  private Long sessionId;
  @Column(name = "overall_score", nullable = false, updatable = false)
  private int overallScore;
  @Column(name = "summary_text", nullable = false, updatable = false, columnDefinition = "longtext")
  private String summaryText;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "report_snapshot", nullable = false, updatable = false, columnDefinition = "json")
  private String reportSnapshot;
  @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static InterviewReportEntity create(
      Long sessionId, int overallScore, String summary, String reportSnapshot) {
    var report = new InterviewReportEntity();
    report.sessionId = sessionId;
    report.overallScore = overallScore;
    report.summaryText = summary;
    report.reportSnapshot = reportSnapshot;
    return report;
  }
}
