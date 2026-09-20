import json
import re
from pathlib import Path

from . import config, pipeline_model
from .device import KEYCODES


def compile_steps(con, pipeline):
    steps = json.loads(pipeline["definition_json"] or "[]")
    if isinstance(steps, dict):
        steps = [steps]
    elements = {}
    if pipeline["app_id"]:
        rows = con.execute(
            "SELECT * FROM elements WHERE app_id=?", (pipeline["app_id"],)
        ).fetchall()
        for row in rows:
            elements[row["name"]] = json.loads(row["locator_json"] or "{}")
    compiled = []
    for step in steps:
        step = dict(step)
        if step.get("element"):
            step["_locator"] = elements.get(step["element"], {})
        compiled.append(step)
    return compiled


def _ocr_expected(locator):
    value = locator.get("value", "")
    if locator.get("contains", True):
        return [re.escape(value)]
    return [f"^{re.escape(value)}$"]


def _template_name(locator):
    if locator.get("template"):
        return locator["template"]
    path = locator.get("path")
    return Path(path).name if path else None


def compile_maa_steps(con, pipeline):
    """Compile DB rows into MaaFramework nodes: one node per pause boundary.

    Locator fields are stored 1:1 in MaaFW protocol names (see
    ``pipeline_model``); this function only resolves element references,
    normalizes legacy shorthand and validates.
    """
    nodes = []
    for index, original in enumerate(compile_steps(con, pipeline)):
        step = dict(original)
        if step.get("action") == "key" and not isinstance(step.get("key"), (list, int)):
            code = KEYCODES.get(str(step.get("value")))
            if code is None:
                continue
            step["key"] = code
        node = pipeline_model.build_node(step, step.get("_locator"))
        if not node:
            continue
        template = None
        if node.get("recognition") == "TemplateMatch":
            value = node.get("template")
            if isinstance(value, (list, tuple)) and value:
                template = str(value[0])
            elif isinstance(value, str):
                template = value
        nodes.append(
            {
                "name": f"pipeline{pipeline['id']}_s{index}",
                "index": index,
                "node": node,
                "template": template,
                "source": step,
            }
        )
    return nodes
