package interview.pilot.recruitment.infrastructure;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class ReviewEntities {
  private ReviewEntities() {}
  @Entity(name="HiringReviewAssignment") @Table(name="hiring_review_assignment")
  public static class Assignment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="invitation_id") public Long invitationId;
    @Column(name="reviewer_id") public Long reviewerId;
  }
  @Entity(name="HiringReview") @Table(name="hiring_review")
  public static class Review {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="invitation_id") public Long invitationId;
    @Column(name="reviewer_id") public Long reviewerId;
    public String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="json") public String content;
    public int revision;
    @Version public long version;
  }
  @Entity(name="HiringReviewRevision") @Table(name="hiring_review_revision")
  public static class Revision {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="review_id") public Long reviewId;
    public int revision;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="json") public String content;
    @Column(name="submitted_at") public Instant submittedAt;
  }
  @Entity(name="HiringFeedback") @Table(name="hiring_feedback")
  public static class Feedback {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="invitation_id") public Long invitationId;
    public int revision;
    @Column(name="review_revision_id") public Long reviewRevisionId;
    public String decision;
    @Column(columnDefinition="text") public String feedback;
    @Column(name="published_by") public Long publishedBy;
    @Column(name="published_at") public Instant publishedAt;
  }
}
