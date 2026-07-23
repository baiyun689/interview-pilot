package interview.pilot.knowledge.storage;

import java.io.InputStream;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentStore {
  String store(UUID userId, UUID documentId, MultipartFile file);

  InputStream open(String storageKey);

  void delete(String storageKey);
}
