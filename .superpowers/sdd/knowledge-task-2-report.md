# Knowledge Task 2 Report — Knowledge Base Persistence

## Implementation summary

- Added forward-only `V13__create_knowledge_base.sql`. The task brief's V12 filename was superseded because this branch already contains the applied ownership migration `V12__require_user_ownership.sql`; no Flyway history was rewritten.
- Added tenant-owned `knowledge_base` records and `knowledge_document` records with UUID keys, a per-base content-hash uniqueness constraint, MySQL status checks, optimistic versions, timestamps, storage keys, parsed text, embedding snapshots, chunk counts, failures, and indexing revisions.
- Added owner-scoped repositories only: base lookup requires `(knowledgeBaseId, userAccountId)` and READY-document lookup joins the base and requires the owner id.
- Added document revision fencing. Reindexing increments the revision and clears a prior failure; READY and FAILED completions accept only the active PROCESSING revision. Deletion advances the revision and prevents reindexing.
- No upload, parsing, chunk persistence, Qdrant, controller, or application-service behavior was added.

## TDD evidence

1. Added `KnowledgeSchemaV13MigrationIT` before the migration. Its initial run failed before test execution because Testcontainers could not connect to the local Docker daemon.
2. Added `KnowledgeDocumentEntityTest` and `KnowledgeRepositoryTest` before production types existed. The RED run failed compilation exactly because the new entities, statuses, and repositories did not yet exist.
3. Added the smallest schema and JPA implementation, then reran the focused entity/repository suite to GREEN.

## Verification

Passed:

```text
.\gradlew.bat test --tests interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntityTest --tests interview.pilot.knowledge.infrastructure.KnowledgeRepositoryTest
BUILD SUCCESSFUL
```

Blocked external integration verification:

```text
.\gradlew.bat test --tests interview.pilot.persistence.KnowledgeSchemaV13MigrationIT
```

Testcontainers reported that `dockerDesktopLinuxEngine` does not exist. Starting Docker Desktop and polling for one minute did not make `docker info` available, so the MySQL/Flyway test could not run in this environment. The migration test remains present and will execute against MySQL 8.4 once Docker is available.

`git diff --check` passed before commit.

## Commit

`feat: add knowledge base persistence`

## Residual risks

- The MySQL migration integration test remains unexecuted solely because the local Docker daemon is unavailable; the entity and repository-contract tests are green.
- Deletion deliberately retains documents through the default restrictive foreign key rather than cascading physical deletion. This protects indexed/source records from accidental base deletion; a later deletion workflow must remove external vectors and document rows explicitly after state fencing.
