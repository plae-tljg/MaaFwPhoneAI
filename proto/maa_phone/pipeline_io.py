"""Bridge between standard MaaFramework pipeline JSON and DB rows.

Import: take a pipeline authored by MaaMCP / an AI / a maintainer and file
it as a ``pipeline_new`` proposal (human Review still gates it). Export: emit a
live pipeline as standard pipeline JSON for MaaMCP to run, benchmark, debug or
share. Field names are passed through unchanged (see docs/INTEGRATION.md).
"""

import json
import shutil
from pathlib import Path

from . import compiler, config, db, learn, pipeline_model, pipelines

_RECOGNITION_KEYS = (
    "roi", "roi_offset", "expected", "template", "threshold", "method",
    "green_mask", "detector", "ratio", "count", "connected", "index",
    "order_by", "replace", "only_rec", "model", "color_filter", "lower",
    "upper", "labels", "custom_recognition", "custom_recognition_param",
)
_ACTION_KEYS = ("target", "target_offset", "duration", "begin", "end",
                "package", "input_text", "key", "custom_action",
                "custom_action_param")


def _slug(value, fallback):
    value = str(value or "").strip()
    if not value:
        return fallback
    ascii_slug = "".join(ch if ch.isalnum() else "_" for ch in value).strip("_")
    return (ascii_slug or fallback)[:40]


def _element_for_node(node_name, node, index, used):
    recognition = str(node.get("recognition") or "DirectHit")
    locator = {"type": {
        "OCR": "ocr",
        "TemplateMatch": "template",
        "FeatureMatch": "feature",
        "ColorMatch": "color",
        "DirectHit": "point",
        "Custom": "custom",
    }.get(recognition, recognition.lower())}
    for key in _RECOGNITION_KEYS:
        if key in node and node[key] is not None:
            locator[key] = node[key]
    if "custom_recognition_param" not in locator and node.get("custom_recognition_param") is not None:
        locator["custom_recognition_param"] = node["custom_recognition_param"]
    if recognition == "DirectHit" and "roi" not in locator and "target" in node:
        target = node["target"]
        if isinstance(target, (list, tuple)) and len(target) == 2:
            locator["target"] = list(target)
    # Keep the pipeline node name when possible: it is what a maintainer and
    # MaaMCP see, and what element healing/manual fixes will reference.
    fallback = f"node_{index}"
    if locator.get("expected"):
        expected = locator["expected"]
        text = expected[0] if isinstance(expected, (list, tuple)) and expected else expected
        fallback = _slug(text, fallback)
    elif locator.get("template"):
        template = locator["template"]
        stem = template[0] if isinstance(template, (list, tuple)) and template else template
        fallback = _slug(Path(str(stem)).stem, fallback)
    base = _slug(node_name, fallback)
    name = base
    suffix = 1
    while name in used:
        suffix += 1
        name = f"{base}_{suffix}"
    used.add(name)
    return name, locator


def _step_for_node(node, element_name, source):
    """Turn a MaaFW node action into a DB step."""
    action = node.get("action") or "DoNothing"
    step = {"action": "tap", "element": element_name}
    if action == "Click":
        pass
    elif action == "LongPress":
        step["action"] = "long_press"
    elif action == "Swipe":
        step.update({
            "action": "swipe",
            "from": node.get("begin", [0, 0]),
            "to": (node.get("end") or [[0, 0]])[0],
            "duration": (node.get("duration") or [300])[0],
        })
        step.pop("element", None)
    elif action == "InputText":
        step.update({"action": "text", "value": node.get("input_text", "")})
        step.pop("element", None)
    elif action == "ClickKey":
        key = node.get("key")
        step.update({"action": "key", "value": key[0] if isinstance(key, list) and key else key})
        step.pop("element", None)
    elif action in ("StartApp", "StopApp"):
        step.update({"action": "launch", "package": node.get("package", "")})
        step.pop("element", None)
    elif action == "DoNothing":
        step.update({"action": "wait", "seconds": float(source.get("post_delay", 0) or 0) / 1000.0})
        step.pop("element", None)
    elif action == "Custom":
        step.update({
            "action": "custom",
            "custom_action": node.get("custom_action", ""),
            "custom_action_param": node.get("custom_action_param"),
        })
        step.pop("element", None)
    else:
        step["action"] = "custom"
        step["custom_action"] = f"maafw.{action}"
        step["custom_action_param"] = {k: node[k] for k in _ACTION_KEYS if k in node}

    for key in ("timeout", "post_delay", "pre_delay", "rate_limit", "repeat",
                "repeat_delay", "wait_freezes", "max_hit", "inverse", "enabled",
                "anchor", "target", "target_offset"):
        if key in source and source[key] is not None:
            if key == "post_delay":
                step["delay"] = float(source["post_delay"]) / 1000.0
            else:
                step[key] = source[key]
    if node.get("target") is not None and step.get("action") in ("tap", "long_press"):
        step["target_value"] = node["target"]
    if node.get("target_offset") is not None:
        step["target_offset"] = node["target_offset"]
    return step


def _ordered_nodes(pipeline, entry=None):
    validate = pipeline_model.validate_pipeline(pipeline)
    names = list(pipeline.keys())
    if entry is None:
        referenced = set()
        for node in pipeline.values():
            for nxt in node.get("next") or []:
                referenced.add(str(nxt))
        entry = next((name for name in names if name not in referenced), names[0])
    order, seen = [], set()

    def visit(name):
        if name in seen or name not in pipeline:
            return
        seen.add(name)
        order.append(name)
        for nxt in pipeline[name].get("next") or []:
            visit(str(nxt))

    visit(entry)
    for name in names:
        visit(name)
    return order


def import_pipeline(con, pipeline, goal, app_id=None, entry=None):
    """File a MaaFW pipeline as a pending ``pipeline_new`` proposal.

    ``pipeline`` may be a dict, a JSON string or a path to a JSON file.
    """
    if isinstance(pipeline, (str, Path)):
        text = Path(pipeline).read_text(encoding="utf-8")
        pipeline = json.loads(text)
    if not isinstance(pipeline, dict):
        raise pipeline_model.PipelineModelError("pipeline must be an object")
    pipeline_model.validate_pipeline(pipeline)

    steps, elements, used = [], {}, set()
    for index, name in enumerate(_ordered_nodes(pipeline, entry=entry)):
        node = pipeline[name]
        recognition = str(node.get("recognition") or "DirectHit")
        # Skip pure wrapper/entry nodes (DirectHit + DoNothing + next).
        if (
            recognition == "DirectHit"
            and (node.get("action") or "DoNothing") == "DoNothing"
            and node.get("next")
            and not node.get("target")
            and not node.get("roi")
        ):
            continue
        element_name, locator = _element_for_node(name, node, index, used)
        elements[element_name] = locator
        if locator.get("template"):
            _copy_templates(locator)
        steps.append(_step_for_node(node, element_name, node))

    if not steps:
        raise pipeline_model.PipelineModelError("pipeline has no runnable nodes")
    name = _slug(goal, "imported_pipeline")
    pipeline_row = pipelines.ensure_pipeline(
        con, goal, app_id=app_id, name=name, source="import", aliases=[goal]
    )
    version_id = pipelines.add_candidate(
        con,
        pipeline_row["id"],
        steps,
        postcondition={},
        source="import",
        evidence={"imported_from": "maafw_pipeline"},
    )
    candidate = {
        "pipeline": {
            "id": pipeline_row["id"],
            "version_id": version_id,
            "name": name,
            "goal": goal,
            "aliases": [goal],
            "app_id": app_id,
            "steps": steps,
            "postcondition": {},
            "imported_from": "maafw_pipeline",
        },
        "elements": elements,
    }
    return db.add_proposal(
        con,
        "pipeline_new",
        candidate,
        target_pipeline_id=pipeline_row["id"],
        target_version_id=version_id,
    )


def _copy_templates(locator):
    templates = locator.get("template")
    if isinstance(templates, str):
        templates = [templates]
    for item in templates or []:
        src = Path(str(item))
        if not src.is_absolute():
            src = config.ROOT / src
        if not src.exists():
            continue
        config.TEMPLATES.mkdir(parents=True, exist_ok=True)
        config.BUNDLE_IMAGE.mkdir(parents=True, exist_ok=True)
        target = config.TEMPLATES / src.name
        if not target.exists():
            shutil.copyfile(src, target)
        bundle_target = config.BUNDLE_IMAGE / src.name
        if not bundle_target.exists():
            shutil.copyfile(src, bundle_target)


def export_pipeline(con, pipeline):
    """Emit a live pipeline as a standard MaaFW pipeline dict (linear chain)."""
    items = compiler.compile_maa_steps(con, pipeline)
    if not items:
        return {}
    pipeline = {}
    for position, item in enumerate(items):
        node = dict(item["node"])
        if position + 1 < len(items):
            node["next"] = [items[position + 1]["name"]]
        else:
            node["next"] = []
        pipeline[item["name"]] = node
    return pipeline


def save_pipeline(con, pipeline, out_path):
    pipeline = export_pipeline(con, pipeline)
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(pipeline, ensure_ascii=False, indent=2), encoding="utf-8")
    return out_path
