"""
Trusted Script Demo: Host Info Swipe
Entry contract:
- on_start(ctx)
- on_event(ctx, event)
"""

PAGE_IDS = ["overview", "cpu", "memory", "storage"]


def _get_store():
    return cap.store


def _safe_int(value, default_value=0):
    try:
        return int(value)
    except Exception:
        return default_value


def _extract_payload(event):
    if not isinstance(event, dict):
        return {}
    payload = event.get("payload")
    return payload if isinstance(payload, dict) else {}


def _extract_topic(event):
    if not isinstance(event, dict):
        return ""
    topic = event.get("topic")
    return topic if isinstance(topic, str) else ""


def _current_page(store):
    page = store.get("current_page", "overview")
    if page not in PAGE_IDS:
        return "overview"
    return page


def _calc_metrics(store):
    tick = _safe_int(store.get("tick", 0), 0)
    cpu = 25 + ((tick * 7) % 55)
    mem = 35 + ((tick * 5) % 45)
    disk = 48 + ((tick * 3) % 40)
    temp = 36 + (tick % 8)
    return {
        "tick": tick,
        "cpu": cpu,
        "mem": mem,
        "disk": disk,
        "temp": temp,
    }


def _page_title(page_id):
    return {
        "overview": "Host Overview",
        "cpu": "CPU Detail",
        "memory": "Memory Detail",
        "storage": "Storage Detail",
    }.get(page_id, "Host Overview")


def _main_value(page_id, metrics):
    if page_id == "cpu":
        return str(metrics["cpu"]) + "%"
    if page_id == "memory":
        return str(metrics["mem"]) + "%"
    if page_id == "storage":
        return str(metrics["disk"]) + "%"
    return "OK"


def _sub_value(page_id, metrics):
    if page_id == "cpu":
        return "Temp " + str(metrics["temp"]) + "C"
    if page_id == "memory":
        return "Used " + str(metrics["mem"]) + "%"
    if page_id == "storage":
        return "Used " + str(metrics["disk"]) + "%"
    return "CPU " + str(metrics["cpu"]) + "% | MEM " + str(metrics["mem"]) + "%"


def _build_layout(page_id, auto_refresh, metrics):
    auto_text = "Auto ON" if auto_refresh else "Auto OFF"
    return {
        "children": [
            {
                "type": "scene",
                "id": "host_scene",
                "bg_color": "#081523",
                "children": [
                    {
                        "type": "container",
                        "id": "host_card",
                        "w": "92%",
                        "h": "88%",
                        "align": "center",
                        "pad": 12,
                        "radius": 18,
                        "bg_color": "#10283D",
                        "flex": "column",
                        "gap": 8,
                        "children": [
                            {"type": "label", "id": "title", "text": _page_title(page_id), "font_size": 20, "text_color": "#E9F6FF"},
                            {"type": "label", "id": "main_value", "text": _main_value(page_id, metrics), "font_size": 30, "text_color": "#7AD8FF"},
                            {"type": "label", "id": "sub_value", "text": _sub_value(page_id, metrics), "font_size": 14, "text_color": "#A2C5DD"},
                            {
                                "type": "bar",
                                "id": "metric_bar",
                                "w": "92%",
                                "h": 14,
                                "min": 0,
                                "max": 100,
                                "value": metrics["cpu"],
                                "bg_color": "#23435C",
                                "indic_color": "#51C7FF",
                                "radius": 7,
                            },
                            {
                                "type": "container",
                                "id": "page_tabs",
                                "w": "100%",
                                "flex": "row",
                                "justify": "space_between",
                                "gap": 4,
                                "children": [
                                    {"type": "button", "id": "tab_overview", "text": "Overview", "w": 72, "h": 34, "on_click": "server://ui/click?action=page_overview"},
                                    {"type": "button", "id": "tab_cpu", "text": "CPU", "w": 58, "h": 34, "on_click": "server://ui/click?action=page_cpu"},
                                    {"type": "button", "id": "tab_memory", "text": "Memory", "w": 72, "h": 34, "on_click": "server://ui/click?action=page_memory"},
                                    {"type": "button", "id": "tab_storage", "text": "Storage", "w": 72, "h": 34, "on_click": "server://ui/click?action=page_storage"},
                                ],
                            },
                            {
                                "type": "container",
                                "id": "ops_row",
                                "w": "100%",
                                "flex": "row",
                                "justify": "center",
                                "gap": 8,
                                "children": [
                                    {"type": "button", "id": "btn_refresh", "text": "Refresh", "w": 92, "h": 38, "on_click": "server://ui/click?action=refresh"},
                                    {"type": "button", "id": "btn_toggle_auto", "text": auto_text, "w": 108, "h": 38, "on_click": "server://ui/click?action=toggle_auto"},
                                ],
                            },
                            {"type": "label", "id": "tick_text", "text": "Tick #" + str(metrics["tick"]), "font_size": 12, "text_color": "#84A7C2"},
                        ],
                    }
                ],
            }
        ]
    }


def _store_state(page_id, auto_refresh, tick):
    cap.store.set("current_page", page_id)
    cap.store.set("auto_refresh", auto_refresh)
    cap.store.set("tick", tick)


def on_start(ctx):
    store = _get_store()
    page_id = _current_page(store)
    auto_refresh = bool(store.get("auto_refresh", True))
    tick = _safe_int(store.get("tick", 0), 0)
    cap.store.set("tick", tick)
    metrics = _calc_metrics(store)
    _store_state(page_id, auto_refresh, metrics["tick"])
    return {
        "action": "layout",
        "page_id": "home",
        "payload": _build_layout(page_id, auto_refresh, metrics),
    }


def _event_action(event):
    payload = _extract_payload(event)
    action = payload.get("action")
    if isinstance(action, str) and action:
        return action
    topic = _extract_topic(event)
    if topic == "ui/page_changed":
        page = payload.get("page")
        if isinstance(page, str) and page:
            return "page_" + page
    return ""


def on_event(ctx, event):
    store = _get_store()
    page_id = _current_page(store)
    auto_refresh = bool(store.get("auto_refresh", True))
    tick = _safe_int(store.get("tick", 0), 0)

    action = _event_action(event)

    if action == "refresh":
        tick += 1
    elif action == "toggle_auto":
        auto_refresh = not auto_refresh
    elif action == "page_overview":
        page_id = "overview"
    elif action == "page_cpu":
        page_id = "cpu"
    elif action == "page_memory":
        page_id = "memory"
    elif action == "page_storage":
        page_id = "storage"
    else:
        topic = _extract_topic(event)
        if topic == "telemetry/heartbeat" and auto_refresh:
            tick += 1

    _store_state(page_id, auto_refresh, tick)
    metrics = _calc_metrics(store)

    if action.startswith("page_"):
        return {
            "action": "layout",
            "page_id": "home",
            "payload": _build_layout(page_id, auto_refresh, metrics),
        }

    return {
        "action": "update",
        "page_id": "home",
        "payload": {
            "id": "main_value",
            "text": _main_value(page_id, metrics),
        },
    }
