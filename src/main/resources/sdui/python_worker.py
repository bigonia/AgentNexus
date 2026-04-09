import json
import sys
import traceback
from types import SimpleNamespace


class RuntimeStore:
    def __init__(self):
        self._store = {}

    def get(self, key, default=None):
        return self._store.get(key, default)

    def set(self, key, value):
        self._store[key] = value


class CapabilityProxy:
    def __init__(self, initial_store=None, assets=None, device_state=None):
        self.store = RuntimeStore()
        if isinstance(initial_store, dict):
            for k, v in initial_store.items():
                self.store.set(k, v)
        self.assets = assets if isinstance(assets, list) else []
        self.device_state = device_state if isinstance(device_state, dict) else {}

    # MVP phase: capabilities are intentionally minimal and local-only.
    # Java-side capability API can be wired in next iteration.
    def asset_get(self, asset_id):
        for a in self.assets:
            if a.get("asset_id") == asset_id:
                return a
        return None

    def asset_search(self, tags, limit=5):
        if not tags:
            return self.assets[:limit]
        tag_set = set(tags)
        result = []
        for asset in self.assets:
            atags = set(asset.get("tags") or [])
            if atags & tag_set:
                result.append(asset)
            if len(result) >= limit:
                break
        return result

    def device_get_state(self):
        return self.device_state


APP_CACHE = {}


def safe_globals():
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
    }
    return {"__builtins__": allowed_builtins}


def load_app(key, script_content):
    if key in APP_CACHE:
        return APP_CACHE[key]
    namespace = safe_globals()
    exec(script_content, namespace, namespace)
    APP_CACHE[key] = namespace
    return namespace


def to_response(request_id, ok, result=None, error=None, store=None):
    payload = {
        "request_id": request_id,
        "ok": ok,
        "result": result,
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
                runtime_ctx.get("assets"),
                runtime_ctx.get("device_state"),
            )
            fn = ns.get(method)
            if fn is None:
                to_response(request_id, False, error=f"METHOD_NOT_FOUND:{method}")
                continue

            ctx = runtime_ctx
            payload = request.get("payload") or {}

            if method == "on_start":
                result = fn(ctx)
            elif method == "on_event":
                result = fn(ctx, payload)
            elif method == "on_timer":
                result = fn(ctx, payload.get("now_ms"))
            else:
                to_response(request_id, False, error=f"METHOD_NOT_SUPPORTED:{method}")
                continue

            if result is None:
                result = {"action": "noop", "payload": {}}
            if not isinstance(result, dict):
                result = {"action": "noop", "payload": {}}
            to_response(request_id, True, result=result, store=ns["cap"].store._store)
        except json.JSONDecodeError as jde:
            # If this chunk already ends a line but still cannot decode,
            # treat it as malformed JSON instead of waiting forever.
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
