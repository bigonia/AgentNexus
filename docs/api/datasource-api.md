# DataSource API Reference

Generated on: 2026-03-25

## 1. Database Info APIs

Base path: `/api/database-info`

`DataBaseInfoDTO` key fields:
- `id`
- `name` (required)
- `type` (`MYSQL | POSTGRESQL | SQLSERVER | ORACLE`)
- `description`
- `host`
- `port`
- `databaseName`
- `username`
- `password`
- `extraProperties` (`Map<String,String>`)
- `createdAt`
- `updatedAt`

### 1.1 CRUD

- `POST /api/database-info` -> `ApiResponse<DataBaseInfoDTO>`
- `PUT /api/database-info/{id}` -> `ApiResponse<DataBaseInfoDTO>`
- `GET /api/database-info/{id}` -> `ApiResponse<DataBaseInfoDTO>`
- `GET /api/database-info/name/{name}` -> `ApiResponse<DataBaseInfoDTO>`
- `GET /api/database-info` -> `ApiResponse<List<DataBaseInfoDTO>>`
- `GET /api/database-info/page` -> `ApiResponse<Page<DataBaseInfoDTO>>`
- `DELETE /api/database-info/{id}` -> `ApiResponse<Void>`

### 1.2 Connection Test

- `POST /api/database-info/{id}/test-connection` -> `ApiResponse<Void | error>`
- `POST /api/database-info/test-connection` (body `DataBaseInfoDTO`) -> `ApiResponse<Void | error>`

### 1.3 Metadata Browse

- `GET /api/database-info/schemas/{id}` -> `ApiResponse<List<String>>`
- `GET /api/database-info/{id}/{schema}` -> `ApiResponse<List<String>>` (tables)
- `GET /api/database-info/{id}/{schema}/{table}` -> `ApiResponse<List<String>>` (columns)

### 1.4 Stream Table / Metadata Document

- `POST /api/database-info/{id}/{schema}/{table}/stream`
  - Body: raw string `template`
  - Response: `ApiResponse<Void>`

- `POST /api/database-info/{id}/metadata`
  - Response: `ApiResponse<DomainDocument>`

### 1.5 Existence Check

- `GET /api/database-info/{id}/exists` -> `{"exists": true|false}`
- `GET /api/database-info/name/{name}/exists` -> `{"exists": true|false}`

---

## 2. Metadata APIs

Base path: `/api/metadata`

### 2.1 Check Metadata

- `POST /api/metadata/check/{id}`
- Response: `ResponseEntity<DatabaseMetadata>`

`DatabaseMetadata` fields:
- `databaseProductName`
- `databaseProductVersion`
- `catalogs` (schema/catalog hierarchy)

### 2.2 Save Metadata

- `POST /api/metadata/save/{id}`
- Response: `ResponseEntity<DatabaseMetadata>`

### 2.3 Analyse Metadata to RAG

- `POST /api/metadata/analyse/{id}`
- Response: void (HTTP 200)

### 2.4 Preview Metadata Documents

- `POST /api/metadata/preview/{id}`
- Response: `ResponseEntity<List<Document>>`
