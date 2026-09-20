"""Native MaaFramework field plumbing.

Rows store MaaFW protocol fields 1:1 (see docs/INTEGRATION.md). This module:

- normalizes the legacy prototype shorthand (``value``/``contains``/``path``/
  ``point``) into the native field names;
- validates the field types we pass through to MaaFW;
- builds a node dict from a step + its resolved locator.

The compiler intentionally does not translate geometry or reimplement
matching: it resolves element references, fills defaults and validates.
"""

import copy
import re
from pathlib import Path

# Fields we know and type-check. Extra fields are passed through untouched so
# newer MaaFW protocol additions keep working.
_FIELD_TYPES = {
    "roi": (list, tuple, str, bool),
    "roi_offset": (list, tuple),
    "target": (list, tuple, str, bool),
    "target_offset": (list, tuple),
    "expected": (list, tuple, str),
    "template": (str, list, tuple),
    "threshold": (int, float, list, tuple),
    "method": (int,),
    "green_mask": (bool,),
    "detector": (str,),
    "ratio": (int, float),
    "count": (int,),
    "connected": (bool,),
    "index": (int,),
    "order_by": (str,),
    "replace": (list,),
    "only_rec": (bool,),
    "model": (str,),
    "color_filter": (str,),
    "lower": (list,),
    "upper": (list,),
    "labels": (list,),
    "custom_recognition": (str,),
    "custom_action": (str,),
}

_RECOGNITION_TYPES = {
    "directhit", "templatematch", "featurematch", "colormatch", "ocr",
    "neuralnetworkclassify", "neuralnetworkdetect", "and", "or", "custom",
}


class PipelineModelError(ValueError):
    pass


def _expected_regex(value, contains=True):
    value = str(value or "")
    if contains:
        return [re.escape(value)]
    return [f"^{re.escape(value)}$"]


def normalize_locator(locator):
    """Return a copy of a locator with native MaaFW fields.

    Legacy rows (``{"type":"ocr","value":"搜索","contains":true}``,
    ``{"type":"template","path":".../shutter.png"}``,
    ``{"type":"point","point":[x,y]}``) keep working.
    """
    loc = copy.deepcopy(dict(locator or {}))
    kind = str(loc.get("type") or "").lower()
    if kind in ("uiauto",):
        kind = "ocr"
    loc["type"] = kind or loc.get("type") or "ocr"

    if kind == "ocr" or loc.get("value") is not None:
        loc["type"] = "ocr"
        if "expected" not in loc and loc.get("value") is not None:
            loc["expected"] = _expected_regex(loc.pop("value"), loc.pop("contains", True))
        loc.pop("contains", None)
        if isinstance(loc.get("expected"), str):
            loc["expected"] = [loc["expected"]]
    elif kind == "template":
        template = loc.get("template")
        if template is None and loc.get("path"):
            template = Path(str(loc["path"])).name
        if isinstance(template, str):
            template = [template]
        loc["template"] = template
        loc.pop("path", None)
    elif kind == "point":
        target = loc.get("target")
        if target is None and loc.get("point") is not None:
            target = loc.pop("point")
        if target is not None:
            loc["target"] = list(target) if isinstance(target, (list, tuple)) else target

    for key, types in _FIELD_TYPES.items():
        if key in loc and loc[key] is not None and not isinstance(loc[key], types):
            raise PipelineModelError(
                f"{key} has type {type(loc[key]).__name__}, expected {types}"
            )
    return loc


def recognition_type(locator):
    """Map our locator kind to the MaaFW recognition name (or None)."""
    loc = normalize_locator(locator)
    kind = str(loc.get("type") or "ocr").lower()
    if kind in ("directhit",):
        return "DirectHit"
    if kind == "templatematch":
        return "TemplateMatch"
    if kind == "featurematch":
        return "FeatureMatch"
    if kind == "colormatch":
        return "ColorMatch"
    if kind == "ocr":
        return "OCR"
    if kind == "neuralnetworkclassify":
        return "NeuralNetworkClassify"
    if kind == "neuralnetworkdetect":
        return "NeuralNetworkDetect"
    if kind == "custom":
        return "Custom"
    if kind in ("and", "or"):
        return kind.capitalize()
    # our short names
    return {
        "template": "TemplateMatch",
        "feature": "FeatureMatch",
        "color": "ColorMatch",
        "nn": "NeuralNetworkClassify",
        "point": "DirectHit",
    }.get(kind)


def _copy_recognition_fields(locator, node):
    loc = normalize_locator(locator)
    rtype = recognition_type(loc)
    if not rtype:
        return None
    node["recognition"] = rtype
    for key in (
        "roi", "roi_offset", "expected", "template", "threshold", "method",
        "green_mask", "detector", "ratio", "count", "connected", "index",
        "order_by", "replace", "only_rec", "model", "color_filter", "lower",
        "upper", "labels", "custom_recognition", "custom_recognition_param",
    ):
        if key in loc and loc[key] is not None:
            node[key] = copy.deepcopy(loc[key])
    return rtype


def _copy_node_attrs(step, node):
    """Pass through the MaaFW node attributes the step may carry."""
    for key in (
        "timeout", "pre_delay", "post_delay", "rate_limit", "repeat",
        "repeat_delay", "wait_freezes", "max_hit", "inverse", "enabled",
        "anchor", "attach", "focus", "next", "on_error",
    ):
        if key in step and step[key] is not None:
            node[key] = copy.deepcopy(step[key])


def build_node(step, locator=None):
    """Build a MaaFW node from a step and its resolved locator.

    Returns None when the step is not runnable.
    """
    step = copy.deepcopy(dict(step or {}))
    action = step.get("action")

    # A native pipeline fragment wins: rows can store full MaaFW nodes.
    native = step.get("pipeline") or step.get("node")
    if isinstance(native, dict):
        node = copy.deepcopy(native)
        _copy_node_attrs(step, node)
        return node

    node = {}
    delay_ms = int(float(step.get("delay", 0) or 0) * 1000)
    _copy_node_attrs(step, node)
    node.setdefault("timeout", int(step.get("timeout", 10000)))

    loc = locator if locator is not None else step.get("_locator")
    if loc:
        normalized = normalize_locator(loc)
        rtype = _copy_recognition_fields(normalized, node)
        if rtype is None:
            return None
    else:
        normalized, rtype = {}, None

    if action in ("tap", "click", "long_press"):
        target = step.get("target")
        point = step.get("point")
        if not loc and target:
            node["recognition"] = "OCR"
            node["expected"] = _expected_regex(
                target.get("value", ""), target.get("contains", True)
            )
            rtype = "OCR"
        if not loc and point:
            node["recognition"] = "DirectHit"
            rtype = "DirectHit"
        if not loc and not target and not point:
            return None
        node["action"] = "LongPress" if action == "long_press" else "Click"
        if node.get("recognition") == "DirectHit":
            target_value = normalized.get("target") if normalized else None
            if target_value is None and point:
                target_value = list(point)
            if target_value is not None:
                node["target"] = copy.deepcopy(target_value)
        else:
            node["target"] = step.get("target_value", True)
        if normalized.get("target_offset") is not None:
            node["target_offset"] = copy.deepcopy(normalized["target_offset"])
        if step.get("target_offset") is not None:
            node["target_offset"] = copy.deepcopy(step["target_offset"])
    elif action == "launch":
        node["recognition"] = "DirectHit"
        node["action"] = "StartApp"
        node["package"] = step.get("package", "")
    elif action == "text":
        node["recognition"] = "DirectHit"
        node["action"] = "InputText"
        node["input_text"] = step.get("value", step.get("text", ""))
    elif action == "key":
        key = step.get("key", step.get("value"))
        if key is None:
            return None
        node["recognition"] = "DirectHit"
        node["action"] = "ClickKey"
        node["key"] = key if isinstance(key, list) else [int(key)]
    elif action == "swipe":
        begin = step.get("begin", step.get("from", [0, 0]))
        end = step.get("end", step.get("to", [0, 0]))
        node["recognition"] = "DirectHit"
        node["action"] = "Swipe"
        node["begin"] = list(begin)
        node["end"] = [list(end)]
        node["duration"] = [int(step.get("duration", 300))]
    elif action == "wait":
        node["recognition"] = "DirectHit"
        node["action"] = "DoNothing"
        node["post_delay"] = delay_ms + int(float(step.get("seconds", 1) or 1) * 1000)
    elif action == "custom":
        node["recognition"] = node.get("recognition", "DirectHit")
        node["action"] = "Custom"
        node["custom_action"] = step.get("custom_action", "")
        node["custom_action_param"] = step.get("custom_action_param")
    elif action in ("done", "fail", "ask"):
        return None
    elif rtype:
        # A step with no action but a locator: keep the recognition (useful
        # for assert/verify steps).
        node.setdefault("action", "DoNothing")
    else:
        return None

    if "post_delay" not in node and delay_ms:
        node["post_delay"] = delay_ms
    return node


def validate_pipeline(pipeline):
    """Validate a MaaFW pipeline dict (node name -> node dict)."""
    if not isinstance(pipeline, dict) or not pipeline:
        raise PipelineModelError("pipeline must be a non-empty object")
    for name, node in pipeline.items():
        if not isinstance(name, str) or not name:
            raise PipelineModelError("node names must be non-empty strings")
        if not isinstance(node, dict):
            raise PipelineModelError(f"node {name!r} must be an object")
        recognition = node.get("recognition")
        if recognition and str(recognition).lower() not in _RECOGNITION_TYPES:
            raise PipelineModelError(
                f"node {name!r}: unknown recognition {recognition!r}"
            )
    return pipeline
