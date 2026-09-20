import json

import pytest

from maa_phone import compiler, db, learn, resolver


def make_db(tmp_path, monkeypatch):
    monkeypatch.setenv("MAA_PHONE_DB", str(tmp_path / "test.db"))
    con = db.connect(tmp_path / "test.db")
    db.init_db(con)
    return con


def test_resolver_scores_and_gate(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    con.execute(
        "INSERT INTO pipelines(name,goal,aliases,definition_json,status) VALUES(?,?,?,?,'live')",
        ("dark", "turn on dark mode", json.dumps(["dark mode", "深色模式"]), "[]"),
    )
    con.commit()
    pipeline, score = resolver.resolve(con, "turn on dark mode")
    assert pipeline is not None and score == 100
    pipeline, score = resolver.resolve(con, "please enable dark mode now")
    assert pipeline is not None and score >= 65
    pipeline, _ = resolver.resolve(con, "take a photo")
    assert pipeline is None


def test_compiler_resolves_elements(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    con.execute("INSERT INTO apps(package_name,name) VALUES('x','x')")
    con.commit()
    db.upsert_element(con, 1, "search_box", {"type": "uiauto", "value": "搜索"})
    con.execute(
        "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,status) VALUES(1,'s','g','[]',?, 'live')",
        (json.dumps([{"action": "tap", "element": "search_box"}]),),
    )
    con.commit()
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=1").fetchone()
    steps = compiler.compile_steps(con, pipeline)
    assert steps[0]["_locator"]["value"] == "搜索"


def test_learn_approve_makes_skill_live(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    run_id = db.start_run(con, "search the video 114514", app_id=1, path="bootstrap")
    steps = [
        {"action": "launch", "package": "com.demo"},
        {"action": "tap", "target": {"match": "text", "value": "搜索", "contains": True}},
        {"action": "text", "value": "114514"},
        {"action": "key", "value": "enter"},
    ]
    db.finish_run(con, run_id, True, steps=steps, ai_cost=1200)
    pid = learn.propose_from_run(con, run_id, with_ai=False)
    assert pid
    result = learn.approve(con, pid)
    assert "live" in result
    pipeline = con.execute("SELECT * FROM pipelines WHERE status='live'").fetchone()
    assert pipeline is not None
    compiled = compiler.compile_steps(con, pipeline)
    assert compiled[1]["element"].startswith("el_")
    assert compiled[1]["_locator"]["value"] == "搜索"
    element = con.execute("SELECT * FROM elements").fetchone()
    assert element is not None and json.loads(element["locator_json"])["value"] == "搜索"


def test_nightly_promotes_repeated_ai_success(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    for _ in range(2):
        run_id = db.start_run(con, "open camera", app_id=1, path="bootstrap")
        db.finish_run(con, run_id, True, steps=[{"action": "launch", "package": "com.demo"}])
    ids = learn.nightly(con, with_ai=False)
    assert len(ids) == 1
    assert con.execute("SELECT COUNT(*) c FROM proposals").fetchone()["c"] == 1


def test_template_matching(tmp_path):
    from PIL import Image, ImageDraw

    from maa_phone import matching

    img = Image.new("RGB", (300, 300), "white")
    draw = ImageDraw.Draw(img)
    draw.rectangle([100, 120, 140, 160], fill="black")
    draw.ellipse([200, 50, 230, 80], fill="red")
    tpl = img.crop((95, 115, 145, 165))
    path = tmp_path / "tpl.png"
    tpl.save(path)
    hit = matching.find_template(img, str(path), threshold=0.8)
    assert hit is not None
    assert abs(hit["center"][0] - 120) <= 3 and abs(hit["center"][1] - 140) <= 3
    assert matching.find_template(Image.new("RGB", (300, 300), "white"), str(path), 0.9) is None


def test_compile_maa_steps(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    con.execute("INSERT INTO apps(package_name,name) VALUES('x','x')")
    con.commit()
    db.upsert_element(con, 1, "cam", {"type": "ocr", "value": "相机", "contains": True})
    db.upsert_element(
        con,
        1,
        "shutter",
        {
            "type": "template",
            "template": "shutter.png",
            "path": "data/templates/shutter.png",
            "threshold": 0.8,
        },
    )
    steps = [
        {"action": "tap", "element": "cam"},
        {"action": "tap", "element": "shutter", "delay": 2.0},
        {"action": "text", "value": "abc"},
        {"action": "key", "value": "enter"},
        {"action": "wait", "seconds": 1},
        {"action": "tap", "point": [10, 20]},
    ]
    con.execute(
        "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,status) VALUES(1,'s','g','[]',?, 'live')",
        (json.dumps(steps),),
    )
    con.commit()
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=1").fetchone()
    nodes = compiler.compile_maa_steps(con, pipeline)
    assert len(nodes) == 6
    assert nodes[0]["node"]["recognition"] == "OCR"
    assert nodes[0]["node"]["expected"] == ["相机"]
    assert nodes[0]["node"]["target"] is True
    assert nodes[1]["node"]["recognition"] == "TemplateMatch"
    assert nodes[1]["template"] == "shutter.png"
    assert nodes[1]["node"]["post_delay"] == 2000
    assert nodes[2]["node"]["action"] == "InputText"
    assert nodes[3]["node"]["key"] == [66]
    assert nodes[4]["node"]["post_delay"] == 1000
    assert nodes[5]["node"]["target"] == [10, 20]


def test_debug_bundle_export(tmp_path, monkeypatch):
    import zipfile

    from maa_phone import config, debug

    monkeypatch.setattr(config, "RUNS", tmp_path / "runs")
    bundle = debug.RunBundle(7)
    bundle.manifest({"kind": "test"})
    bundle.event({"type": "step", "ok": True})
    target = bundle.export(out_dir=tmp_path)
    with zipfile.ZipFile(target) as zf:
        names = zf.namelist()
    assert any(name.endswith("manifest.json") for name in names)
    assert any(name.endswith("events.jsonl") for name in names)


def test_learn_drops_decorative_targets_and_collapses_taps(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    steps = [
        {"action": "launch", "package": "com.android.camera"},
        {"action": "tap", "target": {"value": "●", "contains": True}},
        {"action": "tap", "point": [540, 2070], "screenshot": "nope.png"},
        {"action": "tap", "point": [543, 2066], "screenshot": "nope.png"},
        {"action": "tap", "target": {"value": "搜索", "contains": True}},
    ]
    out, elements = learn.extract_elements(steps, "take a photo")
    actions = [(s.get("action"), s.get("target", {}).get("value"), tuple(s.get("point") or ())) for s in out]
    assert actions[0][0] == "launch"
    # the decorative dot is dropped, and the two jittery shutter taps collapse to one
    assert all(value != "●" for _, value, _ in actions)
    points = [p for _, _, p in actions if p]
    assert points == [(543, 2066)]
    assert "element" in out[-1]
    assert elements[out[-1]["element"]]["value"] == "搜索"


def test_validate_postcondition():
    assert learn.validate_postcondition({"type": "file_count", "path": "/sdcard/DCIM/Camera"}) == {
        "type": "file_count",
        "path": "/sdcard/DCIM/Camera",
        "delta_min": 1,
        "timeout": 10.0,
    }
    assert learn.validate_postcondition({"type": "element", "name": "shutter"})["name"] == "shutter"
    assert learn.validate_postcondition({"type": "element", "name": "x"}, element_names={"shutter"}) == {}
    assert learn.validate_postcondition({"type": "wat"}) == {}
    assert learn.validate_postcondition(None) == {}


def test_verifier_file_count(monkeypatch):
    from maa_phone import verifiers

    counts = iter([2, 2, 3, 3, 3])
    monkeypatch.setattr(verifiers, "adb_file_count", lambda path, serial=None: next(counts))
    ok, evidence = verifiers.evaluate(
        None,
        None,
        {"type": "file_count", "path": "/sdcard/DCIM/Camera", "delta_min": 1, "timeout": 2},
        context={"file_count": {"/sdcard/DCIM/Camera": 2}},
    )
    assert ok and evidence["before"] == 2 and evidence["after"] == 3


def test_approve_stores_postcondition(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    run_id = db.start_run(con, "open camera", app_id=1, path="bootstrap")
    db.finish_run(con, run_id, True, steps=[{"action": "launch", "package": "com.demo"}])
    pid = learn.propose_from_run(con, run_id, with_ai=False)
    row = con.execute("SELECT candidate_json FROM proposals WHERE id=?", (pid,)).fetchone()
    candidate = json.loads(row["candidate_json"])
    candidate["pipeline"]["postcondition"] = {
        "type": "file_count",
        "path": "/sdcard/DCIM/Camera",
        "delta_min": 1,
        "timeout": 10,
    }
    con.execute(
        "UPDATE proposals SET candidate_json=? WHERE id=?",
        (json.dumps(candidate), pid),
    )
    con.commit()
    learn.approve(con, pid)
    pipeline = con.execute("SELECT * FROM pipelines WHERE status='live'").fetchone()
    assert json.loads(pipeline["postcondition_json"])["type"] == "file_count"


def test_finish_run_records_verification(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    run_id = db.start_run(con, "x", path="pipeline")
    db.finish_run(
        con, run_id, True, verified=True, verify={"type": "file_count", "delta": 1}
    )
    row = con.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
    assert row["verified"] == 1
    assert json.loads(row["verify_json"])["type"] == "file_count"


def test_action_parsing_tolerates_tool_calls_and_fences():
    from maa_phone import agent_maa

    assert agent_maa._parse_action('```json\n{"action":"tap","point":[1,2]}\n```')["action"] == "tap"
    assert agent_maa._parse_action('prose before {"action":"done","summary":"ok"} after')["action"] == "done"
    assert agent_maa._parse_action("") is None
    message = {
        "content": "",
        "tool_calls": [
            {"function": {"name": "phone_action", "arguments": '{"action":"done","summary":"ok"}'}}
        ],
    }
    action, raw = agent_maa._action_from_message(message)
    assert action["action"] == "done"


def test_repeat_note_detects_jittery_taps():
    from maa_phone import agent_maa

    trajectory = [
        {"action": "launch", "package": "x"},
        {"action": "tap", "point": [540, 110]},
        {"action": "tap", "point": [540, 165]},
    ]
    note = agent_maa._repeat_note(trajectory)
    assert "STOP" in note
    trajectory.append({"action": "tap", "target": {"value": "搜索"}})
    assert "STOP" not in agent_maa._repeat_note([trajectory[-1]])


def test_verifier_pixel():
    from PIL import Image, ImageDraw

    from maa_phone import verifiers

    class FakeExec:
        def image(self):
            im = Image.new("RGB", (100, 100), (128, 128, 128))
            ImageDraw.Draw(im).rectangle([42, 42, 58, 58], fill=(251, 114, 153))
            return im

    ok, evidence = verifiers.evaluate(
        None,
        FakeExec(),
        {"type": "pixel", "point": [50, 50], "rgb": [251, 114, 153], "tolerance": 40,
         "radius": 5, "timeout": 1},
    )
    assert ok and evidence["hit"]


def test_proposal_reuses_verified_ai_postcondition(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    run_id = db.start_run(con, "like a video", app_id=1, path="bootstrap")
    db.finish_run(
        con,
        run_id,
        True,
        steps=[{"action": "tap", "target": {"value": "赞", "contains": True}}],
        verified=True,
        verify={"type": "pixel", "point": [50, 50], "rgb": [251, 114, 153],
                "tolerance": 40, "radius": 5, "timeout": 8, "hit": True},
    )
    pid = learn.propose_from_run(con, run_id, with_ai=False)
    candidate = json.loads(
        con.execute("SELECT candidate_json FROM proposals WHERE id=?", (pid,)).fetchone()[0]
    )
    post = candidate["pipeline"]["postcondition"]
    assert post["type"] == "pixel" and post["point"] == [50, 50]
    assert "hit" not in post


def test_noise_targets_reject_lone_digits_and_letters():
    assert learn._is_noise_target("1") is True
    assert learn._is_noise_target("A") is True
    assert learn._is_noise_target("●") is True
    assert learn._is_noise_target("114514") is False
    assert learn._is_noise_target("搜索") is False


def test_record_only_binds_point_to_centred_text():
    from maa_phone import agent_maa

    items = [{"text": "搜索", "box": (100, 100, 80, 40), "score": 1.0}]
    trajectory = []
    agent_maa._record(
        {"action": "tap", "point": [140, 120]}, items, "tapped", trajectory, scale=1.0
    )
    assert trajectory[-1]["target"]["value"] == "搜索"
    agent_maa._record(
        {"action": "tap", "point": [300, 300]}, items, "tapped", trajectory, "shot.png", 1.0
    )
    assert "target" not in trajectory[-1]
    assert trajectory[-1]["point"] == [300, 300]
    assert trajectory[-1]["screenshot"] == "shot.png"


def test_pipeline_model_normalizes_legacy_and_native_fields():
    from maa_phone import pipeline_model as pm

    ocr = pm.normalize_locator({"type": "ocr", "value": "搜索", "contains": True})
    assert ocr["expected"] == ["搜索"]
    assert "value" not in ocr and "contains" not in ocr

    tpl = pm.normalize_locator(
        {"type": "template", "path": "data/templates/shutter.png", "threshold": [0.8]}
    )
    assert tpl["template"] == ["shutter.png"]
    assert tpl["threshold"] == [0.8]

    point = pm.normalize_locator({"type": "point", "point": [10, 20]})
    assert point["target"] == [10, 20]

    native = pm.normalize_locator(
        {
            "type": "ocr",
            "expected": ["设置"],
            "roi": [0, 100, 200, 50],
            "roi_offset": [1, 2, 3, 4],
            "order_by": "Vertical",
            "index": -1,
            "replace": [["设置", "設定"]],
            "color_filter": "filter_node",
            "future_field": {"kept": True},
        }
    )
    assert native["roi"] == [0, 100, 200, 50]
    assert native["order_by"] == "Vertical"
    assert native["index"] == -1
    assert native["future_field"] == {"kept": True}


def test_compile_maa_steps_passes_native_fields(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    con.execute("INSERT INTO apps(package_name,name) VALUES('x','x')")
    con.commit()
    db.upsert_element(
        con,
        1,
        "like",
        {
            "type": "template",
            "template": "like.png",
            "roi": [100, 100, 300, 200],
            "roi_offset": [0, 0, 0, 0],
            "threshold": [0.8],
            "method": 5,
            "green_mask": True,
            "order_by": "Score",
            "index": 0,
        },
    )
    con.execute(
        "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,status) "
        "VALUES(1,'s','g','[]',?,'live')",
        (json.dumps([{"action": "tap", "element": "like", "delay": 1.5, "timeout": 5000}]),),
    )
    con.commit()
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=1").fetchone()
    nodes = compiler.compile_maa_steps(con, pipeline)
    node = nodes[0]["node"]
    assert node["recognition"] == "TemplateMatch"
    assert node["template"] == ["like.png"]
    assert node["roi"] == [100, 100, 300, 200]
    assert node["threshold"] == [0.8]
    assert node["method"] == 5
    assert node["green_mask"] is True
    assert node["order_by"] == "Score"
    assert node["action"] == "Click"
    assert node["target"] is True
    assert node["timeout"] == 5000
    assert node["post_delay"] == 1500


def test_build_node_native_fragment_and_validation():
    from maa_phone import pipeline_model as pm

    node = pm.build_node(
        {"action": "tap", "timeout": 1234, "pipeline": {"recognition": "DirectHit",
          "action": "Click", "target": [1, 2]}}
    )
    assert node["recognition"] == "DirectHit"
    assert node["target"] == [1, 2]
    assert node["timeout"] == 1234

    with pytest.raises(pm.PipelineModelError):
        pm.validate_pipeline({"bad": {"recognition": "NoSuchAlgorithm"}})
    with pytest.raises(pm.PipelineModelError):
        pm.validate_pipeline({})


def test_import_export_round_trip(tmp_path, monkeypatch):
    from maa_phone import pipeline_io

    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo", "Demo")
    pipeline = {
        "entry": {"recognition": "DirectHit", "action": "DoNothing", "next": ["search_box"]},
        "search_box": {
            "recognition": "OCR",
            "expected": ["搜索"],
            "roi": [0, 80, 1080, 120],
            "action": "Click",
            "target": True,
            "post_delay": 500,
            "next": ["submit"],
        },
        "submit": {"recognition": "TemplateMatch", "template": ["submit.png"],
                   "threshold": [0.8], "action": "Click", "next": []},
    }
    pid = pipeline_io.import_pipeline(con, pipeline, goal="search in demo", app_id=1)
    candidate = json.loads(
        con.execute("SELECT candidate_json FROM proposals WHERE id=?", (pid,)).fetchone()[0]
    )
    steps = candidate["pipeline"]["steps"]
    assert len(steps) == 2
    assert candidate["elements"]["search_box"]["expected"] == ["搜索"]
    assert candidate["elements"]["search_box"]["roi"] == [0, 80, 1080, 120]
    assert candidate["elements"]["submit"]["template"] == ["submit.png"]

    learn.approve(con, pid)
    pipeline = con.execute("SELECT * FROM pipelines WHERE status='live'").fetchone()
    exported = pipeline_io.export_pipeline(con, pipeline)
    assert exported["pipeline1_s0"]["recognition"] == "OCR"
    assert exported["pipeline1_s0"]["roi"] == [0, 80, 1080, 120]
    assert exported["pipeline1_s1"]["recognition"] == "TemplateMatch"
    assert exported["pipeline1_s1"]["next"] == []
    assert exported["pipeline1_s0"]["next"] == ["pipeline1_s1"]


def test_approve_hint_proposal(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    pid = db.add_proposal(
        con, "hint", {"package": "tv.danmaku.bili", "text": "search bar is at (360,100)"}
    )
    result = learn.approve(con, pid)
    assert "hint" in result
    assert (
        con.execute(
            "SELECT value FROM settings WHERE key='hint:tv.danmaku.bili'"
        ).fetchone()[0]
        == "search bar is at (360,100)"
    )


def test_resolver_expands_app_pattern(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    app_id = db.ensure_app(con, "com.android.camera", "相机")
    con.execute("UPDATE apps SET aliases=? WHERE id=?", (json.dumps(["camera"]), app_id))
    con.execute(
        "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,status) "
        "VALUES(?,?,?,?,?,'live')",
        (app_id, "open_app", "open an app", json.dumps(["open {app}"]), "[]"),
    )
    con.commit()
    pipeline, score = resolver.resolve(con, "open camera")
    assert pipeline is not None and score >= 65
    slots_skill, _, slots = resolver.resolve_with_slots(con, "open 相机")
    assert slots_skill is not None and slots["app"] == app_id
    missing, _ = resolver.resolve(con, "open nosuchapp")
    assert missing is None


def test_autonomy_gate(tmp_path, monkeypatch):
    from maa_phone import safety

    con = make_db(tmp_path, monkeypatch)
    for autonomy in ("auto", "confirm", "never"):
        con.execute(
            "INSERT INTO pipelines(name,goal,aliases,definition_json,status,autonomy) "
            "VALUES(?,?,'[]','[]','live',?)",
            (autonomy, autonomy, autonomy),
        )
    con.commit()
    rows = {r["autonomy"]: r for r in con.execute("SELECT * FROM pipelines")}
    assert safety.check(rows["auto"])[0] is True
    allowed, reason = safety.check(rows["confirm"], assume_yes=False)
    assert allowed is False and "confirm" in reason
    assert safety.check(rows["confirm"], assume_yes=True)[0] is True
    allowed, reason = safety.check(rows["never"], assume_yes=True)
    assert allowed is False and "never" in reason


def test_message_stream_around_run(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    run_id = db.start_run(con, "take a photo", path="pipeline")
    db.add_message(con, "assistant", "question", "Which camera app?", run_id=run_id,
                   payload={"options": ["Camera", "OpenCamera"]})
    db.finish_run(con, run_id, True, duration_ms=1200, verified=True,
                  verify={"type": "file_count", "before": 1, "after": 2})
    rows = db.messages(con, run_id=run_id)
    assert [r["kind"] for r in rows] == ["goal", "question", "result"]
    assert rows[1]["payload_json"] and json.loads(rows[1]["payload_json"])["options"]
    result = json.loads(rows[2]["payload_json"])
    assert result["success"] is True and result["verified"] is True


def test_resume_state_from_run_includes_answer():
    from maa_phone import agent_maa

    run = {
        "id": 7,
        "progress_json": json.dumps(
            {
                "step": 3,
                "trajectory": [{"action": "launch"}],
                "history": [{"role": "user", "content": "goal"}],
                "tokens": 1200,
                "question": "Which app?",
            }
        ),
    }
    state = agent_maa.resume_state_from_run(run, answer="WhatsApp")
    assert state["run_id"] == 7 and state["step"] == 3
    assert state["tokens"] == 1200
    assert state["history"][-1]["content"].endswith("WhatsApp")
    assert "ask" in agent_maa.VALID_ACTIONS
    enum = agent_maa.ACTION_TOOL["function"]["parameters"]["properties"]["action"]["enum"]
    assert "ask" in enum


def test_dhash_and_decision_cache(tmp_path, monkeypatch):
    from PIL import Image, ImageDraw

    from maa_phone import vision

    a = Image.new("RGB", (120, 120), "white")
    ImageDraw.Draw(a).rectangle([0, 0, 50, 120], fill="black")
    b = Image.new("RGB", (120, 120), "white")
    ImageDraw.Draw(b).rectangle([70, 0, 120, 120], fill="black")
    assert vision.DeepSeek.dhash(a) == vision.DeepSeek.dhash(a.copy())
    assert vision.DeepSeek.dhash(a) != vision.DeepSeek.dhash(b)

    con = make_db(tmp_path, monkeypatch)
    db.cache_put(
        con, "key1", "send a message", "com.demo", "abcd", "prompt",
        {"action": "tap", "point": [1, 2]},
    )
    row = db.cache_get(con, "key1")
    assert row["hits"] == 1
    assert json.loads(row["action_json"])["action"] == "tap"
    assert db.cache_clear(con) == 1
    assert db.cache_get(con, "key1") is None


def test_policy_matching_and_goal_gate(tmp_path, monkeypatch):
    from maa_phone import policies, safety

    con = make_db(tmp_path, monkeypatch)
    con.execute(
        "INSERT INTO policies(name,priority,match_json,action_json) VALUES(?,?,?,?)",
        ("no_delete", 10, json.dumps({"goal_keywords": ["delete", "删除"]}),
         json.dumps({"type": "deny", "reason": "deletion is never automatic"})),
    )
    con.execute(
        "INSERT INTO policies(name,priority,match_json,action_json) VALUES(?,?,?,?)",
        ("confirm_send", 20, json.dumps({"goal_keywords": ["send", "发"]}),
         json.dumps({"type": "confirm", "reason": "sending needs approval"})),
    )
    con.commit()
    action, row = policies.decide(con, "delete my photos")
    assert action["type"] == "deny" and row["name"] == "no_delete"
    action, _ = policies.decide(con, "send a message to Anna")
    assert action["type"] == "confirm"
    action, _ = policies.decide(con, "open camera")
    assert action["type"] == "allow"

    allowed, reason = safety.check_goal(con, "delete my photos")
    assert allowed is False and "denied" in reason
    allowed, _ = safety.check_goal(con, "send a message", assume_yes=True)
    assert allowed is True
    allowed, reason = safety.check_goal(con, "send a message")
    assert allowed is False and "--yes" in reason


def test_approve_policy_proposal(tmp_path, monkeypatch):
    con = make_db(tmp_path, monkeypatch)
    pid = db.add_proposal(
        con,
        "policy",
        {
            "name": "confirm_payment",
            "priority": 5,
            "match": {"goal_keywords": ["pay", "付款"]},
            "action": {"type": "confirm", "reason": "payment needs approval"},
        },
    )
    result = learn.approve(con, pid)
    assert "policy" in result
    row = con.execute("SELECT * FROM policies").fetchone()
    assert row["name"] == "confirm_payment"
    assert json.loads(row["action_json"])["type"] == "confirm"


def test_evaluate_pipeline_aggregates(tmp_path, monkeypatch):
    from maa_phone import evaluate, runner

    con = make_db(tmp_path, monkeypatch)
    con.execute(
        "INSERT INTO pipelines(name,goal,aliases,definition_json,status,autonomy) "
        "VALUES('s','open demo','[]',?,'live','auto')",
        (json.dumps([{"action": "launch", "package": "com.demo"}]),),
    )
    con.commit()
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=1").fetchone()
    calls = []

    def fake_run_pipeline(con, pipeline, goal=None, pause_after=None, resume_run=None, log=print):
        calls.append(goal)
        run_id = db.start_run(con, goal or "g", pipeline_id=pipeline["id"], path="pipeline")
        success = len(calls) != 2
        db.finish_run(con, run_id, success, verified=success, duration_ms=10)
        return run_id, "done" if success else "failed"

    monkeypatch.setattr(runner, "run_pipeline", fake_run_pipeline)
    summary = evaluate.evaluate_pipeline(con, pipeline, times=3)
    assert summary["passed"] == 2
    assert summary["failed"] == 1
    assert summary["verified"] == 2
    assert con.execute("SELECT COUNT(*) c FROM eval_items").fetchone()["c"] == 3
    row = con.execute("SELECT * FROM eval_runs WHERE id=?", (summary["eval_id"],)).fetchone()
    assert row["passed"] == 2 and row["verified"] == 2


def test_bootstrap_run_creates_pipeline_version_and_approve(tmp_path, monkeypatch):
    from maa_phone import pipelines

    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    run_id = db.start_run(con, "open camera", app_id=1, path="bootstrap")
    db.finish_run(con, run_id, True, steps=[{"action": "launch", "package": "com.demo"}])
    pid = learn.propose_from_run(con, run_id, with_ai=False)
    proposal = con.execute("SELECT * FROM proposals WHERE id=?", (pid,)).fetchone()
    pipeline_id = proposal["target_pipeline_id"]
    version_id = proposal["target_version_id"]
    assert pipeline_id and version_id

    version = con.execute("SELECT * FROM pipeline_versions WHERE id=?", (version_id,)).fetchone()
    assert version["status"] == "candidate"
    link = con.execute(
        "SELECT * FROM pipeline_version_runs WHERE pipeline_version_id=?", (version_id,)
    ).fetchone()
    assert link["run_id"] == run_id

    learn.approve(con, pid)
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=?", (pipeline_id,)).fetchone()
    assert pipeline["status"] == "live"
    assert con.execute(
        "SELECT status FROM pipeline_versions WHERE id=?", (version_id,)
    ).fetchone()["status"] == "approved"


def test_multiple_bootstrap_runs_reuse_pipeline_create_versions(tmp_path, monkeypatch):
    from maa_phone import pipelines

    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    for _ in range(2):
        run_id = db.start_run(con, "open camera", app_id=1, path="bootstrap")
        db.finish_run(con, run_id, True, steps=[{"action": "launch", "package": "com.demo"}])
        learn.propose_from_run(con, run_id, with_ai=False)

    rows = con.execute("SELECT * FROM pipelines").fetchall()
    assert len(rows) == 1
    versions = con.execute("SELECT * FROM pipeline_versions ORDER BY version").fetchall()
    assert [v["version"] for v in versions] == [1, 2]
    assert len(con.execute("SELECT * FROM pipeline_version_runs").fetchall()) == 2


def test_mission_items_round_trip(tmp_path, monkeypatch):
    from maa_phone import pipelines

    con = make_db(tmp_path, monkeypatch)
    db.ensure_app(con, "com.demo")
    pipeline_row = pipelines.ensure_pipeline(con, "open settings", app_id=1, source="manual")
    mission = pipelines.create_mission(con, "morning", "daily run")
    item = pipelines.add_mission_item(
        con, mission["id"], pipeline_id=pipeline_row["id"],
        goal="open settings", overrides={"autonomy": "confirm"},
    )
    items = pipelines.list_mission_items(con, mission["id"])
    assert len(items) == 1
    assert items[0]["id"] == item["id"]
    assert items[0]["pipeline_id"] == pipeline_row["id"]
