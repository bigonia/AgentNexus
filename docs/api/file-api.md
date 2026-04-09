# File API Reference

Generated on: 2026-03-25

Base path: `/api/files`

## 1. Upload File

- `POST /api/files/upload`
- Content-Type: `multipart/form-data`
- Form fields:
  - `file` (required)
  - `sourceSystem` (optional)
- Response: `ApiResponse<KnowledgeFile>`

`KnowledgeFile` fields:
- `id`
- `originalFilename`
- `storedFilename`
- `filePath`
- `sourceSystem`
- `createdAt`

## 2. Convert File to Domain Document

- `POST /api/files/{id}/toDoc`
- Response: `ApiResponse<String>`

## 3. Delete File

- `DELETE /api/files/{id}`
- Response: `ApiResponse<String>`

## 4. List Files

- `GET /api/files`
- Response: `ApiResponse<List<KnowledgeFile>>`

## 5. Get File Metadata by ID

- `GET /api/files/{id}`
- Response: `ResponseEntity<KnowledgeFile>`
  - `200`: file metadata
  - `404`: not found

## 6. Get File Content Stream

- `GET /api/files/{id}/content`
- Response: `ResponseEntity<Resource>`
- Content type auto-detected by extension or file probe.

## Notes

- Some historical endpoints are commented out in code and currently unavailable:
  - `POST /api/files/{id}/preview`
  - `POST /api/files/{id}/vectorize`
  - `DELETE /api/files/{id}/vectors`
