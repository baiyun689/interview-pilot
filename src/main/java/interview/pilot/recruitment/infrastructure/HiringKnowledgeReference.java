package interview.pilot.recruitment.infrastructure;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hiring_knowledge_reference")
public class HiringKnowledgeReference {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "document_id", length = 36) public UUID documentId;
  @Column(name = "index_revision") public int indexRevision;
  @Column(name = "reference_key", length = 160) public String referenceKey;
  @Column(name = "expires_at") public Instant expiresAt;
}
