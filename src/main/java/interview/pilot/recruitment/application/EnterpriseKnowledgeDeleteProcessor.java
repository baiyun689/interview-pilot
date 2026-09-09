package interview.pilot.recruitment.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import interview.pilot.knowledge.infrastructure.KnowledgeChunkRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;
import tools.jackson.databind.ObjectMapper;

@Component
public class EnterpriseKnowledgeDeleteProcessor implements HiringWorkProcessor {
  public record Input(Long databaseId, UUID documentId, int revision, String storageKey) {}
  private final Optional<VectorStore> vectors;
  private final KnowledgeChunkRepository chunks;
  private final KnowledgeDocumentStore files;
  private final ObjectMapper json;
  public EnterpriseKnowledgeDeleteProcessor(Optional<VectorStore> vectors, KnowledgeChunkRepository chunks,
      KnowledgeDocumentStore files, ObjectMapper json) { this.vectors = vectors; this.chunks = chunks; this.files = files; this.json = json; }
  @Override public String kind() { return "KNOWLEDGE_DELETE"; }
  @Override public Object process(String inputSnapshot) {
    var input = json.readValue(inputSnapshot, Input.class);
    vectors.orElseThrow(() -> new IllegalStateException("Vector store unavailable")).delete(
        new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("document_id"), new Filter.Value(input.documentId().toString())));
    chunks.deleteByDocumentId(input.documentId()); files.delete(input.storageKey());
    return Map.of("deleted", true);
  }
}
