package interview.pilot.knowledge.api;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBaseResponse(
    UUID knowledgeBaseId, String name, String status, int readyDocumentCount, Instant createdAt) {}
