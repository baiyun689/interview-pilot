# Knowledge Task 1 Report

## Scope

Implemented only the Qdrant, DashScope embedding, knowledge-file volume, and
configuration-validation foundation. No knowledge persistence, ingestion API, or
retrieval API was added.

## TDD

1. Added `knowledgeInfrastructureDeclaresQdrantFileVolumeAndEmbeddingConfiguration`
   to `InfrastructureConfigurationTest` before infrastructure changes.
2. Ran `./gradlew.bat test --tests interview.pilot.InfrastructureConfigurationTest`.
   It failed at the new assertion because the Qdrant and knowledge configuration did
   not yet exist.
3. Added the minimal Compose, environment, application, dependency, and Java
   configuration changes. The test passed after implementation.
4. The application-context verification then exposed the Qdrant starter's automatic
   `VectorStore` configuration, which requires an embedding model even while
   `app.knowledge.enabled=false`. The application now excludes that automatic
   configuration and creates the explicitly named knowledge beans only when enabled.

## Implementation

- Added `qdrant/qdrant:v1.15.4`, persistent `qdrant_data`, and app-mounted
  `knowledge_files` volumes. Qdrant stays internal to Compose through `expose`.
- Added environment-driven Qdrant and DashScope embedding settings; no API key is
  committed.
- Added `KnowledgeProperties` validation for the file root, chunking, batch,
  collection, retrieval, Qdrant, and fixed 1024-dimension embedding settings.
- Added an enabled-only `knowledgeEmbeddingModel`, `knowledgeQdrantClient`, and
  `knowledgeVectorStore`. The vector store uses `knowledge_chunks_v1` and
  `initializeSchema(true)`.
- Added the required Qdrant Spring AI starter plus the explicit gRPC transport
  dependency required by Qdrant Java client 1.13.0 at compile time.

## Verification

- `./gradlew.bat test --tests interview.pilot.InfrastructureConfigurationTest --tests interview.pilot.InterviewPilotApplicationTest` — PASS.
- `docker compose config` — PASS.
- `git diff --check` — PASS.

## Review Follow-up

- `KnowledgeProperties` now rejects any collection name other than
  `knowledge_chunks_v1`, any embedding model other than `text-embedding-v3`, and
  any dimension other than `1024` during Spring property binding. This prevents
  environment variables from silently producing incompatible vectors.
- Removed the ineffective `KNOWLEDGE_COLLECTION_NAME` environment/example
  setting. The application configuration remains the single fixed collection
  declaration.
- Added `KnowledgeConfigurationTest` with `ApplicationContextRunner`. It proves
  invalid fixed values stop context creation, disabled/no-key configuration
  exposes none of the three knowledge infrastructure beans, and enabled
  configuration wires the named embedding/client/vector-store beans to a mocked
  Qdrant client without a network connection.

## Commit

Committed as `3aa1342 build: add qdrant and embedding configuration`.

## Risks

- Enabling `KNOWLEDGE_ENABLED` requires a reachable Qdrant gRPC endpoint and a
  non-empty `DASHSCOPE_EMBEDDING_API_KEY`; this is deliberately validated at startup.
- `initializeSchema=true` creates the configured collection at startup. This is
  required by the task but should remain an intentional operational setting.
