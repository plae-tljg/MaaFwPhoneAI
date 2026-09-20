import hashlib
import json
import time

from . import config, db, debug, executor, learn, maa_exec, verifiers, vision

VALID_ACTIONS = {"launch", "tap", "swipe", "text", "key", "wait", "ask", "done", "fail"}

ACTION_TOOL = {
    "type": "function",
    "function": {
        "name": "phone_action",
        "description": "Choose exactly one next action on the phone to reach the goal.",
        "parameters": {
            "type": "object",
            "properties": {
                "thought": {"type": "string", "description": "brief reason for this action"},
                "action": {
                    "type": "string",
                    "enum": ["launch", "tap", "swipe", "text", "key", "wait", "ask", "done", "fail"],
                },
                "package": {"type": "string", "description": "for launch"},
                "target": {
                    "type": "object",
                    "description": "for tap; value is OCR text",
                    "properties": {
                        "match": {"type": "string"},
                        "value": {"type": "string"},
                        "contains": {"type": "boolean"},
                    },
                },
                "point": {
                    "type": "array",
                    "items": {"type": "integer"},
                    "minItems": 2,
                    "maxItems": 2,
                    "description": "for tap; image-scale coordinates",
                },
                "from": {"type": "array", "items": {"type": "integer"}, "minItems": 2, "maxItems": 2},
                "to": {"type": "array", "items": {"type": "integer"}, "minItems": 2, "maxItems": 2},
                "duration": {"type": "integer", "description": "swipe duration ms"},
                "value": {"type": "string", "description": "for text or key"},
                "seconds": {"type": "number", "description": "for wait"},
                "summary": {"type": "string", "description": "for done"},
                "reason": {"type": "string", "description": "for fail"},
                "question": {"type": "string", "description": "for ask: what to ask the user"},
                "options": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "for ask: optional suggested answers",
                },
            },
            "required": ["action", "thought"],
        },
    },
}

SYSTEM_PROMPT = """You control an Android phone step by step to reach a user goal.
You get a screenshot and a list of text elements detected by OCR. Call the phone_action function with exactly ONE action per turn.
The JSON forms below describe each action's fields.

Actions:
{"thought":"...","action":"launch","package":"com.example.app"}
{"thought":"...","action":"tap","target":{"match":"text","value":"搜索","contains":true}}
{"thought":"...","action":"tap","point":[360,800]}
{"thought":"...","action":"swipe","from":[360,1600],"to":[360,700],"duration":300}
{"thought":"...","action":"text","value":"114514"}
{"thought":"...","action":"key","value":"enter|back|home"}
{"thought":"...","action":"wait","seconds":1.5}
{"thought":"...","action":"ask","question":"Which app?","options":["WhatsApp","Messages"]}
{"thought":"...","action":"done","summary":"what was accomplished"}
{"thought":"...","action":"fail","reason":"why it cannot be done"}

Rules:
- To open an app, prefer {"action":"launch","package":"..."} using the known apps list.
- Prefer a target whose text appears in the OCR list; use point only when no text matches.
- All coordinates, including the OCR centers above, are in the provided image scale.
- The camera shutter has no text: it is the large white circle at the bottom center.
- The camera screen does not change after the shutter; after one shutter tap, call done.
- One small step per turn. Wait for loading when needed.
- Check the screenshot after each action; never repeat the same action more than twice.
- Dismiss permission dialogs or popups that block the goal.
- Camera and video surfaces may look black in screenshots even though the app works;
  do not assume failure from a black preview. If you already performed the decisive
  action and cannot verify visually, call done.
- Never tap ads, never buy, never send messages unless the goal says so.
- Before calling done, the latest screenshot must show the goal's final state.
  For toggle-like actions (like/follow/switch), FIRST check the current state:
  if it is already in the desired state, do NOT tap it; just call done.
  If you do tap, confirm the new state visually before calling done.
- Never tap a toggle twice: a second tap undoes the first one.
- Call done as soon as the goal is achieved and verified."""


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


def _nodes_text(items, scale=1.0):
    lines = []
    for i, it in enumerate(items[:60]):
        x, y, w, h = it["box"]
        cx = round((x + w / 2) / scale)
        cy = round((y + h / 2) / scale)
        lines.append(f"#{i} text={it['text']!r} center=({cx},{cy})")
    return "\n".join(lines) or "(no text detected; use the screenshot and point taps)"


def _trajectory_for_prompt(trajectory, scale=1.0):
    out = []
    for entry in trajectory[-3:]:
        item = dict(entry)
        item.pop("screenshot", None)
        if item.get("point") and scale != 1.0:
            item["point"] = [round(item["point"][0] / scale), round(item["point"][1] / scale)]
        out.append(item)
    return out


def _repeat_note(trajectory):
    if not trajectory:
        return ""

    def kind(entry):
        action = entry.get("action")
        if action == "tap":
            if entry.get("target"):
                return ("tap_target", entry["target"].get("value", ""))
            point = entry.get("point") or []
            if len(point) == 2:
                return ("tap_region", (point[0], point[1]))
            return ("tap",)
        return (action, entry.get("value") or entry.get("package") or ())

    kinds = [kind(entry) for entry in trajectory]

    def similar(a, b):
        if a[0] != b[0]:
            return False
        if a[0] == "tap_region":
            (x1, y1), (x2, y2) = a[1], b[1]
            return max(abs(x1 - x2), abs(y1 - y2)) <= 60
        return a == b

    last = kinds[-1]
    count = 0
    for value in reversed(kinds):
        if not similar(value, last):
            break
        count += 1
    if count < 2:
        return ""
    return (
        f"STOP: you have repeated the same action {count} times and the screen did not change. "
        "Do not repeat it again. If the action likely succeeded (for example the shutter was "
        "tapped), you MUST call done now. Otherwise try a different action or call fail."
    )


def _execute(exec_, action, items, scale=1.0):
    act = action.get("action")
    if act == "launch":
        exec_.start_app(action.get("package", ""))
        time.sleep(action.get("wait", 1.5))
        return f"launched {action.get('package')}"
    if act == "tap":
        target = action.get("target")
        point = action.get("point")
        if target:
            value = target.get("value", "")
            contains = target.get("contains", True)
            for it in items:
                hit = value in it["text"] if contains else value == it["text"]
                if value and hit:
                    x, y, w, h = it["box"]
                    exec_.click(x + w // 2, y + h // 2)
                    return f"tapped {value!r} (image center {round((x + w / 2) / scale)},{round((y + h / 2) / scale)})"
            raise RuntimeError(f"text not found: {value!r}")
        if point:
            x, y = int(point[0] * scale), int(point[1] * scale)
            exec_.click(x, y)
            return f"tapped point {point[0]},{point[1]} (image scale)"
        raise RuntimeError("tap without target or point")
    if act == "text":
        exec_.input_text(action.get("value", ""))
        return f"typed {action.get('value')!r}"
    if act == "key":
        code = executor.KEYCODES.get(str(action.get("value")))
        if code is None:
            raise RuntimeError(f"unknown key: {action.get('value')}")
        exec_.click_key(code)
        return f"key {action.get('value')}"
    if act == "swipe":
        begin = action.get("from", [0, 0])
        end = action.get("to", [0, 0])
        exec_.swipe(begin[0], begin[1], end[0], end[1], action.get("duration", 300))
        return "swiped"
    if act == "wait":
        time.sleep(action.get("seconds", 1))
        return f"waited {action.get('seconds', 1)}s"
    raise RuntimeError(f"unknown action: {act}")


def _record(action, items, result, trajectory, shot_rel="", scale=1.0):
    entry = {"action": action.get("action")}
    if action.get("action") == "launch":
        entry["package"] = action.get("package", "")
    elif action.get("action") == "tap":
        target = action.get("target")
        point = action.get("point")
        if target:
            entry["target"] = {
                "match": "text",
                "value": target.get("value", ""),
                "contains": target.get("contains", True),
            }
        elif point:
            x, y = int(point[0] * scale), int(point[1] * scale)
            node = None
            for it in items:
                bx, by, bw, bh = it["box"]
                cx, cy = bx + bw / 2, by + bh / 2
                centred = abs(cx - x) <= 24 and abs(cy - y) <= 24
                # A point tap is only "really" a text tap when the model hit the
                # centre of a reasonably sized text box. Otherwise keep the
                # point + screenshot so learning can make a template crop.
                if (
                    bx <= x <= bx + bw
                    and by <= y <= by + bh
                    and centred
                    and len(it["text"]) >= 2
                    and bw <= 420
                    and bh <= 160
                ):
                    node = it
                    break
            if node and node["text"]:
                entry["target"] = {"match": "text", "value": node["text"], "contains": True}
            else:
                entry["point"] = [x, y]
                if shot_rel:
                    entry["screenshot"] = shot_rel
    elif action.get("action") == "swipe":
        entry["from"] = action.get("from")
        entry["to"] = action.get("to")
        entry["duration"] = action.get("duration", 300)
    elif action.get("action") in ("text", "key"):
        entry["value"] = action.get("value", "")
    elif action.get("action") == "wait":
        entry["seconds"] = action.get("seconds", 1)
    if action.get("action") in ("tap", "swipe", "text", "key", "launch"):
        entry["delay"] = 2.0
    entry["result"] = result
    trajectory.append(entry)


def _parse_action(text):
    """Parse a JSON action, tolerating code fences / surrounding prose."""
    if not text:
        return None
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text[:4].lower() == "json":
            text = text[4:]
    candidates = [text]
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        candidates.append(text[start : end + 1])
    for candidate in candidates:
        try:
            data = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(data, dict) and data.get("action") in VALID_ACTIONS:
            return data
    return None


def _action_from_message(message):
    """Prefer native tool_calls; fall back to JSON content. Returns (action, text)."""
    content = (message.get("content") or "").strip()
    for call in message.get("tool_calls") or []:
        try:
            arguments = call.get("function", {}).get("arguments") or "{}"
            data = json.loads(arguments)
        except (json.JSONDecodeError, AttributeError, TypeError):
            continue
        if isinstance(data, dict) and data.get("action") in VALID_ACTIONS:
            return data, content or json.dumps(data, ensure_ascii=False)
    return _parse_action(content), content


def resume_state_from_run(run, answer=None):
    """Build the state ``run_agent`` needs to continue a needs_input run."""
    progress = json.loads(run["progress_json"] or "{}")
    history = list(progress.get("history") or [])
    if answer is not None:
        history.append({"role": "user", "content": f"Answer: {answer}"})
    return {
        "run_id": run["id"],
        "step": int(progress.get("step", 0)),
        "trajectory": progress.get("trajectory", []),
        "history": history,
        "tokens": int(progress.get("tokens", 0)),
    }


def run_agent(con, goal, app_id=None, max_steps=None, pause_after=None, resume_state=None,
              postcondition=None, log=print):
    max_steps = max_steps or int(db.setting(con, "agent_max_steps", "15"))
    exec_ = maa_exec.MaaExec()
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
        if app_id is None:
            app_id = db.ensure_app(con, exec_.current_app() or "unknown")
        run_id = db.start_run(con, goal, app_id=app_id, path="bootstrap", engine="maa")
    bundle = debug.bundle_for(con, run_id)
    exec_.set_log_dir(bundle.dir / "maa")
    level = debug.debug_level(con)
    exec_.set_debug(
        save_draw=(level == "full"),
        debug_mode=(level == "full"),
        save_on_error=True,
    )
    bundle.manifest(
        {
            "kind": "bootstrap",
            "goal": goal,
            "engine": "maa",
            "debug_level": level,
            "device": exec_.device_name,
        }
    )
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
                "UPDATE runs SET state='paused', progress_json=?, steps_json=?, ai_cost=? WHERE id=?",
                (
                    json.dumps(progress, ensure_ascii=False),
                    json.dumps(trajectory, ensure_ascii=False),
                    tokens,
                    run_id,
                ),
            )
            con.commit()
            bundle.event({"type": "pause", "step": step})
            return run_id, "paused"
        try:
            image = exec_.image()
            items = exec_.ocr_texts(image=image)
        except Exception as exc:
            error = f"device error: {exc}"
            break
        shot_rel = ""
        if level in ("screens", "full"):
            rel = bundle.screenshot(f"turn_{step}.png", image)
            shot_rel = str((bundle.dir / rel).relative_to(config.ROOT))
        data_url, vision_size = vision.DeepSeek.image_data_url(image)
        scale = image.width / float(vision_size[0])
        current_app = exec_.current_app()
        user_text = (
            f"Goal: {goal}\nCurrent app: {current_app}\n"
            f"Image size: {vision_size[0]}x{vision_size[1]} (all coordinates use this scale)\n"
            f"OCR text elements:\n{_nodes_text(items, scale)}\n"
            f"Recent actions:\n{json.dumps(_trajectory_for_prompt(trajectory, scale), ensure_ascii=False)[:600]}\n"
            f"{_repeat_note(trajectory)}"
        )
        hint = db.setting(con, f"hint:{current_app}", "")
        if hint:
            user_text += f"\nApp hint: {hint}"
        known = con.execute(
            "SELECT name, package_name FROM apps WHERE active=1"
        ).fetchall()
        if known:
            listing = "; ".join(f"{row['name']} -> {row['package_name']}" for row in known)
            user_text += f"\nKnown apps (prefer launch): {listing}"
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
        # Cost control: keyed by goal + app + perceptual screenshot hash. The
        # same unknown screen+goal is paid for once (answer_cache analogue).
        phash = vision.dhash(image)
        prompt_hash = hashlib.sha1(user_text.encode("utf-8")).hexdigest()
        cache_key = hashlib.sha1(
            f"{client.model}|{goal}|{current_app}|{phash}".encode("utf-8")
        ).hexdigest()
        action, raw = None, ""
        cached = db.cache_get(con, cache_key)
        if cached:
            try:
                action = json.loads(cached["action_json"] or "{}")
                raw = json.dumps(action, ensure_ascii=False)
                bundle.event(
                    {"type": "cache_hit", "step": step, "key": cache_key, "phash": phash}
                )
                log(f"  ai step {step}: cache hit {raw[:90]}")
            except Exception:
                action = None
        if action is None:
            max_tokens = int(db.setting(con, "agent_max_tokens", "6000"))
            action, raw = None, ""
            # Native tool calling first: JSON mode occasionally leaks the model's
            # internal DSML tool syntax into content (seen with deepseek thinking
            # models), which is unparseable.
            try:
                message = client.chat_message(
                    messages, json_mode=False, max_tokens=max_tokens, tools=[ACTION_TOOL]
                )
                tokens += client.last_usage.get("total_tokens", 0)
                action, raw = _action_from_message(message)
            except Exception:
                action = None
            if action is None:
                # Fallback: some providers/proxies reject tools or return content JSON.
                try:
                    raw = client.chat(messages, json_mode=True, max_tokens=max_tokens)
                    tokens += client.last_usage.get("total_tokens", 0)
                    action = _parse_action(raw)
                except Exception as exc:
                    error = f"model error: {exc}"
                    break
            if action is None:
                retry = messages + [
                    {"role": "assistant", "content": raw or "(empty)"},
                    {
                        "role": "user",
                        "content": "Your reply was not one valid action. Call phone_action "
                        "(or reply with exactly one JSON action object), nothing else.",
                    },
                ]
                try:
                    message = client.chat_message(
                        retry, json_mode=False, max_tokens=max_tokens, tools=[ACTION_TOOL]
                    )
                    tokens += client.last_usage.get("total_tokens", 0)
                    action, raw2 = _action_from_message(message)
                    if action is not None:
                        raw = raw2 or raw
                except Exception:
                    pass
        if action is None:
            error = "invalid action from model"
            bundle.event({"type": "fail", "step": step, "reason": error})
            break
        if action.get("action") not in ("ask",):
            db.cache_put(con, cache_key, goal, current_app, phash, prompt_hash, action)
        history.append({"role": "assistant", "content": raw or json.dumps(action, ensure_ascii=False)})
        act = action.get("action")
        if act == "ask":
            question = str(action.get("question") or "").strip() or "Please provide more information."
            options = [str(o) for o in (action.get("options") or [])]
            progress = {
                "step": step,
                "trajectory": trajectory,
                "history": _clean_history(history, keep=12),
                "tokens": tokens,
                "question": question,
                "options": options,
            }
            con.execute(
                "UPDATE runs SET state='needs_input', progress_json=?, steps_json=?, ai_cost=? WHERE id=?",
                (
                    json.dumps(progress, ensure_ascii=False),
                    json.dumps(trajectory, ensure_ascii=False),
                    tokens,
                    run_id,
                ),
            )
            con.commit()
            db.add_message(
                con, "assistant", "question", question, run_id=run_id,
                payload={"options": options},
            )
            bundle.event({"type": "question", "step": step, "question": question, "options": options})
            log(f"  question: {question}")
            return run_id, "needs_input"
        if act == "done":
            success = True
            error = ""
            bundle.event({"type": "done", "step": step, "summary": action.get("summary", "")})
            break
        if act == "fail":
            error = action.get("reason", "agent failed")
            bundle.event({"type": "fail", "step": step, "reason": error})
            break
        try:
            result = _execute(exec_, action, items, scale)
        except Exception as exc:
            result = f"error: {exc}"
        _record(action, items, result, trajectory, shot_rel, scale)
        event = {
            "type": "ai_step",
            "step": step,
            "action": act,
            "result": result,
            "thought": action.get("thought", "")[:200],
        }
        if shot_rel:
            event["screenshot"] = shot_rel
        if level == "full":
            event["ocr"] = items[:30]
        bundle.event(event)
        log(f"  ai step {step}: {act} -> {result[:100]}")
        history.append({"role": "user", "content": f"Result: {result}"})
        step += 1
        time.sleep(0.6)
    else:
        error = error or f"max steps ({max_steps}) reached"

    verified, verify_evidence = None, {"type": "none", "skipped": True}
    if success and (postcondition or {}).get("type") not in (None, "", "none"):
        verified, verify_evidence = verifiers.evaluate(
            con, exec_, postcondition, app_id=app_id, log=log
        )
        bundle.event({"type": "verify", "ok": bool(verified), "evidence": verify_evidence})
        log(f"  verify: {json.dumps(verify_evidence, ensure_ascii=False)[:160]}")
        if not verified:
            success = False
            error = f"postcondition failed: {json.dumps(verify_evidence, ensure_ascii=False)[:200]}"
    duration = int((time.time() - started) * 1000)
    db.finish_run(
        con, run_id, success, steps=trajectory, ai_cost=tokens, duration_ms=duration, error=error,
        verified=verified, verify=verify_evidence,
    )
    bundle.event({"type": "finished", "success": bool(success), "tokens": tokens, "error": error})
    return run_id, "done" if success else "failed"
