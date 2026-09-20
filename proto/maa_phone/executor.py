import time
from pathlib import Path

from . import config, matching, uiauto


class StepError(RuntimeError):
    pass


def _wait_for(fn, timeout=6.0, interval=0.8):
    deadline = time.time() + timeout
    while True:
        hit = fn()
        if hit is not None:
            return hit
        if time.time() >= deadline:
            return None
        time.sleep(interval)


def find_target(dev, nodes, target):
    return uiauto.find(
        nodes,
        match=target.get("match", "text"),
        value=target.get("value", ""),
        contains=target.get("contains", True),
        index=target.get("index", 0),
    )


def execute_step(dev, step, nodes=None, scale=1.0, screenshot_dir=None, run_id=0, index=0):
    action = step.get("action")
    if action == "launch":
        dev.launch(step["package"])
        time.sleep(step.get("wait", 1.5))
        return f"launched {step['package']}"
    if action == "tap":
        locator = step.get("_locator")
        if locator:
            if locator.get("type") == "point":
                x, y = locator["point"]
                dev.tap(x, y)
                return f"tapped point {x},{y}"
            if locator.get("type") == "template":
                path = locator["path"]
                full = Path(path)
                if not full.is_absolute():
                    full = config.ROOT / path if str(path).startswith("data/") else config.BUNDLE_IMAGE / path
                hit = _wait_for(
                    lambda: matching.find_template(
                        dev.screencap(), str(full), locator.get("threshold", 0.75)
                    ),
                    timeout=locator.get("timeout", 8),
                )
                if not hit:
                    raise StepError(f"template not found: {path}")
                dx, dy = locator.get("offset", [0, 0])
                dev.tap(hit["center"][0] + dx, hit["center"][1] + dy)
                return f"tapped template {path} score={hit['score']:.2f} at {hit['center']}"
            target = {
                "match": locator.get("match", "text"),
                "value": locator.get("value", ""),
                "contains": locator.get("contains", True),
                "index": locator.get("index", 0),
            }
            node = _wait_for(lambda: find_target(dev, dev.uiauto(), target))
            if node is None:
                raise StepError(f"element not found: {step.get('element')} {target}")
            dev.tap(*node["center"])
            return f"tapped {step.get('element')} at {node['center']}"
        target = step.get("target")
        if target:
            node = _wait_for(lambda: find_target(dev, dev.uiauto(), target))
            if node is None:
                raise StepError(f"target not found: {target}")
            dev.tap(*node["center"])
            return f"tapped {target.get('value')!r} at {node['center']}"
        if step.get("point"):
            x, y = step["point"]
            dev.tap(x * scale, y * scale)
            return f"tapped point {x},{y}"
        raise StepError("tap without target")
    if action == "text":
        dev.text(step["value"])
        return f"typed {step['value']!r}"
    if action == "key":
        dev.key(step["value"])
        return f"key {step['value']}"
    if action == "swipe":
        dev.swipe(
            step["from"][0] * scale,
            step["from"][1] * scale,
            step["to"][0] * scale,
            step["to"][1] * scale,
            step.get("duration", 300),
        )
        return "swiped"
    if action == "wait":
        time.sleep(step.get("seconds", 1))
        return f"waited {step.get('seconds', 1)}s"
    if action == "screenshot":
        if screenshot_dir:
            path = screenshot_dir / f"run{run_id}_{index}.png"
            dev.screencap().save(path)
            return f"saved {path}"
        return "screenshot skipped"
    if action == "done":
        return "done"
    raise StepError(f"unknown action: {action}")


def run_steps(dev, con, run_id, steps, pause_after=None, start_index=0, on_step=None):
    import json

    index = start_index
    while index < len(steps):
        if pause_after is not None and index >= pause_after:
            con.execute(
                "UPDATE runs SET state='paused', progress_json=? WHERE id=?",
                (json.dumps({"step_index": index}), run_id),
            )
            con.commit()
            return "paused", index
        step = steps[index]
        execute_step(dev, step)
        if on_step:
            on_step(index, step)
        index += 1
        time.sleep(float(step.get("delay", 1.2)))
    return "done", index
