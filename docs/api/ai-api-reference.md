# AI API Reference

Generated on: 2026-03-25  
Scope: AI-related external APIs in `com.zwbd.agentnexus.ai.*`

## 1. Common Response Contract

Most endpoints return `ApiResponse<T>`:

```json
{
  "code": 20000,
  "message": "Success",
  "data": {}
}
```

Common exceptions:
- `GET /api/v1/chat-client/history/{id}` returns raw `List<Message>`
- `PATCH /api/ai/conversations/{conversationId}/title` returns HTTP 200 with empty body
- SSE endpoints return `text/event-stream` event chunks instead of `ApiResponse`

## 2. API Index

| Module | Base Path | Description |
| --- | --- | --- |
| Agent | `/api/v1/agent` | Agent chat, agent config, model registry, tool/advisor query |
| Chat Client | `/api/v1/chat-client` | Generic chat stream and history |
| MCP | `/api/v1/mcp` | Dynamic MCP connection management |
| RAG | `/api/v1/rag` | RAG chat (blocking + streaming) |
| Conversations | `/api/ai/conversations` | Conversation list/create/title/delete/messages |
| Documents | `/api/documents` | Knowledge document search/list/chunk update/delete |

---

## 3. Agent APIs (`/api/v1/agent`)

### 3.1 Chat

- `POST /api/v1/agent/chat?agentId={agentId}`  
  Content-Type: `application/json`  
  Produces: `text/event-stream`  
  Body: `ChatRequest`

`ChatRequest`:
- `query` string, required
- `useRag` boolean
- `RAGFilters` array of `{ key, operator, value }`
- `sessionId` string (optional, server auto-generates if null)

SSE event shape:

```json
{
  "type": "TEXT",
  "data": "..."
}
```

`type` enum: `SESSION_INFO | CONTEXT | TOOL_EXECUTION | TEXT | END`

### 3.2 History

- `GET /api/v1/agent/history/{id}` -> `ApiResponse<List<Message>>`

### 3.3 Tool/Advisor/Model Query

- `GET /api/v1/agent/tools` -> `ApiResponse<List<ToolInfo>>`
- `GET /api/v1/agent/advisors` -> `ApiResponse<Set<String>>`
- `GET /api/v1/agent/models` -> `ApiResponse<Set<String>>`

`ToolInfo`:
- `toolId`
- `name`
- `description`
- `inputSchema`
- `providerId`
- `providerType`
- `enabled`

### 3.4 Model Registry

- `POST /api/v1/agent/model` -> `ApiResponse<String>`
- `DELETE /api/v1/agent/model/{name}` -> `ApiResponse<Void>`

`OpenAiModelConfig`:
- `registrationName` required
- `apiKey` required
- `baseUrl`
- `modelName` required
- `temperature`
- `topP`

### 3.5 Agent CRUD

- `GET /api/v1/agent/agents/{id}` -> `ApiResponse<AgentEntity>`
- `GET /api/v1/agent/agents/list` -> `ApiResponse<List<AgentEntity>>`
- `POST /api/v1/agent/agents` -> `ApiResponse<AgentEntity>`
- `PUT /api/v1/agent/agents/{id}` -> `ApiResponse<AgentEntity>`
- `DELETE /api/v1/agent/agents/{id}` -> `ApiResponse<Void>`

`AgentEntity` key fields:
- `id`
- `name`
- `modelName`
- `systemPrompt`
- `toolNames` (legacy fallback)
- `providerIds` (provider-level authorization)
- `toolIds` (tool-level whitelist)
- `advisors`

---

## 4. Chat Client APIs (`/api/v1/chat-client`)

- `POST /api/v1/chat-client/stream`  
  Produces: `text/event-stream`  
  Body: `ChatRequest`

- `GET /api/v1/chat-client/history/{id}`  
  Returns raw `List<Message>` (not wrapped by `ApiResponse`)

---

## 5. MCP APIs (`/api/v1/mcp`)

### 5.1 Connection Management

- `GET /api/v1/mcp/connections` -> `ApiResponse<List<McpConnectionView>>`
- `GET /api/v1/mcp/connections/{id}` -> `ApiResponse<McpConnectionView>`
- `POST /api/v1/mcp/connections` -> `ApiResponse<McpConnectionView>`
- `PUT /api/v1/mcp/connections/{id}` -> `ApiResponse<McpConnectionView>`
- `DELETE /api/v1/mcp/connections/{id}` -> `ApiResponse<Void>`

### 5.2 Lifecycle Actions

- `POST /api/v1/mcp/connections/{id}/enable` -> `ApiResponse<Void>`
- `POST /api/v1/mcp/connections/{id}/disable` -> `ApiResponse<Void>`
- `POST /api/v1/mcp/connections/{id}/refresh` -> `ApiResponse<McpConnectionView>`
- `POST /api/v1/mcp/connections/{id}/test` -> `ApiResponse<String>`
- `GET /api/v1/mcp/connections/{id}/tools` -> `ApiResponse<List<ToolInfo>>`

`McpConnectionRequest`:
- `name`
- `transportType` (`STDIO | SSE`)
- `enabled`
- `requestTimeoutSeconds`
- STDIO: `command`, `args`, `envVars`
- SSE: `baseUrl`, `sseEndpoint`, `headers`

`McpConnectionView`:
- `id`, `name`, `transportType`, `enabled`, `requestTimeoutSeconds`
- `command`, `baseUrl`, `sseEndpoint`
- `providerId`, `running`, `toolCount`, `lastError`

---

## 6. RAG APIs (`/api/v1/rag`)

- `POST /api/v1/rag/chat` -> `LocalChatResponse`
- `POST /api/v1/rag/chat-stream` -> `text/event-stream` of `StreamEvent`

`LocalChatResponse`:
- `answer`
- `retrieved_documents` (`List<Document>`)
- `sessionId`

---

## 7. Conversation APIs (`/api/ai/conversations`)

- `GET /api/ai/conversations` -> `ApiResponse<List<Conversation>>`
- `POST /api/ai/conversations` body `{ "title": "..." }` -> `ApiResponse<Conversation>`
- `GET /api/ai/conversations/{conversationId}/messages` -> `ApiResponse<List<Message>>`
- `DELETE /api/ai/conversations/{conversationId}` -> `ApiResponse<Void>`
- `PATCH /api/ai/conversations/{conversationId}/title` body `{ "title": "..." }` -> `HTTP 200` empty body

`Conversation`:
- `id`
- `title`
- `createdAt`
- `updatedAt`

---

## 8. Document APIs (`/api/documents`)

- `POST /api/documents/search` -> `ApiResponse<List<Document>>`
- `GET /api/documents/list` -> `ApiResponse<List<DocumentInfoDTO>>`
- `GET /api/documents/{sourceId}` -> `ApiResponse<List<DocumentChunkDTO>>`
- `PUT /api/documents/chunks/{chunkId}` body `UpdateChunkRequest` -> `HTTP 200` empty body
- `DELETE /api/documents/{sourceId}` -> `ApiResponse<Void>`

`SearchRequest` (inline record in controller):
- `query` string
- `topK` int
- `threshold` double

`DocumentInfoDTO`:
- `sourceId`, `sourceName`, `documentType`, `sourceSystem`, `chunkCount`

`DocumentChunkDTO`:
- `id`, `content`, `metadata`

`UpdateChunkRequest`:
- `content` required

---

## 9. Notes and Known Inconsistencies

- Response wrapping is not fully unified (`ApiResponse` vs raw vs empty body).
- Path prefixes are mixed (`/api/v1/...`, `/api/ai/...`, `/api/documents`).
- `agentId` in `POST /api/v1/agent/chat` is query/form parameter (not path variable).
- `ChatRequest` field name is `RAGFilters` (uppercase), clients should match exact JSON key.
