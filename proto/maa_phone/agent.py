import json
import time

from . import config, db, executor, uiauto, vision

SYSTEM_PROMPT = """You control an Android phone step by step to reach a user goal.
You get a screenshot and a list of UI nodes. Output exactly ONE JSON action per turn.

Actions:
{"thought":"...","action":"launch","package":"com.example.app"}
{"thought":"...","action":"tap","target":{"match":"text","value":"搜索","contains":true}}
{"thought":"...","action":"tap","point":[360,800]}
{"thought":"...","action":"swipe","from":[360,1600],"to":[360,700],"duration":300}
{"thought":"...","action":"text","value":"114514"}
{"thought":"...","action":"key","value":"enter|back|home"}
{"thought":"...","action":"wait","seconds":1.5}
{"thought":"...","action":"done","summary":"what was accomplished"}
{"thought":"...","action":"fail","reason":"why it cannot be done"}

Rules:
- Prefer semantic targets (text/content-desc) over points; the node list is authoritative.
- point coordinates are in the provided image scale, origin top-left.
- One small step per turn. Wait for loading when needed.
- Check the screenshot after each action; never repeat the same action more than twice.
- When the goal says "take a photo", a single shutter tap is enough, then done.
- Dismiss permission dialogs or popups that block the goal.
- Never tap ads, never buy, never send messages unless the goal says so.
- Call done as soon as the goal is achieved."""


def _clean_history(history, keep=8):
    out = []
    for item in history[-keep:]:
        content = item["content"]
        if isinstance(content, list):
            content = " ".join(
                part.get("text", "") for part in content if part.get("type") == "text"
            )
        out.append({"role": item["role"], "content": content})
    return out


def _record(action, nodes, device_size, vision_size, result, trajectory, current_app="", screenshot=""):
    entry = {"action": action.get("action")}
    if screenshot:
        entry["screenshot"] = screenshot
    if action.get("action") == "launch":
        entry["package"] = action.get("package", "")
    elif action.get("action") == "tap":
        target = action.get("target")
        point = action.get("point")
        if target:
            entry["target"] = {
                "match": target.get("match", "text"),
                "value": target.get("value", ""),
                "contains": target.get("contains", True),
            }
        elif point:
            scale = device_size[0] / float(vision_size[0])
            node = uiauto.node_at(nodes, point[0] * scale, point[1] * scale)
            same_app = not current_app or not node or node["package"] == current_app
            if node and same_app and (node["text"] or node["desc"]) and node["clickable"]:
                entry["target"] = {
                    "match": "text" if node["text"] else "desc",
                    "value": node["text"] or node["desc"],
                    "contains": True,
                }
            else:
                entry["point"] = [int(point[0] * scale), int(point[1] * scale)]
    elif action.get("action") == "swipe":
        entry["from"] = action.get("from")
        entry["to"] = action.get("to")
        entry["duration"] = action.get("duration", 300)
    elif action.get("action") in ("text", "key"):
        entry["value"] = action.get("value", "")
    elif action.get("action") == "wait":
        entry["seconds"] = action.get("seconds", 1)
    if action.get("action") in ("tap", "swipe", "text", "key"):
        entry["delay"] = 2.0
    entry["result"] = result
    trajectory.append(entry)


def run_agent(
    dev,
    con,
    goal,
    app_id=None,
    max_steps=None,
    pause_after=None,
    resume_state=None,
    on_step=None,
):
    max_steps = max_steps or int(db.setting(con, "agent_max_steps", "15"))
    client = vision.DeepSeek()
    trajectory = []
    history = []
    tokens = 0
    start_step = 0
    run_id = None
    if resume_state:
        run_id = resume_state["run_id"]
        trajectory = resume_state.get("trajectory", [])
        history = resume_state.get("history", [])
        tokens = resume_state.get("tokens", 0)
        start_step = resume_state.get("step", 0)
        con.execute("UPDATE runs SET state='running' WHERE id=?", (run_id,))
        con.commit()
    else:
        run_id = db.start_run(con, goal, app_id=app_id, path="bootstrap", source="in_app")

    started = time.time()
    success = False
    error = ""
    step = start_step
    while step < max_steps:
        if pause_after is not None and (step - start_step) >= pause_after:
            progress = {
                "step": step,
                "trajectory": trajectory,
                "history": _clean_history(history, keep=12),
                "tokens": tokens,
            }
            con.execute(
                "UPDATE runs SET state='paused', progress_json=?, steps_json=?, ai_cost=? "
                "WHERE id=?",
                (
                    json.dumps(progress, ensure_ascii=False),
                    json.dumps(trajectory, ensure_ascii=False),
                    tokens,
                    run_id,
                ),
            )
            con.commit()
            return run_id, "paused"
        try:
            image = dev.screencap()
            nodes = dev.uiauto()
        except Exception as exc:
            error = f"device error: {exc}"
            break
        shot_rel = ""
        try:
            config.SHOTS.mkdir(parents=True, exist_ok=True)
            shot_path = config.SHOTS / f"run{run_id}_step{step}.png"
            image.save(shot_path)
            shot_rel = str(shot_path.relative_to(config.ROOT))
        except Exception:
            shot_rel = ""
        data_url, vision_size = vision.DeepSeek.image_data_url(image)
        current_app = dev.current_app()
        node_text = uiauto.summarize(nodes) or "(no UI nodes available, use the screenshot and point taps)"
        user_text = (
            f"Goal: {goal}\nCurrent app: {current_app}\n"
            f"Image size: {vision_size[0]}x{vision_size[1]} (point scale)\n"
            f"UI nodes:\n{node_text}"
        )
        messages = (
            [{"role": "system", "content": SYSTEM_PROMPT}]
            + _clean_history(history)
            + [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_text},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                }
            ]
        )
        max_tokens = int(db.setting(con, "agent_max_tokens", "4000"))
        try:
            raw = client.chat(messages, json_mode=True, max_tokens=max_tokens)
        except Exception as exc:
            error = f"model error: {exc}"
            break
        tokens += client.last_usage.get("total_tokens", 0)
        try:
            action = json.loads(raw)
        except json.JSONDecodeError:
            retry_messages = messages + [
                {"role": "assistant", "content": raw or "(empty)"},
                {
                    "role": "user",
                    "content": "Your reply was not one valid JSON action object. "
                    "Reply with exactly one JSON object, nothing else.",
                },
            ]
            try:
                raw = client.chat(retry_messages, json_mode=True, max_tokens=max_tokens)
                tokens += client.last_usage.get("total_tokens", 0)
                action = json.loads(raw)
            except Exception:
                action = {"action": "fail", "reason": "invalid JSON from model"}
        history.append({"role": "assistant", "content": raw})
        act = action.get("action")
        if act == "done":
            success = True
            error = ""
            break
        if act == "fail":
            error = action.get("reason", "agent failed")
            break
        try:
            if act == "tap" and action.get("point") and shot_rel:
                image = dev.screencap()
                image.save(config.ROOT / shot_rel)
            result = executor.execute_step(
                dev, action, nodes=nodes, scale=dev.size()[0] / float(vision_size[0])
            )
        except Exception as exc:
            result = f"error: {exc}"
        _record(action, nodes, dev.size(), vision_size, result, trajectory, current_app, shot_rel)
        history.append({"role": "user", "content": f"Result: {result}"})
        if on_step:
            on_step(step, action)
        step += 1
        time.sleep(0.6)
    else:
        error = error or f"max steps ({max_steps}) reached"

    duration = int((time.time() - started) * 1000)
    db.finish_run(
        con,
        run_id,
        success,
        steps=trajectory,
        ai_cost=tokens,
        duration_ms=duration,
        error=error,
    )
    return run_id, "done" if success else "failed"
