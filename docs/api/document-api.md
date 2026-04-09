# Domain Document & Cleaning API Reference

Generated on: 2026-03-25

## 1. Domain Document APIs

Base path: `/api/domain-docs`

## 1.1 Script Generation

- `POST /api/domain-docs/generate-script`
- Body: `GenerateScriptRequest`

`GenerateScriptRequest`:
- `docId` (optional)
- `sampleData` (optional)
- `requirement` (recommended)

- Response: `Flux<String>` (streamed script content)

## 1.2 Derived Document

- `POST /api/domain-docs/{parentId}/derive`
- Body: `String` (python script)
- Response: `ApiResponse<Void>`

## 1.3 Entity and Vectorization

- `GET /api/domain-docs/entity/{id}` -> `ApiResponse<Void>`
- `GET /api/domain-docs/vector/{id}` -> `ApiResponse<Void>`

## 1.4 Export

- `GET /api/domain-docs/{docId}/export/excel`
  - Response: binary Excel stream

- `GET /api/domain-docs/{docId}/export/sql?tableName={table}&pkKey={pk}`
  - Response: SQL download stream

## 1.5 Query and Lifecycle

- `GET /api/domain-docs/{id}` -> `ApiResponse<DomainDocument>`
- `GET /api/domain-docs/{id}/context?page={n}&size={n}` -> `ApiResponse<Page<DocumentContext>>`
- `GET /api/domain-docs/{id}/stream` -> `ApiResponse<Stream<DocumentContext>>`
- `GET /api/domain-docs/list` -> `ApiResponse<List<DomainDocument>>`
- `DELETE /api/domain-docs/{id}` -> `ApiResponse<Void>`

## 1.6 Streaming Data Export (NDJSON)

- `GET /api/domain-docs/{docId}/stream`
- Produces: `application/x-ndjson`
- Response: `StreamingResponseBody` (newline-delimited JSON `DocumentContext`)

## 1.7 Action APIs

- `GET /api/domain-docs/actions/support-list`
  - Response: `ApiResponse<Map<BizAction, String>>`
  - `BizAction`: `VECTORIZED | GEN_QA | GEN_TAG`

- `POST /api/domain-docs/{id}/actions`
  - Body:

```json
{
  "action": "VECTORIZED",
  "params": {}
}
```

  - Response: `ApiResponse<Void>`

---

## 2. Cleaning APIs

Base path: `/api/cleaning`

### 2.1 Init Job

- `POST /api/cleaning/jobs`
- Body: `JobInitRequest`

`JobInitRequest`:
- `sourceDocumentId`
- `initialScript` (optional)

- Response: `Long` (job id)

### 2.2 Job Status and Diff

- `GET /api/cleaning/jobs/status?page={n}&size={n}` -> `Page<CleaningRecordDiffDto>`
- `GET /api/cleaning/jobs/{jobId}/diff?page={n}&size={n}` -> `Page<CleaningRecordDiffDto>`

`CleaningRecordDiffDto`:
- `recordId`
- `sourceSegmentId`
- `originalContent`
- `cleanedContent`
- `status`
- `errorMessage`
- `changed`

### 2.3 Human-in-the-loop and Restart

- `POST /api/cleaning/jobs/{jobId}/records/flag`
  - Body: `RecordIssueFlagRequest`
  - `recordId`, `userComment`

- `POST /api/cleaning/jobs/{jobId}/fix-and-restart`
  - Body: `{ "instruction": "..." }`

---

## Known Risks / Inconsistencies

- `DomainDocumentController` defines two `GET /api/domain-docs/{id}/stream` handlers with different return types; one also uses `application/x-ndjson`.
- `CleaningController` `GET /api/cleaning/jobs/status` has a `@PathVariable Long jobId` argument but no `{jobId}` in mapping.
- Several endpoints return raw types instead of `ApiResponse`, causing response style inconsistency.
