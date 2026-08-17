package interview.pilot.interview.grounding;

import java.util.List;
import java.util.UUID;

import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

public record GroundingSnapshot(
    GroundingStatus status, String query, String embeddingVersion,
    List<Chunk> chunks, String failureCode) {

  public GroundingSnapshot {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
    query = query == null ? "" : query;
    embeddingVersion = embeddingVersion == null ? "" : embeddingVersion;
    failureCode = failureCode == null || failureCode.isBlank() ? null : failureCode;
  }

  public RagContextSnapshot toRagContext() {
    RagStatus ragStatus = switch (status) {
      case DISABLED -> RagStatus.DISABLED;
      case NOT_REQUESTED -> RagStatus.NOT_REQUESTED;
      case RETRIEVED -> RagStatus.RETRIEVED;
      case NO_MATCH -> RagStatus.NO_MATCH;
      case UNAVAILABLE -> RagStatus.UNAVAILABLE;
    };
    return new RagContextSnapshot(ragStatus, query, embeddingVersion,
        chunks.stream().map(chunk -> new RagContextSnapshot.Chunk(
            chunk.sourceId(), chunk.documentId(), chunk.filename(), chunk.documentRevision(),
            chunk.chunkIndex(), chunk.role(), chunk.section(), chunk.pageNumber(),
            chunk.score(), chunk.content())).toList(), failureCode);
  }

  public record Chunk(
      String sourceId, KnowledgeRole role, UUID documentId, int documentRevision,
      String filename, int chunkIndex, String section, Integer pageNumber,
      double score, String content) {}
}
