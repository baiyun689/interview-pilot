package interview.pilot.interview.rag;

import java.util.List;
import java.util.UUID;
import java.util.Collection;

import interview.pilot.interview.grounding.KnowledgeRole;
import interview.pilot.interview.domain.GroundingMode;

public record RagContextSnapshot(
    RagStatus status, String query, String embeddingVersion, List<Chunk> chunks,
    String failureCode, GroundingMode groundingMode, List<String> evidenceRefs) {

  public RagContextSnapshot {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
    if (chunks.size() > 6) throw new IllegalArgumentException("too many RAG chunks");
    if (status == RagStatus.RETRIEVED && chunks.isEmpty()) {
      throw new IllegalArgumentException("retrieved snapshot requires chunks");
    }
    query = query == null ? "" : query;
    embeddingVersion = embeddingVersion == null ? "" : embeddingVersion;
    failureCode = (failureCode == null || failureCode.isBlank()) ? null : failureCode;
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    groundingMode = groundingMode == null ? GroundingMode.GENERAL : groundingMode;
  }

  public RagContextSnapshot(
      RagStatus status, String query, String embeddingVersion, List<Chunk> chunks,
      String failureCode) {
    this(status, query, embeddingVersion, chunks, failureCode,
        GroundingMode.GENERAL, List.of());
  }

  public static RagContextSnapshot notConfigured() {
    return new RagContextSnapshot(RagStatus.NOT_CONFIGURED, "", "", List.of(), null);
  }

  public record Chunk(
      String pointId, UUID documentId, String filename,
      int documentRevision, int chunkIndex, KnowledgeRole role,
      String section, Integer pageNumber, double score, String content) {
    public Chunk {
      if (pointId == null || pointId.isBlank()) throw new IllegalArgumentException("pointId required");
      if (documentId == null) throw new IllegalArgumentException("documentId required");
      if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");
      filename = filename == null ? "" : filename;
      role = role == null ? KnowledgeRole.TECHNICAL_REFERENCE : role;
      section = section == null ? "" : section;
    }

    public Chunk(
        String pointId, UUID documentId, String filename,
        int chunkIndex, double score, String content) {
      this(pointId, documentId, filename, 1, chunkIndex,
          KnowledgeRole.TECHNICAL_REFERENCE, "", null, score, content);
    }
  }

  public void requireCurrentSources(Collection<String> sourceIds) {
    java.util.Set<String> allowed = chunks.stream()
        .map(Chunk::pointId).collect(java.util.stream.Collectors.toSet());
    if (!allowed.containsAll(sourceIds)) {
      throw new IllegalArgumentException("References must belong to current grounding snapshot");
    }
  }
}
