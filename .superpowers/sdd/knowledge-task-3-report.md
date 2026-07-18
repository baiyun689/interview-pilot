# Knowledge Task 3 Report

## Scope delivered

- Private filesystem storage with UUID-only keys in the form `<userId>/<documentId>/source`.
- Canonical root containment, traversal rejection, and symbolic-link checks for stored paths.
- MD, TXT, PDF, and DOCX text extraction using Apache Tika with embedded-document parsing and PDF OCR disabled.
- Text normalization and a fixed 1600-character recursive splitter with 200-character overlap.

## TDD evidence

Initial focused test run failed at `compileTestJava` because the three Task 3 implementation classes did not exist. The implementation was then added and the focused test suite was run through red/green iterations.

## Verification

```text
.\\gradlew.bat test --tests 'interview.pilot.knowledge.storage.*' --tests 'interview.pilot.knowledge.indexing.*'
BUILD SUCCESSFUL

.\\gradlew.bat compileTestJava
BUILD SUCCESSFUL
```

The tests cover malicious filenames and keys, traversal rejection, a symbolic-link directory escape, store/open/delete behavior, markdown normalization, empty documents, PDF/DOCX extraction, blank text, Chinese punctuation, Markdown headings, 1600-character chunk boundaries, and 200-character overlap.
