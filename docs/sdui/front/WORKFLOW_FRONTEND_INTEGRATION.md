# SDUI Workflow Frontend Integration

## 1. Current Completion Status

From the frontend closed-loop integration perspective, the workflow module is currently an MVP-ready backend, not a fully complete workflow product.

The following closed loop is available:

1. Query device-specific workflow node catalog.
2. Build a DAG and editor model in the frontend canvas.
3. Validate the DAG against the selected device.
4. Create or update a workflow definition.
5. Bind the workflow to a device.
6. Enable or disable the workflow.
7. Trigger manually or through device UI input events.
8. Query run and step execution history.

Known frontend-facing gaps:

- `cron` and `webhook` appear in the node catalog, but runtime scheduling and webhook entrypoints are not implemented yet.
- Run status has no SSE/WebSocket stream yet. The frontend should poll run APIs.
- Bindings can be created or overwritten, but there is no dedicated unbind or per-binding disable API yet.
- DAG validation is basic. It checks core topology and common output requirements, but not every nested field schema.
- `control.condition` only emits a result. For v1 branching, use edge-level `condition`.

Recommended v1 visible node set:

- `trigger.manual`
- `trigger.device.ui.event`
- `trigger.command.lifecycle`
- `output.device.command`
- `output.platform.capability`
- `output.section.scene`
- `output.section.patch`
- `control.set_variable`
- `control.terminate`

Hide or mark as upcoming:

- `trigger.cron`
- `trigger.webhook`

## 2. Integration Flow

### Step 1: Query Node Catalog

After selecting a device, query the available workflow nodes:

```http
GET /api/v1/sdui/workflows/nodes?deviceId={deviceId}
```

Use the returned `triggers`, `input`, `output`, and `control` data to render the left-side node palette and node configuration forms.

### Step 2: Build Canvas Data

The frontend should maintain two data objects:

- `dag`: the standard backend runtime model.
- `editorModel`: frontend-only canvas metadata, such as positions, zoom, selected state, and UI layout.

Only `dag` is used by the backend runtime. `editorModel` is stored and returned as-is.

### Step 3: Validate Before Save

```http
POST /api/v1/sdui/workflows/validate?deviceId={deviceId}
```

If `data.valid` is false, show `data.errors` and block publish/enable.

### Step 4: Create Or Update Workflow

Create:

```http
POST /api/v1/sdui/workflows
```

Update:

```http
PUT /api/v1/sdui/workflows/{workflowId}
```

### Step 5: Bind Device

```http
POST /api/v1/sdui/workflows/{workflowId}/bindings
```

### Step 6: Enable Workflow

```http
POST /api/v1/sdui/workflows/{workflowId}/enable
```

### Step 7: Trigger And Debug

Manual trigger:

```http
POST /api/v1/sdui/workflows/{workflowId}/trigger
```

Device UI event trigger is automatic after the workflow is active and bound to the device.
Command lifecycle trigger is also automatic after the workflow is active and bound to the device. It can react to command dispatch, ACK, rejection, failure, timeout, or generic result events.

### Step 8: Query Runs

```http
GET /api/v1/sdui/workflows/{workflowId}/runs?limit=20
GET /api/v1/sdui/workflows/runs/{runId}
```

## 3. Common Response Format

All workflow APIs use the common response wrapper:

```json
{
  "code": 20000,
  "message": "Success",
  "data": {}
}
```

Error example:

```json
{
  "code": 40000,
  "message": "deviceId is required",
  "data": null
}
```

## 4. API Details

### 4.1 Query Workflow Node Catalog

```http
GET /api/v1/sdui/workflows/nodes?deviceId=dev-1
```

Response:

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "deviceId": "dev-1",
    "triggers": [],
    "input": [],
    "output": [],
    "control": []
  }
}
```

Top-level fields:

| Field | Description |
| --- | --- |
| `triggers` | Trigger nodes, such as `manual` and `device.ui.event`. |
| `input` | Device input events from the device capability projection. |
| `output` | Output nodes grouped by device commands, platform capabilities, and sections. |
| `control` | Flow control nodes. |

Node field conventions:

| Field | Description |
| --- | --- |
| `id` | Palette node ID. |
| `type` | `trigger`, `input`, `output`, or `control`. |
| `kind` | Runtime behavior type. |
| `fields` | Form schema for node config. |
| `ports` | Canvas connection port metadata. |

Editor-oriented metadata:

| Field | Description |
| --- | --- |
| `displayName` | Human-readable name from the backend capability catalog. |
| `description` | Human-readable capability or field description. |
| `configTemplate` | Suggested initial `config` value when dragging this node into the canvas. |
| `ui.widget` | Lightweight UI hint. The frontend still owns the actual component implementation. |
| `options` | Selectable values for enum/select fields. |
| `min` / `max` | Numeric constraints when provided by the capability catalog. |

Output group example:

```json
{
  "group": "deviceCommands",
  "nodes": [
    {
      "id": "output.command.rgb.effect.set",
      "type": "output",
      "kind": "device.command",
      "command": "rgb.effect.set",
      "group": "rgb",
      "fields": [],
      "ports": {
        "inputs": ["in"],
        "outputs": ["result"]
      }
    }
  ]
}
```

### 4.2 DAG Format

Recommended frontend DAG format:

```json
{
  "nodes": [
    {
      "id": "trigger_1",
      "type": "trigger",
      "kind": "device.ui.event",
      "config": {
        "eventId": "ui:action.click",
        "nodeId": "submit_btn",
        "sectionId": "actions"
      }
    },
    {
      "id": "rgb_1",
      "type": "output",
      "kind": "device.command",
      "config": {
        "command": "rgb.effect.set",
        "params": {
          "mode": "solid",
          "r": 255,
          "g": 80,
          "b": 40
        }
      }
    }
  ],
  "edges": [
    {
      "from": "trigger_1",
      "to": "rgb_1"
    }
  ]
}
```

Edges also accept `source` and `target` as aliases for `from` and `to`.

Supported data references:

```text
$event.nodeId
$event.value
$event.sectionId
$vars.someName
$steps.nodeId.someField
```

Example variable node:

```json
{
  "id": "save_button",
  "type": "control",
  "kind": "set_variable",
  "config": {
    "name": "lastButton",
    "value": "$event.nodeId"
  }
}
```

Edge condition example:

```json
{
  "from": "trigger_1",
  "to": "rgb_1",
  "condition": "$event.nodeId == 'submit_btn'"
}
```

Command lifecycle trigger example:

```json
{
  "id": "rgb_ack",
  "type": "trigger",
  "kind": "command.lifecycle",
  "config": {
    "eventId": "command.ack",
    "command": "rgb.effect.set",
    "status": "ACKED"
  }
}
```

Supported command lifecycle event IDs:

| Event ID | Meaning |
| --- | --- |
| `command.dispatch` | Command was dispatched by the platform. |
| `command.ack` | Device acknowledged command success. |
| `command.rejected` | Device rejected the command. |
| `command.failed` | Command send or execution failed. |
| `command.timeout` | Command ACK timed out. |
| `command.result` | Other command result event. |

### 4.3 Validate Workflow

```http
POST /api/v1/sdui/workflows/validate?deviceId=dev-1
Content-Type: application/json
```

Request:

```json
{
  "dag": {
    "nodes": [],
    "edges": []
  }
}
```

Response:

```json
{
  "code": 20000,
  "message": "Success",
  "data": {
    "valid": false,
    "errors": [
      "at least one trigger node is required"
    ],
    "warnings": []
  }
}
```

### 4.4 Create Workflow

```http
POST /api/v1/sdui/workflows
Content-Type: application/json
```

Request:

```json
{
  "name": "Button RGB Workflow",
  "description": "Change RGB after clicking a Section button",
  "status": "DRAFT",
  "dag": {
    "nodes": [],
    "edges": []
  },
  "editorModel": {
    "nodes": [],
    "edges": [],
    "viewport": {
      "x": 0,
      "y": 0,
      "zoom": 1
    }
  }
}
```

Response `data`:

```json
{
  "id": "workflow-id",
  "name": "Button RGB Workflow",
  "description": "Change RGB after clicking a Section button",
  "status": "DRAFT",
  "version": 1,
  "dag": {},
  "editorModel": {},
  "createdAt": "2026-06-11T16:00:00",
  "updatedAt": "2026-06-11T16:00:00"
}
```

### 4.5 Update Workflow

```http
PUT /api/v1/sdui/workflows/{workflowId}
Content-Type: application/json
```

Request fields are the same as create. Partial updates are accepted. Every update increments `version`.

### 4.6 List Workflows

```http
GET /api/v1/sdui/workflows
```

Response `data` is an array of workflow definitions.

### 4.7 Get Workflow Detail

```http
GET /api/v1/sdui/workflows/{workflowId}
```

Response `data` includes workflow bindings:

```json
{
  "id": "workflow-id",
  "name": "Button RGB Workflow",
  "dag": {},
  "editorModel": {},
  "bindings": [
    {
      "id": "binding-id",
      "workflowId": "workflow-id",
      "deviceId": "dev-1",
      "enabled": true,
      "bindingStatus": "ACTIVE",
      "lastRunAt": null
    }
  ]
}
```

### 4.8 Bind Device

```http
POST /api/v1/sdui/workflows/{workflowId}/bindings
Content-Type: application/json
```

Request:

```json
{
  "deviceId": "dev-1",
  "enabled": true,
  "bindingStatus": "ACTIVE"
}
```

Calling this endpoint repeatedly for the same workflow and device overwrites the existing binding.

### 4.9 Enable Or Disable Workflow

```http
POST /api/v1/sdui/workflows/{workflowId}/enable
POST /api/v1/sdui/workflows/{workflowId}/disable
```

Enable behavior:

- Sets workflow `status` to `ACTIVE`.
- Sets all related bindings `enabled` to `true`.

Disable behavior:

- Sets workflow `status` to `DISABLED`.
- Sets all related bindings `enabled` to `false`.

### 4.10 Manual Trigger

```http
POST /api/v1/sdui/workflows/{workflowId}/trigger
Content-Type: application/json
```

Request:

```json
{
  "deviceId": "dev-1",
  "payload": {
    "nodeId": "manual_test",
    "value": "hello"
  }
}
```

Response is run detail:

```json
{
  "id": "run-id",
  "workflowId": "workflow-id",
  "deviceId": "dev-1",
  "triggerType": "manual",
  "status": "SUCCEEDED",
  "inputPayload": {},
  "outputPayload": {},
  "errorMessage": null,
  "durationMs": 25,
  "steps": []
}
```

### 4.11 Query Runs

```http
GET /api/v1/sdui/workflows/{workflowId}/runs?limit=20
```

Response `data` is a run list.

```http
GET /api/v1/sdui/workflows/runs/{runId}
```

Response `data` is one run with step details.

Step detail fields:

| Field | Description |
| --- | --- |
| `nodeId` | DAG node ID. |
| `nodeType` | Runtime node type, such as `output.device.command`. |
| `status` | `RUNNING`, `SUCCEEDED`, or `FAILED`. |
| `inputPayload` | Node config at execution time. |
| `outputPayload` | Node execution result. |
| `errorMessage` | Failure reason, if any. |
| `durationMs` | Step duration in milliseconds. |

## 5. Example: Button Click Changes RGB

### DAG

```json
{
  "nodes": [
    {
      "id": "click_submit",
      "type": "trigger",
      "kind": "device.ui.event",
      "config": {
        "eventId": "ui:action.click",
        "nodeId": "submit_btn"
      }
    },
    {
      "id": "set_rgb",
      "type": "output",
      "kind": "device.command",
      "config": {
        "command": "rgb.effect.set",
        "params": {
          "mode": "solid",
          "r": 255,
          "g": 64,
          "b": 32
        }
      }
    }
  ],
  "edges": [
    {
      "from": "click_submit",
      "to": "set_rgb"
    }
  ]
}
```

### Frontend Save Request

```json
{
  "name": "Button Click Changes RGB",
  "status": "DRAFT",
  "dag": {
    "nodes": [
      {
        "id": "click_submit",
        "type": "trigger",
        "kind": "device.ui.event",
        "config": {
          "eventId": "ui:action.click",
          "nodeId": "submit_btn"
        }
      },
      {
        "id": "set_rgb",
        "type": "output",
        "kind": "device.command",
        "config": {
          "command": "rgb.effect.set",
          "params": {
            "mode": "solid",
            "r": 255,
            "g": 64,
            "b": 32
          }
        }
      }
    ],
    "edges": [
      {
        "from": "click_submit",
        "to": "set_rgb"
      }
    ]
  },
  "editorModel": {
    "nodes": [
      {
        "id": "click_submit",
        "position": {
          "x": 100,
          "y": 120
        }
      },
      {
        "id": "set_rgb",
        "position": {
          "x": 420,
          "y": 120
        }
      }
    ],
    "edges": [
      {
        "id": "edge_1",
        "source": "click_submit",
        "target": "set_rgb"
      }
    ]
  }
}
```
