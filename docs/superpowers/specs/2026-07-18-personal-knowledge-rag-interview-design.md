# Personal Knowledge RAG for AI Interviews

Date: 2026-07-18

## 1. Summary

InterviewPilot will add private, file-backed knowledge bases and use their content as hidden
grounding material during AI mock interviews.

A signed-in user can create a knowledge base, upload Markdown, TXT, PDF, or DOCX documents,
wait for asynchronous indexing, and select one or more ready knowledge bases when creating an
interview. Before the first question and each subsequent question, the application retrieves
relevant chunks from the selected knowledge bases. The question generator uses those chunks as
untrusted reference material. When the user answers, the evaluator reuses the exact chunk snapshot
saved with that question.

RAG augments question generation and answer evaluation. It does not control interview state.
The existing Java decision policy remains responsible for follow-up limits, competency coverage,
difficulty bounds, turn budgets, and completion.

## 2. Goals

- Add account login and strict ownership checks for user data.
- Let each user create private knowledge bases and upload supported documents.
- Parse, split, embed, and index documents asynchronously.
- Keep MySQL as the business source of truth and add Qdrant for vector search.
- Let users optionally select private knowledge bases while creating an interview.
- Ground first questions, follow-ups, and topic changes in retrieved personal knowledge.
- Reuse a question's saved retrieval snapshot during answer evaluation.
- Preserve the current interview behavior when no knowledge base is selected.
- Keep an active interview usable when retrieval or Qdrant is temporarily unavailable.

## 3. Non-goals

- Free-form RAG chat.
- A standalone knowledge-base quiz or practice mode.
- Online note editing.
- OCR for scanned PDFs.
- Image, archive, legacy DOC, or web-page ingestion.
- Query rewriting, dedicated reranking models, or LLM-based reranking.
- Token-level model streaming.
- OAuth, email verification, password recovery, organizations, RBAC, billing, or administration.
- RAG retrieval during report generation.
- Replacing the Java interview decision policy with model or retrieval decisions.

## 4. Architecture Decision

The application will retain MySQL and add Qdrant.

| Store | Responsibility |
|---|---|
| MySQL | Users, ownership, source documents, indexing state, interview scope, turns, reports, and durable async tasks |
| Qdrant | Chunk content, embeddings, similarity search, and searchable metadata |
| Redis | Login sessions, rate limiting, concurrency leases, and short-lived processing claims |
| RabbitMQ | Reliable document indexing and vector cleanup delivery |
| Docker volume | Private original document files |

Migrating the application to PostgreSQL and pgvector was rejected because it would put every
existing Flyway migration, persistence integration test, and stable interview workflow at risk for
an unrelated storage change. Adding PostgreSQL only for vectors was rejected because it would
introduce a second relational database without eliminating cross-store consistency.

Qdrant runs as another Docker Compose service. A single collection named `knowledge_chunks_v1`
uses cosine similarity and 1024-dimensional embeddings. It is shared by users and partitioned
logically with mandatory payload filters.

## 5. Authentication and Ownership

### 5.1 Authentication

Authentication uses Spring Security, server-side sessions stored in Redis, and an HttpOnly cookie.
The React frontend and Spring API remain behind the same Nginx origin.

Endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`

Email is normalized to lower case and is the login name. Passwords are stored only as adaptive
password hashes. The session cookie uses `HttpOnly` and `SameSite=Lax`; public HTTPS deployments
also use `Secure`. CSRF protection remains enabled for state-changing requests.

Redis contains no durable user facts. Redis loss invalidates sessions and requires users to log in
again, but it does not lose accounts or business data.

### 5.2 User table

`user_account` contains:

- internal `BIGINT` primary key;
- external UUID `user_id`;
- normalized unique `email`;
- `password_hash`;
- `display_name`;
- `status` with `ACTIVE` and `DISABLED`;
- timestamps and optimistic-lock version.

The migration creates a `legacy-demo` account and assigns existing rows to it before making user
foreign keys non-null.

### 5.3 Owned resources

The following aggregate roots receive a non-null `user_account_id BIGINT` foreign key:

- `resume`;
- `job_profile`;
- `interview_session`;
- `async_task`;
- `knowledge_base`.

Interview turns and reports inherit ownership through `interview_session`. Knowledge documents
inherit ownership through `knowledge_base`, but repository operations still join through the owner
when resolving external identifiers.

Repositories and application interfaces query by both resource identifier and current user.
Controllers do not load a resource globally and perform an ownership check afterward.

## 6. Knowledge Base Domain

### 6.1 Tables

`knowledge_base` contains:

- internal primary key and external UUID `knowledge_base_id`;
- internal `user_account_id` foreign key;
- `name` and optional `description`;
- `ACTIVE`, `ARCHIVED`, or `DELETING` lifecycle status;
- timestamps and optimistic-lock version.

`knowledge_document` contains:

- internal primary key and external UUID `document_id`;
- `knowledge_base_id`;
- original filename, detected content type, size, and SHA-256 content hash;
- private `storage_key`;
- extracted `parsed_text`;
- `PROCESSING`, `READY`, `FAILED`, `ARCHIVED`, or `DELETING` status;
- monotonic `index_revision`;
- embedding provider, model, dimensions, and collection version;
- chunk count and safe failure reason;
- timestamps and optimistic-lock version.

The content hash is unique within `(knowledge_base_id, content_hash)`. The same content can be
uploaded by different users or to distinct knowledge bases.

### 6.2 File storage

Original files are stored behind a `KnowledgeDocumentStore` module with this interface:

- store an uploaded file and return an opaque storage key;
- open a stored file;
- delete a stored file.

The first adapter writes to a persistent Docker volume under generated user and document UUIDs.
User filenames never become filesystem paths. Files have no public URL; every download goes through
an authenticated ownership check.

Supported formats are Markdown, TXT, PDF, and DOCX. Validation uses extension, declared MIME type,
and detected file type. Parsing reuses the hardened Tika configuration already used for resumes,
including input-size and extracted-character limits and disabled embedded-document extraction.
Scanned PDFs without extractable text fail with a clear unsupported-content message.

### 6.3 Indexing pipeline

Uploading a document:

1. Authenticates the user and resolves the owned knowledge base.
2. Validates and stores the original file.
3. Creates a `PROCESSING` document and durable `KNOWLEDGE_DOCUMENT_INDEX` async task in MySQL.
4. Commits the transaction.
5. Publishes the task through the existing confirmed RabbitMQ publisher.
6. A worker claims the task, rechecks the document and revision, opens and parses the file.
7. The worker splits the normalized text, calls the embedding provider in bounded batches, and
   upserts deterministic Qdrant point IDs.
8. A short transaction fences on the document revision and marks the document `READY`.

The existing task dispatcher, retry queues, dead-letter handling, claims, attempt counters, and
manual retry mechanism are extended rather than replaced.

New task types:

- `KNOWLEDGE_DOCUMENT_INDEX`;
- `KNOWLEDGE_DOCUMENT_DELETE`.

Point IDs are deterministically derived from document UUID, index revision, and chunk index.
RabbitMQ redelivery therefore overwrites the same points instead of creating duplicates. A stale
worker cannot mark a newer revision ready. Reindexing writes a new revision before switching the
document's active revision. Old revision points remain available while an active interview scope
references them and are garbage-collected afterward.

### 6.4 Splitting and embeddings

The `RecursiveTextSplitter` implementation and tests from InterviewGuide are adapted. It preserves
useful Markdown heading and sentence boundaries and falls back through:

1. Markdown headings;
2. blank lines;
3. line boundaries;
4. Chinese sentence punctuation;
5. English sentence punctuation;
6. spaces;
7. fixed-size splitting.

Initial configuration uses a maximum of 1600 characters and 200 characters of overlap. These
values are configuration, not public interface guarantees, and must be calibrated with a retrieval
evaluation set.

Embedding is configured independently from the selected chat provider. The first collection uses
DashScope `text-embedding-v3`, 1024 dimensions, and a versioned collection name. DeepSeek or Kimi
may still generate interview content while DashScope supplies embeddings.

Changing embedding dimensions or semantic model creates a new versioned collection and requires
reindexing. Incompatible embeddings are never mixed in one collection.

Every Qdrant point includes:

- external UUID `user_id`, not the internal MySQL foreign key;
- `knowledge_base_id`;
- `document_id`;
- `index_revision`;
- `chunk_index`;
- source filename;
- embedding version;
- chunk text.

## 7. Secure Retrieval Module

Callers do not access Qdrant or construct metadata filters. They use:

```java
RetrievedKnowledge retrieve(
    ValidatedKnowledgeScope scope,
    RetrievalIntent intent
);
```

`KnowledgeScopeResolver` has two secure entry paths. During interview creation it accepts the
authenticated user and requested knowledge-base identifiers, verifies ownership, ready documents,
and compatible active revisions, then produces a `ValidatedKnowledgeScope`. During an active
interview it rebuilds the validated scope from that session's immutable owned document-revision
snapshot, allowing a referenced revision to remain usable after the source is archived or
reindexed. A raw list of client-supplied IDs cannot be passed to the retriever.

`RetrievalIntent` describes:

- interview question generation;
- target competencies or probe focus;
- current difficulty;
- relevant resume and job keywords;
- recently covered topics;
- top-K and threshold policy.

The retriever:

1. Builds a bounded query from trusted interview state.
2. Embeds the query.
3. searches Qdrant with mandatory `user_id`, selected `knowledge_base_id`, `document_id`, and
   `index_revision` filters;
4. removes duplicate or near-duplicate content;
5. enforces per-chunk and total context limits;
6. returns typed chunks, scores, source metadata, latency, and retrieval status.

If filter construction or filtered search fails, retrieval fails closed. It never performs an
unfiltered vector search followed by Java-side filtering.

Initial retrieval uses vector Top 12 and returns at most six diverse chunks. The similarity
threshold is configuration and starts at 0.35 until calibrated. A private `KnowledgeRanker` seam
allows later reranking, but the first adapter only uses vector score, diversity, and duplicate
removal.

## 8. Interview Integration

### 8.1 Interview creation

`CreateInterviewRequest` receives an optional list of external knowledge-base UUIDs. An empty or
missing list preserves the current behavior.

When knowledge bases are selected, creation:

1. resolves the user-owned resume and knowledge bases;
2. snapshots selected knowledge bases, ready documents, active index revisions, and embedding
   version;
3. creates the existing job profile and interview plan;
4. builds a first-question retrieval intent from plan competencies, difficulty, relevant resume
   skills, and job requirements;
5. retrieves personal knowledge;
6. generates the first question with the retrieval result;
7. persists the session scope and first turn with its retrieval snapshot.

The interview session has a join table to selected knowledge bases and a JSON scope snapshot that
records document UUIDs and index revisions. The snapshot makes the exact allowed retrieval scope
auditable and stable.

### 8.2 First and subsequent questions

For the first question, the query covers the plan competencies because the target competency is
chosen by the question generator.

For subsequent questions, retrieval happens only after the model suggestion has passed through the
existing Java decision policy:

- `FOLLOW_UP` uses the validated current competency, `probeFocus`, and previous question.
- `NEXT_TOPIC` uses the validated target competency, adjusted difficulty, and covered-topic
  summary.
- `FINISH` performs no retrieval.

The user's raw answer is not copied into the retrieval query. This prevents answer content from
controlling retrieval and reduces prompt-injection exposure.

Each `interview_turn` stores:

- nullable `rag_context_snapshot`;
- `rag_status` with `NOT_CONFIGURED`, `RETRIEVED`, `NO_MATCH`, or `UNAVAILABLE`.

The snapshot contains query, embedding version, point IDs, document IDs, filenames, chunk indexes,
scores, and bounded chunk text. It is hidden from the interview UI.

### 8.3 Answer evaluation

The evaluator receives the exact retrieval snapshot stored with the current question. It does not
perform another vector search.

Evaluation evidence has three layers:

1. the Skill rubric defines scoring principles and ability anchors;
2. the question defines what the candidate was asked;
3. the saved RAG chunks provide relevant technical facts.

RAG is not the sole truth source. A technically reasonable answer is not marked wrong merely
because a personal note omitted it. RAG content cannot override score validation, decision
constraints, or Java policy.

The report generator continues to use stored turn evaluations and evidence. It does not retrieve
from the knowledge base.

### 8.4 Prompt security

Question-generation prompts state that retrieved text is untrusted reference material, not
instructions. The model may use it to ground a question but must not reveal a reference answer.

Evaluation prompts state that the saved chunks may help verify technical facts but are incomplete
and non-authoritative. The evaluator must ignore role changes, tool requests, scoring commands, or
other instructions present in documents.

Retrieved content is placed in a clearly delimited untrusted-data section and is subject to a hard
total-character limit.

## 9. Frontend

The minimum UI includes:

- registration, login, logout, and current-user state;
- knowledge-base list, create, rename, archive/delete;
- document upload and document status;
- failed-document reindex action;
- a multiselect of the current user's knowledge bases on the interview creation page;
- selected knowledge-base names on interview details.

Only knowledge bases with ready documents can be selected. The live interview does not show
retrieved chunks, reference answers, or retrieval scores.

No free-chat or standalone practice navigation is added.

Knowledge management endpoints are:

- `POST /api/knowledge-bases`;
- `GET /api/knowledge-bases`;
- `PATCH /api/knowledge-bases/{knowledgeBaseId}`;
- `DELETE /api/knowledge-bases/{knowledgeBaseId}`;
- `POST /api/knowledge-bases/{knowledgeBaseId}/documents`;
- `GET /api/knowledge-bases/{knowledgeBaseId}/documents`;
- `GET /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}`;
- `POST /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/reindex`;
- `DELETE /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}`.

## 10. Failure and Consistency Semantics

- File-storage failure creates no document row.
- A committed indexing task that is not published is republished by the durable task scanner.
- Parse failure marks the document `FAILED` and retains the source file for retry.
- Temporary embedding or Qdrant failures follow existing retry and dead-letter policy.
- Partial upserts are safe because point IDs are deterministic.
- Retrieval failure during interview creation or an active interview records `UNAVAILABLE` and
  uses the existing Skill, resume, JD, and history path.
- No match records `NO_MATCH` and uses the same fallback.
- Evaluation never depends on Qdrant availability because it uses the saved turn snapshot.
- Database transactions do not contain file parsing, embedding, vector search, or chat-model calls.
- Document deletion first makes the document unavailable for new sessions. Vectors referenced by
  an active interview scope are retained until no active session needs that revision, then removed
  asynchronously.
- Vector cleanup failure cannot expose a document because every retrieval also requires either a
  current active-document scope or an immutable active-session scope, plus the exact revision
  filter.

Embedding work has a separate concurrency admission pool from live chat-model calls so bulk
indexing cannot consume the real-time interview budget.

## 11. Observability

Metrics and structured logs include:

- document parse, split, embedding, and index duration;
- indexed chunk count and task retry outcome;
- retrieval duration, result count, best score, and `rag_status`;
- RAG usage rate per generated question;
- fallback count by `NO_MATCH` and `UNAVAILABLE`;
- embedding and Qdrant error counts;
- prompt context character count.

Logs include user and resource opaque identifiers where useful but never log password hashes,
session cookies, full source documents, complete retrieved chunks, resumes, or raw answers.

## 12. Verification

### 12.1 Authentication and ownership

- User A cannot list, download, modify, select, index, or retrieve User B's knowledge.
- User A cannot create an interview from User B's resume.
- Same-content documents are allowed for different users.
- Every external resource lookup includes user scope.

### 12.2 Ingestion

- Markdown, TXT, PDF, and DOCX parsing is covered.
- Unsupported, oversized, empty, and disguised files are rejected safely.
- Recursive splitting preserves headings, overlap, and maximum-size invariants.
- Redelivery upserts deterministic point IDs without duplication.
- Stale revision workers cannot overwrite newer state.
- Failed tasks can be retried and dead-lettered through the existing mechanism.

### 12.3 Retrieval security

- Qdrant searches always include user, knowledge-base, document, and revision filters.
- Filter failure returns `UNAVAILABLE`; no unfiltered fallback is executed.
- Deleted, failed, incompatible, or foreign documents cannot enter a validated scope.
- Prompt injection in uploaded content remains data and cannot change system instructions.

### 12.4 Interview behavior

- Selected knowledge scope and first-turn retrieval snapshot persist atomically with interview
  creation.
- The answer evaluator receives the exact snapshot saved for the current turn.
- The next retrieval intent uses the Java-validated decision rather than the raw model suggestion.
- No selected knowledge base preserves the existing interview journey unchanged.
- No match and Qdrant failure still allow the interview to progress.
- Report generation uses stored evaluations and performs no retrieval.

### 12.5 Integration and quality

- MySQL and RabbitMQ behavior remains covered by existing Testcontainers tests.
- A Qdrant generic container verifies real filtered add, search, upsert, and delete behavior.
- WireMock or a deterministic test adapter supplies embedding and chat responses.
- End-to-end tests use a fake retriever for stable orchestration assertions and separate boundary
  tests cover the real Qdrant adapter.
- A small versioned Java/Spring/MySQL retrieval corpus measures Recall@6 and no-match behavior
  before changing chunk size, threshold, or Top-K.

## 13. Delivery Sequence

Implementation should proceed in vertical slices:

1. Authentication and ownership migration across existing resources.
2. Knowledge-base and document management with private file storage.
3. Reliable parsing, splitting, embedding, and Qdrant indexing.
4. Secure validated-scope retrieval.
5. Interview creation selection and first-question grounding.
6. Subsequent-question retrieval and evaluation snapshot reuse.
7. Frontend completion, observability, quality corpus, and failure-path verification.

Each slice must preserve the existing no-RAG interview journey and keep external calls outside
database transactions.
