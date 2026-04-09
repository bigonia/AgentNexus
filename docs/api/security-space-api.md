# Security & Space API Reference

Generated on: 2026-03-25

## 1. Authentication APIs

Base path: `/api/auth`

### 1.1 Login

- `POST /api/auth/login`
- Body:

```json
{
  "username": "string",
  "password": "string"
}
```

- Response (raw map, not `ApiResponse`):

```json
{
  "code": 20000,
  "data": {
    "token": "jwt-token"
  }
}
```

### 1.2 Current User Info

- `GET /api/auth/info`
- Response:

```json
{
  "code": 20000,
  "data": {
    "roles": ["guest"],
    "introduction": "I am a super administrator",
    "avatar": "https://...",
    "name": "Super Admin"
  }
}
```

### 1.3 Logout

- `POST /api/auth/logout`
- Response:

```json
{
  "code": 20000,
  "data": "success"
}
```

---

## 2. Space APIs

Base path: `/api/spaces`

### 2.1 Create Space

- `POST /api/spaces`
- Body: `BusinessSpace`

```json
{
  "name": "string",
  "description": "string"
}
```

- Response: `ApiResponse<BusinessSpace>`

### 2.2 Delete Space

- `DELETE /api/spaces/{id}`
- Response: `HTTP 200` empty body

### 2.3 List Spaces

- `GET /api/spaces/all`
- Response: `ApiResponse<List<BusinessSpace>>`

`BusinessSpace` fields:
- `id`
- `name`
- `description`
- `createdAt`
- `updatedAt`
