---
name: documents
description: How Theseus reads and generates document files, with the exact working paths and their limits. Ingest covers pdf/docx/xlsx/csv-family attachments (extract-text lane); generation covers markdown/html/docx/pdf/xlsx and the a2a_task borrow for polished output. Triggers on "read this pdf/xlsx/docx", "extract the attachment", "make/generate a pdf/docx/xlsx/report/spreadsheet", "send me the report as a document", "borrow document generation from OpenCrabs".
metadata:
  version: 0.1.0
  verified: 2026-11-12
---

# /documents — read and generate document files

Every path below was verified live on this machine (2026-11-12, receipts in
`docs/webdeck-parity-witness.edn` and `CHANGELOG.md`). Nothing here is
speculation; the dead ends are named so they are not retried.

## Reading (ingest)

Attachments arrive as persisted descriptor maps; `bb-agent.telegram-extract`
(extract-text) already dispatches by extension + mime. All rows below are the
**live** behavior on this machine:

| Format | Reader | Notes |
|---|---|---|
| PDF | `pdftotext -layout` (poppler 26.11, `/opt/homebrew/bin`) | resolve-bin covers launchd's minimal PATH; mutool fallback |
| DOCX | zero-dep zip+XML (`word/document.xml`) | no external binary, no POI |
| XLSX | `bb-excel` 0.1.1 → `## Sheet` + `A: v | B: v` rows | strings only — no formulas, no styles |
| txt/md/csv/tsv/json/xml/edn/clj/yaml | built-in slurp | bounded 20k chars, 20MB source cap |

If a reader is missing, extract-text returns nil honestly and the caller
renders "[text extraction unavailable]" — never a guessed summary.

## Generating

| Target | Path | When |
|---|---|---|
| Markdown / plain text | write_file | default for reports |
| HTML | hiccup (bundled) or hand-written | when styling matters but it stays text |
| DOCX | `textutil -convert docx` (macOS built-in) on an HTML file | quick Word output, zero deps |
| XLSX | `bb-excel` `create-xlsx` (basic, strings/numbers) | simple data sheets |
| PDF (from a page) | BrowserOS `pdf` tool via the MCP client — Chrome print-to-PDF | the page IS the document |
| PDF/DOCX/XLSX/PPTX (polished) | **`a2a_task`** → OpenCrabs `http://127.0.0.1:18790` | styles, live formulas, branding — anything bb cannot do |

## The borrow pattern (a2a_task)

Approval-gated tool: the model supplies `url` + `text`; the localhost bridge
token attaches automatically (never an argument, never printed). Verified
roundtrip 2026-11-12: a task asking for a one-page PDF at a chosen path came
back `completed` with the path, the file on disk, and its marker sentence
extractable by this agent's own pdftotext lane.

Keep requests to the bridge in this shape:

```
a2a_task {url: "http://127.0.0.1:18790",
          text: "Generate a <format> at <absolute path> with <content>.
                 Use your generate_document tool. Reply with the path."}
```

The reply lands as the tool output; the file is already local — do not
re-request it. Remote (non-localhost) URLs get **no** stored token: a
borrowed agent must never receive this machine's credential.

## Dead ends — do not retry

- **clj-pdf / OpenPDF family**: dies on `java.awt.Graphics2D` in bb's native
  image (probed 2026-11-12). PDFBox/POI wrappers share the same wall.
- **Bing HTML as a search backend**: quality failure, replaced by Brave.

## Limits, stated

- bb-excel writes strings/numbers only — no formulas, no styles.
- textutil docx is HTML-downgraded: tables and images do not survive as
  native Word objects.
- PDF ingest via pdftotext is text-only: scanned pages extract nothing
  (honest nil, not OCR).
