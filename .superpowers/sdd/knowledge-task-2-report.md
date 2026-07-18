# Knowledge Task 2 Report — Knowledge Base Persistence

## Implementation summary

- Added forward-only `V13__create_knowledge_base.sql`. The task brief's V12 filename was superseded because this branch already contains the applied ownership migration `V12__require_user_ownership.sql`; no Flyway history was rewritten.
- Added tenant-owned `knowledge_base` records and `knowledge_document` records with UUID keys, a per-base content-hash uniqueness constraint, MySQL status checks, optimistic versions, timestamps, storage keys, parsed text, embedding snapshots, chunk counts, failures, and indexing revisions.
- Replaced public Spring Data repositories with narrow owner-scoped ports. Only package-private JPA delegates extend `JpaRepository`; package-private adapters are the Spring beans implementing the public ports, which expose only `save` and owner-scoped reads. This prevents later application code from calling unscoped CRUD APIs.
- Added document revision fencing. Reindexing increments the revision and clears a prior failure; READY and FAILED completions accept only the active PROCESSING revision. Deletion advances the revision and prevents reindexing, including stale READY and FAILED completions.
- No upload, parsing, chunk persistence, Qdrant, controller, or application-service behavior was added.

## TDD evidence

1. Added `KnowledgeSchemaV13MigrationIT` before the migration. Its initial run failed before test execution because Testcontainers could not connect to the local Docker daemon.
2. Added `KnowledgeDocumentEntityTest` and `KnowledgeRepositoryTest` before production types existed. The RED run failed compilation exactly because the new entities, statuses, and repositories did not yet exist.
3. Review remediation added failing public-port reflection and adapter-delegation tests before the safe delegates/adapters existed. The RED compile failed for those missing types; after adding the narrow ports and adapters, the focused suite was GREEN.
4. Added coverage for failure clearing, stale failure rejection, deletion-fenced completions, and actual MySQL check/FK rejection. The container test is complete but cannot initialize until Docker is available.

## Verification

Passed:

```text
.\gradlew.bat test --tests interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntityTest --tests interview.pilot.knowledge.infrastructure.KnowledgeRepositoryTest
BUILD SUCCESSFUL

.\gradlew.bat compileTestJava
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

Review remediation commit: `fix: scope knowledge repository ports`

## Residual risks

- The MySQL migration integration test remains unexecuted solely because the local Docker daemon is unavailable; the entity and repository-contract tests are green.
- Deletion deliberately retains documents through the default restrictive foreign key rather than cascading physical deletion. This protects indexed/source records from accidental base deletion; a later deletion workflow must remove external vectors and document rows explicitly after state fencing.
