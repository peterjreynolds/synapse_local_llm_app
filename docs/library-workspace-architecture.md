# Library And Workspace Architecture

Status: app-facing foundation slice

Synapse now has a narrow app-owned Library/Workspace foundation exposed through
the app flow. It is not a RAG engine yet, and it is intentionally separate from
personal memory.

## Current Slice

- Markdown artifacts can be created inside app-private storage.
- Artifact metadata is stored in Room as the first catalog layer.
- Every Markdown create mutation writes a durable receipt.
- File names are generated from sanitized titles. User text never becomes an
  arbitrary filesystem path.
- Artifact files live under the app-private `filesDir/library/workspace/...`
  owner path.
- PDF exports are generated into app-private cache under
  `cacheDir/library-exports/pdf/...`.
- PDF export files can be exposed through the app FileProvider for share/export
  flows.
- The app graph exposes the Library/Workspace repository and Markdown PDF
  exporter.
- The ViewModel exposes app-facing operations to create Markdown notes, list
  recent catalog artifacts, export a draft to PDF, and export saved Markdown
  artifacts to PDF.
- The app has a minimal Library panel for creating Markdown notes, refreshing
  recent artifacts, and sharing generated PDFs.

## Current Tables

- `library_artifacts`
  - catalog metadata for an app-private artifact
  - title, display name, relative path, MIME type, kind, source kind, SHA-256,
    byte count, summary, tags, and timestamps
- `library_artifact_write_receipts`
  - durable mutation receipt for artifact writes
  - mutation, artifact ID, timestamp, reason, byte count, and SHA-256

## Frontend-Playground Pattern

The reference pattern from `frontend-playground` is catalog-first retrieval:

1. Route questions through lookup rules.
2. Search small indexes/catalogs by facets and retrieval triggers.
3. Open only the canonical source path when the catalog says it is relevant.
4. Keep original/source artifacts separate from operational memory.
5. Treat import receipts and review queues as provenance, not prompt memory.

Synapse should follow the same shape on-device:

1. Store originals/artifacts in app-private storage.
2. Save catalog metadata first.
3. Add chunk and embedding indexes later.
4. Retrieve metadata first.
5. Open/read chunks only when relevant.
6. Inject cited evidence packs into prompts.

## Memory Boundary

Memory remains for personal facts and preferences:

- "The user likes pizza."
- "The user prefers concise engineering answers."

Research library/workspace artifacts are not memory:

- Markdown notes
- PDFs
- imported docs
- research packs
- generated project files

The assistant may later retrieve library context into a prompt, but the document
itself should not be shoved into durable memory.

## Next Slices

1. Generated chat file cards that can open/share saved workspace artifacts.
2. Text/Markdown import preserving originals plus catalog rows.
3. PDF text extraction into a document-original record.
4. Chunk metadata and keyword index.
5. Local embedding model integration.
6. Retrieval receipts and cited evidence packs.
7. Research graph relationships: similarity, references, conflicts, topics.

## Test Notes

- Repository tests cover safe title/path handling, catalog metadata, durable
  Markdown reads, and FileProvider PDF export receipts.
- The production graph has a library wiring smoke test.
- The ViewModel has a focused app-facing operation test for creating a Markdown
  artifact and refreshing Library panel state.
- Robolectric tests inject a small PDF writer where platform `PdfDocument`
  behavior is not the subject under test. Production exports use Android
  `PdfDocument`.
