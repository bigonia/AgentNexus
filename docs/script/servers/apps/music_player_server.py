"""
Trusted Script Demo: Music Player
Entry contract:
- on_start(ctx)
- on_event(ctx, event)
"""

PLAYLIST = [
    {"id": "t1", "title": "Morning Light", "artist": "Nova Lane", "duration_s": 188},
    {"id": "t2", "title": "City Pulse", "artist": "Arc Echo", "duration_s": 214},
    {"id": "t3", "title": "Deep Current", "artist": "Polar Field", "duration_s": 201},
]


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


def _current_track(store):
    idx = _safe_int(store.get("track_idx", 0), 0)
    if idx < 0 or idx >= len(PLAYLIST):
        idx = 0
    return idx, PLAYLIST[idx]


def _format_time(seconds):
    s = _safe_int(seconds, 0)
    if s < 0:
        s = 0
    m = s // 60
    rem = s % 60
    if rem < 10:
        return str(m) + ":0" + str(rem)
    return str(m) + ":" + str(rem)


def _progress_pct(position_s, duration_s):
    if duration_s <= 0:
        return 0
    pct = int((position_s * 100) / duration_s)
    if pct < 0:
        return 0
    if pct > 100:
        return 100
    return pct


def _layout(track, playing, position_s):
    duration_s = _safe_int(track.get("duration_s", 1), 1)
    pct = _progress_pct(position_s, duration_s)
    play_text = "Pause" if playing else "Play"

    return {
        "children": [
            {
                "type": "scene",
                "id": "music_scene",
                "bg_color": "#06131E",
                "children": [
                    {
                        "type": "container",
                        "id": "player_card",
                        "w": "92%",
                        "h": "88%",
                        "align": "center",
                        "pad": 12,
                        "radius": 20,
                        "bg_color": "#11273A",
                        "flex": "column",
                        "gap": 8,
                        "children": [
                            {"type": "label", "id": "app_title", "text": "Music Player", "font_size": 22, "text_color": "#E8F3FF"},
                            {"type": "label", "id": "track_title", "text": track["title"], "font_size": 18, "text_color": "#BFE5FF"},
                            {"type": "label", "id": "track_artist", "text": track["artist"], "font_size": 13, "text_color": "#8EB6D2"},
                            {
                                "type": "bar",
                                "id": "progress_bar",
                                "w": "92%",
                                "h": 14,
                                "min": 0,
                                "max": 100,
                                "value": pct,
                                "bg_color": "#24445D",
                                "indic_color": "#5FD7FF",
                                "radius": 7,
                            },
                            {"type": "label", "id": "time_text", "text": _format_time(position_s) + " / " + _format_time(duration_s), "font_size": 12, "text_color": "#9FC1D8"},
                            {
                                "type": "container",
                                "id": "ctrl_row",
                                "w": "100%",
                                "flex": "row",
                                "justify": "center",
                                "gap": 8,
                                "children": [
                                    {"type": "button", "id": "btn_prev", "text": "Prev", "w": 74, "h": 40, "on_click": "server://ui/click?action=prev"},
                                    {"type": "button", "id": "btn_play", "text": play_text, "w": 90, "h": 40, "on_click": "server://ui/click?action=play_pause"},
                                    {"type": "button", "id": "btn_next", "text": "Next", "w": 74, "h": 40, "on_click": "server://ui/click?action=next"},
                                ],
                            },
                            {"type": "label", "id": "state_text", "text": "Playing" if playing else "Paused", "font_size": 12, "text_color": "#82D5B6" if playing else "#C2A47B"},
                        ],
                    }
                ],
            }
        ]
    }


def _event_action(event):
    payload = _extract_payload(event)
    action = payload.get("action")
    if isinstance(action, str):
        return action
    return ""


def _save_state(idx, playing, position_s):
    cap.store.set("track_idx", idx)
    cap.store.set("playing", playing)
    cap.store.set("position_s", position_s)


def on_start(ctx):
    idx = _safe_int(cap.store.get("track_idx", 0), 0)
    if idx < 0 or idx >= len(PLAYLIST):
        idx = 0
    playing = bool(cap.store.get("playing", False))
    position_s = _safe_int(cap.store.get("position_s", 0), 0)
    track = PLAYLIST[idx]
    if position_s > _safe_int(track.get("duration_s", 0), 0):
        position_s = 0

    _save_state(idx, playing, position_s)
    return {
        "action": "layout",
        "page_id": "home",
        "payload": _layout(track, playing, position_s),
    }


def on_event(ctx, event):
    idx, track = _current_track(cap.store)
    playing = bool(cap.store.get("playing", False))
    position_s = _safe_int(cap.store.get("position_s", 0), 0)

    action = _event_action(event)

    if action == "play_pause":
        playing = not playing
    elif action == "next":
        idx = (idx + 1) % len(PLAYLIST)
        track = PLAYLIST[idx]
        position_s = 0
    elif action == "prev":
        idx = (idx - 1 + len(PLAYLIST)) % len(PLAYLIST)
        track = PLAYLIST[idx]
        position_s = 0
    else:
        topic = event.get("topic") if isinstance(event, dict) else ""
        if topic == "telemetry/heartbeat" and playing:
            position_s += 2

    duration_s = _safe_int(track.get("duration_s", 0), 0)
    if duration_s > 0 and position_s >= duration_s:
        idx = (idx + 1) % len(PLAYLIST)
        track = PLAYLIST[idx]
        position_s = 0

    _save_state(idx, playing, position_s)
    pct = _progress_pct(position_s, _safe_int(track.get("duration_s", 1), 1))

    return {
        "actions": [
            {
                "type": "ui_update",
                "page_id": "home",
                "payload": {"id": "track_title", "text": track["title"]},
            },
            {
                "type": "ui_update",
                "page_id": "home",
                "payload": {"id": "track_artist", "text": track["artist"]},
            },
            {
                "type": "ui_update",
                "page_id": "home",
                "payload": {"id": "time_text", "text": _format_time(position_s) + " / " + _format_time(track["duration_s"])} ,
            },
            {
                "type": "ui_update",
                "page_id": "home",
                "payload": {"id": "state_text", "text": "Playing" if playing else "Paused"},
            },
            {
                "type": "ui_update",
                "page_id": "home",
                "payload": {"id": "progress_bar", "value": pct},
            },
            {
                "type": "ui_update",
                "page_id": "home",
                "payload": {"id": "btn_play", "text": "Pause" if playing else "Play"},
            },
        ]
    }
