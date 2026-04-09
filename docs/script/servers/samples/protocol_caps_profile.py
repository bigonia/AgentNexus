from __future__ import annotations

from typing import Any, Dict


def clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, int(v)))


def derive_profile_from_caps(caps: Dict[str, Any]) -> Dict[str, Any]:
    device_profile = caps.get("device_profile") if isinstance(caps.get("device_profile"), dict) else {}
    screen = device_profile.get("screen") if isinstance(device_profile.get("screen"), dict) else {}
    w = clamp(screen.get("w", 466) or 466, 200, 1024)
    h = clamp(screen.get("h", 466) or 466, 200, 1024)
    shape = str(screen.get("shape") or "round")
    safe_default = clamp(device_profile.get("safe_pad_default", 20) or 20, 0, 60)

    if shape == "round":
        recommended = clamp(int(min(w, h) * 0.045), 16, 28)
        safe_pad = clamp(max(recommended, int(safe_default * 0.75), 24), 20, 34)
    else:
        recommended = clamp(int(min(w, h) * 0.03), 8, 20)
        safe_pad = clamp(max(recommended, int(safe_default * 0.5)), 8, 24)

    usable = clamp(min(w, h) - safe_pad * 2, 180, 520)
    return {
        "screen_w": w,
        "screen_h": h,
        "shape": shape,
        "safe_pad": safe_pad,
        "usable": usable,
    }


def layout_tokens(profile: Dict[str, Any]) -> Dict[str, int]:
    usable = clamp(profile.get("usable", 426), 180, 520)
    safe_pad = clamp(profile.get("safe_pad", 20), 0, 60)
    screen_min = clamp(min(profile.get("screen_w", 466), profile.get("screen_h", 466)), 200, 1024)
    # Concentric ring sizing:
    # keep the outer ring close to the physical screen boundary for stronger visual impact,
    # then derive inner rings with stable gaps.
    # Full-dial mode without clipping: outer ring equals screen diameter baseline.
    ring_outer = clamp(screen_min, 220, 520)
    ring_gap = clamp(int(screen_min * 0.11), 36, 54)
    ring_mid = clamp(ring_outer - ring_gap, 148, 468)
    ring_inner = clamp(ring_mid - ring_gap, 124, 426)
    return {
        "safe_pad": safe_pad,
        "title_y": clamp(int(safe_pad * 0.95), 18, 34),
        "sub_y": clamp(int(safe_pad * 1.65), 34, 52),
        "btn_w": clamp(int(usable * 0.30), 96, 136),
        "btn_h": clamp(int(usable * 0.10), 34, 46),
        "card_w": clamp(int(usable * 0.36), 120, 164),
        "card_h": clamp(int(usable * 0.20), 66, 94),
        "line_y_top": clamp(int(-usable * 0.21), -98, -48),
        "line_gap": clamp(int(usable * 0.10), 18, 34),
        "ring_outer": ring_outer,
        "ring_mid": ring_mid,
        "ring_inner": ring_inner,
        "ring_sub_y": clamp(int(screen_min * 0.37), 132, 178),
    }
