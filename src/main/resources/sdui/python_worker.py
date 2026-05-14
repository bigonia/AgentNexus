import builtins
import json
import os
import sys
import traceback


class RuntimeStore:
    def __init__(self):
        self._store = {}

    def get(self, key, default=None):
        return self._store.get(key, default)

    def set(self, key, value):
        self._store[key] = value


class CapabilityProxy:
    def __init__(self, initial_store=None, device_state=None):
        self.store = RuntimeStore()
        if isinstance(initial_store, dict):
            for k, v in initial_store.items():
                self.store.set(k, v)
        self.device_state = device_state if isinstance(device_state, dict) else {}

    def device_get_state(self):
        return self.device_state


APP_CACHE = {}
UNSAFE_MODE_ENV = "SDUI_PYTHON_UNSAFE_MODE"


def is_unsafe_mode_enabled():
    raw = os.getenv(UNSAFE_MODE_ENV, "false")
    return str(raw).strip().lower() in {"1", "true", "yes", "on"}


def safe_globals():
    if is_unsafe_mode_enabled():
        return {
            "__builtins__": dict(builtins.__dict__),
            "__name__": "__sdui_app__",
        }

    allowed_builtins = {
        "len": len,
        "min": min,
        "max": max,
        "sum": sum,
        "range": range,
        "int": int,
        "float": float,
        "str": str,
        "bool": bool,
        "dict": dict,
        "list": list,
        "set": set,
        "tuple": tuple,
        "enumerate": enumerate,
        "abs": abs,
        "globals": globals,
        "locals": locals,
        "isinstance": isinstance,
        "hasattr": hasattr,
        "getattr": getattr,
        "setattr": setattr,
        "callable": callable,
        "Exception": Exception,
        "__import__": __import__,
        "__build_class__": builtins.__build_class__,
    }
    return {
        "__builtins__": allowed_builtins,
        "__name__": "__sdui_app__",
    }


def load_app(key, script_content):
    if key in APP_CACHE:
        return APP_CACHE[key]
    namespace = safe_globals()
    if script_content and script_content[0] == "\ufeff":
        script_content = script_content[1:]
    exec(script_content, namespace, namespace)
    APP_CACHE[key] = namespace
    return namespace


def normalize_actions(result):
    if not isinstance(result, dict):
        return [{"type": "noop", "page_id": "home", "payload": {}}]

    if isinstance(result.get("actions"), list):
        normalized = []
        for item in result.get("actions", []):
            if not isinstance(item, dict):
                continue
            normalized.append({
                "type": item.get("type", "noop"),
                "page_id": item.get("page_id", "home"),
                "payload": item.get("payload", {}),
            })
        return normalized if normalized else [{"type": "noop", "page_id": "home", "payload": {}}]

    action = result.get("action", "noop")
    if action == "layout":
        type_name = "ui_layout"
    elif action == "update":
        type_name = "ui_update"
    else:
        type_name = "noop"

    return [{
        "type": type_name,
        "page_id": result.get("page_id", "home"),
        "payload": result.get("payload", {}),
    }]


def to_response(request_id, ok, result=None, actions=None, error=None, store=None):
    payload = {
        "request_id": request_id,
        "ok": ok,
        "result": result,
        "actions": actions,
        "error": error,
        "store": store,
    }
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def run():
    decoder = json.JSONDecoder()
    buffer = ""
    max_buffer = 512 * 1024
    for raw in sys.stdin:
        chunk = raw
        if not chunk:
            continue

        buffer += chunk
        if len(buffer) > max_buffer:
            to_response(None, False, error="REQUEST_TOO_LARGE_OR_INVALID_JSON_STREAM")
            buffer = ""
            continue

        request = None
        try:
            text = buffer.strip()
            if not text:
                continue
            request, end = decoder.raw_decode(text)
            if text[end:].strip():
                raise ValueError("EXTRA_DATA_AFTER_JSON")
            buffer = ""
            request_id = request.get("request_id")
            app_id = request.get("app_id")
            app_version = request.get("app_version")
            method = request.get("method")
            script_content = request.get("script_content", "")
            key = f"{app_id}:{app_version}"

            ns = load_app(key, script_content)
            runtime_ctx = request.get("ctx") or {}
            ns["cap"] = CapabilityProxy(
                runtime_ctx.get("store"),
                runtime_ctx.get("device_state"),
            )
            fn = ns.get(method)
            if fn is None:
                to_response(request_id, False, error=f"METHOD_NOT_FOUND:{method}")
                continue

            ctx = runtime_ctx
            event = request.get("event") or {}

            if method == "on_start":
                result = fn(ctx)
            elif method == "on_event":
                result = fn(ctx, event)
            elif method == "on_timer":
                result = fn(ctx, event.get("now_ms"))
            else:
                to_response(request_id, False, error=f"METHOD_NOT_SUPPORTED:{method}")
                continue

            if result is None:
                result = {"action": "noop", "payload": {}}
            actions = normalize_actions(result)
            to_response(request_id, True, result=result, actions=actions, store=ns["cap"].store._store)
        except json.JSONDecodeError as jde:
            if chunk.endswith("\n"):
                preview = buffer.strip()[:240]
                to_response(
                    None,
                    False,
                    error=f"MALFORMED_REQUEST_JSON: {jde}; preview={preview}"
                )
                buffer = ""
            continue
        except Exception as ex:
            request_id = None if request is None else request.get("request_id")
            err = f"{type(ex).__name__}: {str(ex)}"
            tb = traceback.format_exc(limit=3)
            to_response(request_id, False, error=f"{err}; traceback={tb}")


if __name__ == "__main__":
    run()
