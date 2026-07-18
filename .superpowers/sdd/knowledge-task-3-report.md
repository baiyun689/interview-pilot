# Knowledge Task 3 Report

## Scope delivered

- Private filesystem storage with UUID-only keys in the form `<userId>/<documentId>/source`.
- Canonical root containment, traversal rejection, symbolic-link checks, POSIX private permissions,
  and single-link validation where the filesystem exposes Unix link counts.
- Bounded 10 MiB private writes with temporary-file cleanup and atomic-move-only replacement. An
  unsupported atomic move fails closed and preserves the prior document.
- MD, TXT, PDF, and DOCX extraction using Apache Tika with type detection for every extension;
  embedded-document parsing and PDF OCR are disabled. DOCX parsing configures POI `ZipSecureFile`
  once with inflate-ratio, per-entry, entry-count, text-size, and Tika character limits.
- Text normalization and a fixed 1600-character recursive splitter with 200-character overlap
  included in the final chunk budget, including UTF-16 surrogate-pair-safe fixed boundaries.

## TDD evidence

Initial focused test run failed at `compileTestJava` because the three Task 3 implementation classes did not exist. The implementation was then added and the focused test suite was run through red/green iterations. The follow-up hardening tests also failed before the bounded copy, atomic-move strategy, full MIME validation, POI ZIP limits, and final chunk-budget changes were introduced.

## Verification

```text
.\\gradlew.bat test --tests 'interview.pilot.knowledge.storage.*' --tests 'interview.pilot.knowledge.indexing.*'
BUILD SUCCESSFUL

.\\gradlew.bat compileTestJava
BUILD SUCCESSFUL
```

The tests cover malicious filenames and keys, traversal rejection, symbolic-link directory and
target exchange rejection, POSIX private-root and hard-link rejection where available, bounded
overwrite protection, atomic-move failure, directory deletion rejection, markdown normalization,
empty documents, PDF/DOCX extraction,
fake PDF/DOCX/ZIP/HTML content declared as TXT/MD, an inflated DOCX package, blank text, Chinese
punctuation, Markdown headings, 1600-character final chunk boundaries, 200-character overlap,
and surrogate-pair boundaries.

## Threat-model boundary

The store serializes its own operations and revalidates the configured root, real ancestor paths,
symbolic links, and regular-file status immediately around each operation. It cannot guarantee
atomic protection against a malicious process running as the same operating-system user that races
between filesystem checks and calls; deployment must keep the private root owned by a dedicated
service account and inaccessible to untrusted local users.
