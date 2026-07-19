package interview.pilot.interview.rag;

import java.util.List;
import java.util.UUID;

public record RagContextSnapshot(
    RagStatus status, String query, String embeddingVersion, List<Chunk> chunks,
    String failureCode) {

  public RagContextSnapshot {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
    if (chunks.size() > 6) throw new IllegalArgumentException("too many RAG chunks");
    if (status == RagStatus.RETRIEVED && chunks.isEmpty()) {
      throw new IllegalArgumentException("retrieved snapshot requires chunks");
    }
    query = query == null ? "" : query;
    embeddingVersion = embeddingVersion == null ? "" : embeddingVersion;
    failureCode = (failureCode == null || failureCode.isBlank()) ? null : failureCode;
  }

  public static RagContextSnapshot notConfigured() {
    return new RagContextSnapshot(RagStatus.NOT_CONFIGURED, "", "", List.of(), null);
  }

  public record Chunk(
      String pointId, UUID documentId, String filename,
      int chunkIndex, double score, String content) {
    public Chunk {
      if (pointId == null || pointId.isBlank()) throw new IllegalArgumentException("pointId required");
      if (documentId == null) throw new IllegalArgumentException("documentId required");
      if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");
      filename = filename == null ? "" : filename;
    }
  }
}
