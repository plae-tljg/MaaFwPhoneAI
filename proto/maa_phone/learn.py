import hashlib
import json
import re

from PIL import Image

from . import config, db, pipelines, policies, resolver, vision


def slugify(value):
    value = (value or "").strip()
    ascii_slug = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    if ascii_slug:
        return ascii_slug[:40]
    return "el_" + hashlib.md5(value.encode("utf-8")).hexdigest()[:8]


def _is_noise_target(value):
    """Decorative glyphs and lone digits are not semantic UI targets."""
    value = (value or "").strip()
    if not value:
        return True
    if len(value) == 1:
        if value.isdigit() or (value.isascii() and value.isalpha()):
            return True
    if len(value) <= 2 and not re.search(r"[0-9A-Za-z\u4e00-\u9fff]", value):
        return value not in ("+", "-", "×", "✕", "✖")
    return False


def extract_elements(steps, goal=""):
    steps = _dedupe(steps)
    elements = {}
    out = []
    for index, step in enumerate(steps):
        step = dict(step)
        target = step.get("target")
        if target and target.get("value"):
            if _is_noise_target(target["value"]):
                continue
            value = target["value"]
            name = slugify(value)
            elements[name] = {
                "type": "ocr",
                "match": target.get("match", "text"),
                "value": value,
                "contains": target.get("contains", True),
            }
            step.pop("target", None)
            step["element"] = name
        elif step.get("point") and step.get("screenshot"):
            name = _template_element(step, index, goal)
            if name:
                elements[name] = {
                    "type": "template",
                    "template": f"{name}.png",
                    "path": str((config.TEMPLATES / f"{name}.png").relative_to(config.ROOT)),
                    "threshold": 0.75,
                }
                step.pop("point", None)
                step.pop("screenshot", None)
                step["element"] = name
        step.pop("screenshot", None)
        out.append(step)
    return out, elements


def _points_close(a, b, max_dist=20):
    if not a or not b or len(a) < 2 or len(b) < 2:
        return False
    return abs(int(a[0]) - int(b[0])) <= max_dist and abs(int(a[1]) - int(b[1])) <= max_dist


def _dedupe(steps):
    """Collapse exact repeats and taps within 20px (learned jitter), keeping the last."""
    out = []
    prev_point = None
    for step in steps:
        if step.get("action") == "tap":
            point = step.get("point")
            if point and prev_point and _points_close(point, prev_point):
                out[-1] = step
                prev_point = point
                continue
            prev_point = point
        else:
            prev_point = None
        out.append(step)
    return out


def _template_element(step, index, goal):
    shot = config.ROOT / step["screenshot"]
    if not shot.exists():
        return None
    image = Image.open(shot)
    x, y = step["point"]
    half = 110
    left, top = max(0, x - half), max(0, y - half)
    right, bottom = min(image.width, x + half), min(image.height, y + half)
    if right - left < 40 or bottom - top < 40:
        return None
    crop = image.crop((left, top, right, bottom))
    base = slugify(goal)[:24] or "tap"
    name = f"{base}_{index}"
    config.TEMPLATES.mkdir(parents=True, exist_ok=True)
    config.BUNDLE_IMAGE.mkdir(parents=True, exist_ok=True)
    crop.save(config.TEMPLATES / f"{name}.png")
    crop.save(config.BUNDLE_IMAGE / f"{name}.png")
    return name


SUPPORTED_POSTCONDITIONS = ("file_count", "element", "screen_text")


def validate_postcondition(candidate, element_names=()):
    """Accept only verifier types the prototype can actually run."""
    if not isinstance(candidate, dict):
        return {}
    kind = candidate.get("type")
    if kind not in SUPPORTED_POSTCONDITIONS:
        return {}
    if kind == "file_count":
        path = candidate.get("path")
        if not isinstance(path, str) or not path.startswith("/"):
            return {}
        return {
            "type": kind,
            "path": path,
            "delta_min": max(1, int(candidate.get("delta_min", 1))),
            "timeout": max(1.0, float(candidate.get("timeout", 10))),
        }
    if kind == "element":
        name = candidate.get("name")
        if not isinstance(name, str) or (element_names and name not in element_names):
            return {}
        return {"type": kind, "name": name, "timeout": max(1.0, float(candidate.get("timeout", 8)))}
    text = candidate.get("text")
    if not isinstance(text, str) or not text.strip():
        return {}
    return {"type": kind, "text": text.strip(), "timeout": max(1.0, float(candidate.get("timeout", 8)))}


def suggest_metadata(goal, steps, client=None, element_names=()):
    fallback = {"name": slugify(goal) or "pipeline", "aliases": [goal], "postcondition": {}}
    try:
        client = client or vision.DeepSeek()
        prompt = (
            "You name automation pipelines. Given the user goal and the recorded steps, "
            'return JSON {"name": "short_snake_case", "aliases": ["...", "..."], "postcondition": {...}|null}. '
            "Aliases are short goal phrases a user might say, in the same language as the goal.\n"
            "The postcondition must prove the goal happened after replay, and may only be one of:\n"
            '  {"type":"file_count","path":"/abs/device/dir","delta_min":1,"timeout":10} '
            "when the goal creates a file (photo, download, export);\n"
            '  {"type":"element","name":"<one of the learned element names>"} '
            "when a learned element proves the final screen;\n"
            '  {"type":"screen_text","text":"...","timeout":8} when a specific on-screen text proves it.\n'
            "Omit postcondition (null) when nothing observable distinguishes success.\n"
            f"Learned element names: {sorted(element_names)}\n"
            f"Goal: {goal}\nSteps: {json.dumps(steps, ensure_ascii=False)[:1500]}"
        )
        data = json.loads(client.complete_json(prompt, max_tokens=700))
        aliases = [a for a in data.get("aliases", []) if isinstance(a, str)]
        if goal not in aliases:
            aliases.insert(0, goal)
        return {
            "name": str(data.get("name") or fallback["name"])[:60],
            "aliases": aliases[:6],
            "postcondition": validate_postcondition(
                data.get("postcondition"), element_names=element_names
            ),
        }
    except Exception:
        return fallback


def propose_from_run(con, run_id, with_ai=True):
    run = con.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
    if not run or not run["success"] or run["path"] != "bootstrap":
        return None
    steps, elements = extract_elements(json.loads(run["steps_json"] or "[]"), run["goal"])
    if not steps:
        return None
    app_id = run["app_id"]
    launch_package = next(
        (step.get("package") for step in steps if step.get("action") == "launch" and step.get("package")),
        None,
    )
    if launch_package:
        app_id = db.ensure_app(con, launch_package)
    if with_ai:
        meta = suggest_metadata(run["goal"], steps, element_names=elements.keys())
    else:
        meta = {
            "name": slugify(run["goal"]) or "pipeline",
            "aliases": [run["goal"]],
            "postcondition": {},
        }
    # If the AI run itself was verified, reuse that evidence as the pipeline's
    # postcondition so replay is held to the same standard.
    run_post = {}
    if run["verified"]:
        try:
            evidence = json.loads(run["verify_json"] or "{}")
        except json.JSONDecodeError:
            evidence = {}
        allowed = (
            "type", "path", "delta_min", "timeout", "point", "rgb",
            "tolerance", "radius", "name", "text",
        )
        run_post = {key: evidence[key] for key in allowed if key in evidence}
    pipeline_row = pipelines.ensure_pipeline(
        con, run["goal"], app_id=app_id, name=meta["name"], source="bootstrap",
        aliases=meta["aliases"],
    )
    version_id = pipelines.add_candidate(
        con,
        pipeline_row["id"],
        steps,
        postcondition=run_post or meta.get("postcondition") or {},
        source="bootstrap",
        source_run_id=run_id,
        evidence={"verify_json": run["verify_json"] or "{}"},
        run_role="source",
    )
    candidate = {
        "pipeline": {
            "id": pipeline_row["id"],
            "version_id": version_id,
            "name": meta["name"],
            "goal": run["goal"],
            "aliases": meta["aliases"],
            "app_id": app_id,
            "steps": steps,
            "postcondition": run_post or meta.get("postcondition") or {},
        },
        "elements": elements,
    }
    return db.add_proposal(
        con,
        "pipeline_new",
        candidate,
        source_run_id=run_id,
        target_pipeline_id=pipeline_row["id"],
        target_version_id=version_id,
    )


def _publish_pipeline_new(con, candidate):
    pipeline = candidate["pipeline"]
    app_id = pipeline.get("app_id")
    for name, locator in candidate.get("elements", {}).items():
        if app_id:
            db.upsert_element(con, app_id, name, locator)
    version_id = candidate.get("version_id") or pipeline.get("version_id")
    if version_id:
        # A human/editor may have changed the candidate JSON before approving.
        con.execute(
            "UPDATE pipeline_versions SET definition_json=?, postcondition_json=?, entry=? "
            "WHERE id=? AND status='candidate'",
            (
                json.dumps(pipeline.get("steps") or [], ensure_ascii=False),
                json.dumps(pipeline.get("postcondition") or {}, ensure_ascii=False),
                pipeline.get("entry") or "",
                int(version_id),
            ),
        )
        con.execute(
            "UPDATE pipelines SET name=?, goal=?, aliases=?, updated_at=datetime('now') WHERE id=?",
            (
                pipeline.get("name") or "",
                pipeline.get("goal") or "",
                json.dumps(pipeline.get("aliases") or [], ensure_ascii=False),
                int(pipeline.get("id") or pipeline.get("pipeline_id")),
            ),
        )
        row = pipelines.approve_version(con, int(version_id))
        return f"pipeline {row['name']!r} is live (version {version_id})"
    # legacy candidate without a version row: publish directly
    con.execute(
        "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,postcondition_json,entry,status) "
        "VALUES(?,?,?,?,?,?,'','live')",
        (
            app_id,
            pipeline["name"],
            pipeline["goal"],
            json.dumps(pipeline["aliases"], ensure_ascii=False),
            json.dumps(pipeline["steps"], ensure_ascii=False),
            json.dumps(pipeline.get("postcondition") or {}, ensure_ascii=False),
        ),
    )
    return f"pipeline {pipeline['name']!r} is live"


def _publish_alias(con, candidate):
    con.execute(
        "UPDATE pipelines SET aliases=?, updated_at=datetime('now') WHERE id=?",
        (
            json.dumps(candidate["aliases"], ensure_ascii=False),
            candidate["pipeline_id"],
        ),
    )
    return f"aliases updated for pipeline {candidate['pipeline_id']}"


def _publish_element_fix(con, candidate):
    db.upsert_element(
        con,
        candidate["app_id"],
        candidate["name"],
        candidate["locator"],
    )
    return f"element {candidate['name']!r} healed"


def _publish_pipeline_fix(con, candidate):
    con.execute(
        "UPDATE pipelines SET status='broken', updated_at=datetime('now') WHERE id=?",
        (candidate["pipeline_id"],),
    )
    return f"pipeline {candidate['pipeline_id']} marked broken for review"


def _publish_hint(con, candidate):
    """App knowledge the AI can propose and a human reviews.

    Hints live in ``settings.hint:<package>`` in v0; the agent already reads
    them for its prompt. Moving them to a dedicated table later only changes
    this publisher.
    """
    package = str(candidate.get("package") or candidate.get("app") or "").strip()
    text = str(candidate.get("text") or candidate.get("content") or "").strip()
    if not package or not text:
        raise ValueError("hint proposal needs package and text")
    db.set_setting(con, f"hint:{package}", text)
    return f"hint for {package} updated"


def _publish_policy(con, candidate):
    row = policies.validate(candidate)
    app_id = row["app_id"]
    con.execute(
        "INSERT INTO policies(app_id,name,priority,match_json,action_json) "
        "VALUES(?,?,?,?,?)",
        (
            app_id,
            row["name"],
            row["priority"],
            json.dumps(row["match"], ensure_ascii=False),
            json.dumps(row["action"], ensure_ascii=False),
        ),
    )
    return f"policy {row['name']!r} is live"


# One publisher per knowledge kind. Adding a kind the AI may propose means
# adding a function here + validation, not another elif in approve().
PUBLISHERS = {
    "pipeline_new": _publish_pipeline_new,
    "alias": _publish_alias,
    "element_fix": _publish_element_fix,
    "pipeline_fix": _publish_pipeline_fix,
    "hint": _publish_hint,
    "policy": _publish_policy,
}


def approve(con, proposal_id):
    prop = con.execute("SELECT * FROM proposals WHERE id=?", (proposal_id,)).fetchone()
    if not prop:
        raise ValueError(f"proposal {proposal_id} not found")
    if prop["status"] != "pending":
        raise ValueError(f"proposal {proposal_id} is {prop['status']}")
    try:
        candidate = json.loads(prop["candidate_json"])
    except json.JSONDecodeError as exc:
        raise ValueError(f"proposal {proposal_id} has invalid JSON: {exc}")
    publisher = PUBLISHERS.get(prop["kind"])
    if publisher is None:
        raise ValueError(f"unknown proposal kind: {prop['kind']}")
    result = publisher(con, candidate)
    con.execute(
        "UPDATE proposals SET status='approved', reviewed_at=datetime('now') WHERE id=?",
        (proposal_id,),
    )
    con.commit()
    return result


def reject(con, proposal_id, note=""):
    con.execute(
        "UPDATE proposals SET status='rejected', reviewed_at=datetime('now'), "
        "candidate_json=json_set(candidate_json,'$.note',?) WHERE id=?",
        (note, proposal_id),
    )
    con.commit()


def _already_known(con, goal):
    norm = resolver.normalize(goal)
    for row in con.execute("SELECT aliases FROM pipelines WHERE status='live'"):
        for alias in json.loads(row["aliases"] or "[]"):
            if resolver.normalize(alias) == norm:
                return True
    for row in con.execute("SELECT candidate_json FROM proposals WHERE status='pending'"):
        try:
            cand = json.loads(row["candidate_json"])
        except json.JSONDecodeError:
            continue
        pipeline = cand.get("pipeline") or {}
        if resolver.normalize(pipeline.get("goal", "")) == norm:
            return True
        for alias in pipeline.get("aliases", []):
            if resolver.normalize(alias) == norm:
                return True
    return False


def nightly(con, with_ai=False):
    created = []
    groups = {}
    for run in con.execute("SELECT * FROM runs WHERE path='bootstrap' AND success=1 ORDER BY id"):
        groups.setdefault(resolver.normalize(run["goal"]), []).append(run)
    minimum = int(db.setting(con, "learn_min_runs", "2"))
    for runs in groups.values():
        if len(runs) < minimum:
            continue
        latest = runs[-1]
        if _already_known(con, latest["goal"]):
            continue
        pid = propose_from_run(con, latest["id"], with_ai=with_ai)
        if pid:
            created.append(pid)
    for pipeline in con.execute(
        "SELECT * FROM pipelines WHERE status='live' AND fail>=2"
    ).fetchall():
        pending = con.execute(
            "SELECT 1 FROM proposals WHERE status='pending' AND kind='pipeline_fix' "
            "AND candidate_json LIKE ?",
            (f'%"pipeline_id": {pipeline["id"]}%',),
        ).fetchone()
        if pending:
            continue
        created.append(
            db.add_proposal(
                con,
                "pipeline_fix",
                {"pipeline_id": pipeline["id"], "reason": "fail_streak", "fail": pipeline["fail"]},
            )
        )
    return created
