"""Postcondition verifiers.

A pipeline's ``postcondition_json`` is checked after its steps run. This is what
separates "all recognizers matched" from "the goal actually happened": a
replay whose buttons match but that takes no photo must be marked failed.

v0 verifier types (stored as JSON):

    {"type":"file_count","path":"/sdcard/DCIM/Camera","delta_min":1,"timeout":10}
        Device-side directory count must grow by delta_min. Used by the PC
        prototype for camera-style goals; the Android fork maps this to a
        custom action/controller callback.

    {"type":"element","name":"<elements.name>","timeout":8}
        A shared MaaFramework element (OCR / TemplateMatch) must match.

    {"type":"screen_text","text":"已赞","timeout":8}
        OCR must find the text somewhere on screen.

    {"type":"pixel","point":[130,1200],"rgb":[251,114,153],"tolerance":70,
     "radius":8,"timeout":8}
        Mean color in a small box around a device point must be within
        tolerance. Local and free; good for toggle states (a like button
        turning pink) that MaaFW text/template matching cannot express.

    {} / {"type":"none"}
        No postcondition: verification is skipped (recorded as verified=0).
"""

import json
import re
import subprocess
import time

from . import config


def adb_file_count(path, serial=None):
    base = ["adb"] + (["-s", serial] if serial else [])
    try:
        out = subprocess.run(
            base + ["shell", "ls", "-1", path],
            capture_output=True,
            text=True,
            timeout=20,
        )
    except Exception:
        return None
    if out.returncode != 0:
        return None
    return len([line for line in out.stdout.splitlines() if line.strip()])


def _poll(fn, timeout=8.0, interval=0.8):
    deadline = time.time() + float(timeout)
    while True:
        value = fn()
        if value:
            return value
        if time.time() >= deadline:
            return None
        time.sleep(interval)


def _locator_for(con, app_id, name):
    if not app_id:
        return None
    row = con.execute(
        "SELECT locator_json FROM elements WHERE app_id=? AND name=?", (app_id, name)
    ).fetchone()
    return json.loads(row["locator_json"] or "{}") if row else None


def _element_hit(exec_, locator, image=None):
    """Run one MaaFramework recognition for a stored locator."""
    kind = locator.get("type")
    if kind == "template":
        name = locator.get("template") or ""
        if not exec_.load_template(name, locator.get("path")):
            return None
        reco = exec_.match(name, image=image, threshold=float(locator.get("threshold", 0.7)))
    else:
        value = locator.get("value", "")
        if not value:
            return None
        expected = [value if locator.get("contains", True) else f"^{re.escape(value)}$"]
        reco = exec_.ocr(image=image, expected=expected, threshold=float(locator.get("threshold", 0.3)))
    if not reco or not reco.hit:
        return None
    result = {"hit": True}
    if reco.box:
        result["box"] = list(reco.box)
    best = getattr(reco, "best_result", None)
    if best is not None:
        if getattr(best, "score", None) is not None:
            result["score"] = round(float(best.score), 3)
        if getattr(best, "text", None):
            result["text"] = best.text
    return result


def evaluate(con, exec_, postcondition, context=None, app_id=None, log=print):
    """Return (ok, evidence). ``exec_`` may be None for file checks."""
    post = postcondition or {}
    kind = post.get("type")
    if not kind or kind == "none":
        return True, {"type": "none", "skipped": True}
    if kind == "file_count":
        path = post.get("path", "")
        delta_min = int(post.get("delta_min", 1))
        timeout = float(post.get("timeout", 10))
        baseline = (context or {}).get("file_count", {}).get(path)
        if baseline is None:
            baseline = adb_file_count(path)
        after = _poll(
            lambda: _count_or_none(path, baseline, delta_min), timeout=timeout
        )
        evidence = {
            "type": kind,
            "path": path,
            "before": baseline,
            "after": after,
            "delta_min": delta_min,
        }
        ok = after is not None
        if not ok:
            evidence["error"] = "directory count did not reach baseline+delta_min"
        return ok, evidence
    if kind == "element":
        name = post.get("name", "")
        locator = _locator_for(con, app_id, name)
        if not locator:
            return False, {"type": kind, "name": name, "error": "element not found"}
        hit = _poll(
            lambda: _element_hit(exec_, locator),
            timeout=float(post.get("timeout", 8)),
        )
        return bool(hit), {"type": kind, "name": name, "hit": hit or False}
    if kind == "pixel":
        point = post.get("point") or []
        rgb = post.get("rgb") or []
        if len(point) != 2 or len(rgb) != 3:
            return False, {"type": kind, "error": "point and rgb are required"}
        radius = int(post.get("radius", 8))
        tolerance = float(post.get("tolerance", 70))
        target = tuple(int(v) for v in rgb)
        timeout = float(post.get("timeout", 8))

        def check():
            if exec_ is None:
                return None
            image = exec_.image().convert("RGB")
            x, y = int(point[0]), int(point[1])
            crop = image.crop(
                (max(0, x - radius), max(0, y - radius),
                 min(image.width, x + radius), min(image.height, y + radius))
            )
            mean = crop.resize((1, 1)).getpixel((0, 0))
            distance = max(abs(mean[i] - target[i]) for i in range(3))
            if distance <= tolerance:
                return {"mean": list(mean), "distance": round(distance, 1)}
            return None

        hit = _poll(check, timeout=timeout, interval=0.6)
        evidence = {
            "type": kind,
            "point": [int(v) for v in point],
            "rgb": list(target),
            "tolerance": tolerance,
            "radius": radius,
            "timeout": timeout,
            "hit": hit or False,
        }
        return bool(hit), evidence
    if kind == "screen_text":
        text = post.get("text", "")
        if not text:
            return False, {"type": kind, "error": "empty text"}
        locator = {"type": "ocr", "value": text, "contains": True}
        hit = _poll(
            lambda: _element_hit(exec_, locator),
            timeout=float(post.get("timeout", 8)),
        )
        return bool(hit), {"type": kind, "text": text, "hit": hit or False}
    return False, {"type": kind, "error": "unsupported postcondition type"}


def _count_or_none(path, baseline, delta_min):
    if baseline is None:
        return None
    now = adb_file_count(path)
    if now is None or now - baseline < delta_min:
        return None
    return now


def baseline(con, exec_, postcondition):
    """Capture any context the verifier needs *before* the pipeline steps run."""
    post = postcondition or {}
    ctx = {"file_count": {}}
    if post.get("type") == "file_count" and post.get("path"):
        ctx["file_count"][post["path"]] = adb_file_count(post["path"])
    return ctx
