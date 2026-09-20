import argparse
import json
import sys
import time

from . import (
    agent,
    agent_maa,
    compiler,
    config,
    db,
    debug,
    device,
    evaluate,
    executor,
    learn,
    pipeline_io,
    pipelines,
    resolver,
    runner,
    safety,
    uiauto,
    vision,
)


def _con():
    return db.init_db()


def _run_pipeline(cmd, con, dev, pipeline, goal=None, pause_after=None, resume_run=None):
    steps = compiler.compile_steps(con, pipeline)
    if resume_run:
        run_id = resume_run["id"]
        progress = json.loads(resume_run["progress_json"] or "{}")
        start_index = int(progress.get("step_index", 0))
        con.execute("UPDATE runs SET state='running' WHERE id=?", (run_id,))
        con.commit()
    else:
        start_index = 0
        run_id = db.start_run(
            con,
            goal or pipeline["goal"] or pipeline["name"],
            app_id=pipeline["app_id"],
            pipeline_id=pipeline["id"],
            path="pipeline",
            source="in_app",
        )
    started = time.time()
    try:
        status, index = executor.run_steps(
            dev, con, run_id, steps, pause_after=pause_after, start_index=start_index
        )
    except Exception as exc:
        status, index = "error", 0
        con.execute(
            "UPDATE runs SET error=?, success=0, state='failed', "
            "finished_at=datetime('now') WHERE id=?",
            (str(exc), run_id),
        )
        con.execute(
            "UPDATE pipelines SET fail=fail+1, last_run_at=datetime('now') WHERE id=?",
            (pipeline["id"],),
        )
        con.commit()
        cmd(f"pipeline failed: {exc}")
        return run_id, "failed"
    duration = int((time.time() - started) * 1000)
    if status == "done":
        db.finish_run(con, run_id, True, duration_ms=duration)
        con.execute(
            "UPDATE pipelines SET success=success+1, fail=0, status='live', "
            "last_run_at=datetime('now'), updated_at=datetime('now') WHERE id=?",
            (pipeline["id"],),
        )
        con.commit()
    return run_id, status


def cmd_goal(args):
    con = _con()
    goal = args.text
    active = con.execute(
        "SELECT * FROM runs WHERE state='running' ORDER BY id DESC LIMIT 1"
    ).fetchone()
    if active:
        choice = args.interrupt
        if choice == "ask":
            if sys.stdin.isatty():
                answer = input(
                    f"run #{active['id']} '{active['goal']}' is active. "
                    "[q]ueue / [d]o now / [c]ancel? "
                ).strip().lower()
                choice = {"q": "queue", "d": "now", "c": "cancel"}.get(answer, "queue")
            else:
                choice = "queue"
        if choice == "queue":
            con.execute(
                "INSERT INTO runs(goal,path,source,state) VALUES(?,'bootstrap','in_app','queued')",
                (goal,),
            )
            con.commit()
            print(f"queued: {goal}")
            return
        if choice == "now":
            con.execute("UPDATE runs SET state='paused' WHERE id=?", (active["id"],))
            con.commit()
            print(f"paused run #{active['id']} (resume later with: resume {active['id']})")
        if choice == "cancel":
            con.execute(
                "UPDATE runs SET state='cancelled', finished_at=datetime('now') WHERE id=?",
                (active["id"],),
            )
            con.commit()

    pipeline, confidence = resolver.resolve(con, goal)
    if pipeline and not args.force_ai:
        allowed, reason = safety.check(
            pipeline,
            assume_yes=getattr(args, "yes", False),
            interactive=sys.stdin.isatty(),
        )
        if not allowed:
            print(f"safety: {reason}")
            return
        print(f"resolver: pipeline '{pipeline['name']}' (score {confidence}) -> deterministic")
        run_id, status = runner.run_pipeline(con, pipeline, goal=goal, pause_after=args.pause_after)
        print(f"run #{run_id}: {status}")
        return

    allowed, reason = safety.check_goal(
        con,
        goal,
        assume_yes=getattr(args, "yes", False),
        interactive=sys.stdin.isatty(),
    )
    if not allowed:
        print(f"safety: {reason}")
        return
    print(f"resolver: no live pipeline (best {confidence}) -> AI fallback [{args.engine}]")
    verify = None
    if getattr(args, "verify", ""):
        try:
            verify = json.loads(args.verify)
        except json.JSONDecodeError as exc:
            print(f"invalid --verify JSON: {exc}")
            return
    if args.engine == "maa":
        run_id, status = agent_maa.run_agent(
            con, goal, max_steps=args.max_steps, pause_after=args.pause_after,
            postcondition=verify,
        )
    else:
        dev = device.Device()
        app_id = db.ensure_app(con, dev.current_app() or "unknown")
        run_id, status = agent.run_agent(
            dev,
            con,
            goal,
            app_id=app_id,
            max_steps=args.max_steps,
            pause_after=args.pause_after,
        )
    print(f"run #{run_id}: {status}")
    if status == "done":
        pid = None if args.no_proposal else learn.propose_from_run(con, run_id, with_ai=not args.no_ai)
        if pid:
            print(f"learned: proposal #{pid} ready to review (approve {pid})")


def cmd_resume(args):
    con = _con()
    run = con.execute("SELECT * FROM runs WHERE id=?", (args.run_id,)).fetchone()
    if not run:
        print(f"run {args.run_id} not found")
        return
    engine = args.engine or run["engine"] or "maa"
    if run["path"] == "pipeline" and run["pipeline_id"]:
        pipeline = con.execute("SELECT * FROM pipelines WHERE id=?", (run["pipeline_id"],)).fetchone()
        allowed, reason = safety.check(
            pipeline,
            assume_yes=getattr(args, "yes", False),
            interactive=sys.stdin.isatty(),
        )
        if not allowed:
            print(f"safety: {reason}")
            return
        if engine == "maa":
            _, status = runner.run_pipeline(con, pipeline, resume_run=run, pause_after=args.pause_after)
        else:
            dev = device.Device()
            _, status = _run_pipeline(print, con, dev, pipeline, resume_run=run, pause_after=args.pause_after)
        print(f"resume run #{run['id']}: {status}")
        return
    progress = json.loads(run["progress_json"] or "{}")
    state = {
        "run_id": run["id"],
        "step": int(progress.get("step", 0)),
        "trajectory": progress.get("trajectory", []),
        "history": progress.get("history", []),
        "tokens": int(progress.get("tokens", 0)),
    }
    if engine == "maa":
        _, status = agent_maa.run_agent(
            con,
            run["goal"],
            app_id=run["app_id"],
            max_steps=args.max_steps,
            pause_after=args.pause_after,
            resume_state=state,
        )
    else:
        dev = device.Device()
        _, status = agent.run_agent(
            dev,
            con,
            run["goal"],
            app_id=run["app_id"],
            max_steps=args.max_steps,
            pause_after=args.pause_after,
            resume_state=state,
        )
    print(f"resume run #{run['id']}: {status}")


def cmd_answer(args):
    con = _con()
    run = con.execute("SELECT * FROM runs WHERE id=?", (args.run_id,)).fetchone()
    if not run:
        print(f"run {args.run_id} not found")
        return
    if run["state"] != "needs_input":
        print(f"run {args.run_id} is {run['state']}, not needs_input")
        return
    db.add_message(con, "user", "answer", args.text, run_id=run["id"])
    state = agent_maa.resume_state_from_run(run, answer=args.text)
    engine = args.engine or run["engine"] or "maa"
    if engine == "maa":
        run_id, status = agent_maa.run_agent(
            con, run["goal"], app_id=run["app_id"], max_steps=args.max_steps,
            resume_state=state,
        )
    else:
        dev = device.Device()
        run_id, status = agent.run_agent(
            dev, con, run["goal"], app_id=run["app_id"], max_steps=args.max_steps,
            resume_state=state,
        )
    print(f"answer run #{run_id}: {status}")
    if status == "done":
        pid = learn.propose_from_run(con, run_id, with_ai=not args.no_ai)
        if pid:
            print(f"learned: proposal #{pid} ready to review (approve {pid})")


def cmd_thread(args):
    con = _con()
    rows = db.messages(con, run_id=args.run_id, limit=args.limit)
    for row in rows:
        print(
            f"#{row['id']} [run {row['run_id'] or '-'}] {row['role']}/{row['kind']} "
            f"({row['state']}): {row['content'][:120]}"
        )


def cmd_process_queue(args):
    con = _con()
    while True:
        row = con.execute(
            "SELECT * FROM runs WHERE state='queued' ORDER BY id LIMIT 1"
        ).fetchone()
        if not row:
            break
        con.execute("UPDATE runs SET state='running' WHERE id=?", (row["id"],))
        con.commit()
        print(f"processing queued run #{row['id']}: {row['goal']}")
        sub = argparse.Namespace(
            text=row["goal"],
            force_ai=True,
            max_steps=args.max_steps,
            pause_after=None,
            interrupt="queue",
            no_proposal=False,
            no_ai=False,
            engine=args.engine,
            verify=None,
            yes=getattr(args, "yes", False),
        )
        cmd_goal(sub)


def cmd_learn(args):
    con = _con()
    ids = learn.nightly(con, with_ai=not args.no_ai)
    if not ids:
        print("no new proposals")
    for pid in ids:
        print(f"proposal #{pid}")


def cmd_proposals(args):
    con = _con()
    rows = con.execute("SELECT * FROM proposals ORDER BY id").fetchall()
    for row in rows:
        candidate = json.loads(row["candidate_json"] or "{}")
        pipeline = candidate.get("pipeline") or {}
        label = pipeline.get("name") or candidate.get("name") or candidate.get("reason") or ""
        print(
            f"#{row['id']} [{row['kind']}] {row['status']} run={row['source_run_id']} {label}"
        )


def cmd_approve(args):
    con = _con()
    print(learn.approve(con, args.id))


def cmd_reject(args):
    con = _con()
    learn.reject(con, args.id, note=args.note or "")
    print(f"proposal #{args.id} rejected")


def cmd_pipelines(args):
    con = _con()
    for row in con.execute("SELECT * FROM pipelines ORDER BY id"):
        aliases = ", ".join(json.loads(row["aliases"] or "[]"))
        post = row["postcondition_json"] or "{}"
        print(
            f"#{row['id']} [{row['status']}] {row['name']} "
            f"ok={row['success']} fail={row['fail']} aliases=[{aliases}] verify={post}"
        )


def cmd_versions(args):
    con = _con()
    if args.pipeline:
        rows = pipelines.versions_for(con, args.pipeline, status=args.status or None)
    else:
        rows = pipelines.candidates(con, limit=args.limit)
    for row in rows:
        name = row["pipeline_name"] if "pipeline_name" in row.keys() else ""
        print(
            f"v#{row['id']} pipeline={row['pipeline_id']} version={row['version']} "
            f"status={row['status']} source={row['source']} {name}"
        )


def cmd_mission_create(args):
    con = _con()
    mission = pipelines.create_mission(con, args.name, args.description or "")
    print(f"mission #{mission['id']} {mission['name']!r} created")


def cmd_mission_add(args):
    con = _con()
    item = pipelines.add_mission_item(
        con,
        args.mission_id,
        pipeline_id=args.pipeline,
        pipeline_version_id=args.version,
        goal=args.goal or "",
        overrides=json.loads(args.overrides) if args.overrides else {},
    )
    print(f"mission item #{item['id']} added to mission #{args.mission_id}")


def cmd_missions(args):
    con = _con()
    for row in pipelines.list_missions(con):
        items = pipelines.list_mission_items(con, row["id"])
        print(f"#{row['id']} {row['name']!r} enabled={row['enabled']} items={len(items)}")


def cmd_mission_items(args):
    con = _con()
    for row in pipelines.list_mission_items(con, args.mission_id):
        print(
            f"#{row['id']} pos={row['position']} pipeline={row['pipeline_id']} "
            f"version={row['pipeline_version_id']} goal={row['goal']!r} enabled={row['enabled']}"
        )


def cmd_elements(args):
    con = _con()
    for row in con.execute("SELECT * FROM elements ORDER BY id"):
        print(
            f"#{row['id']} app={row['app_id']} [{row['status']}] {row['name']} "
            f"{row['locator_json']}"
        )


def cmd_runs(args):
    con = _con()
    for row in con.execute("SELECT * FROM runs ORDER BY id DESC LIMIT ?", (args.limit,)):
        print(
            f"#{row['id']} [{row['state']}] path={row['path']} engine={row['engine']} "
            f"ok={row['success']} verified={row['verified']} tokens={row['ai_cost']} "
            f"{row['goal'][:44]}"
        )


def cmd_trace(args):
    con = _con()
    print(debug.trace(con, args.run_id))


def cmd_export(args):
    con = _con()
    row = con.execute("SELECT * FROM runs WHERE id=?", (args.run_id,)).fetchone()
    if not row:
        print(f"run {args.run_id} not found")
        return
    bundle = debug.RunBundle(args.run_id)
    if not bundle.dir.exists():
        print(f"run {args.run_id} has no debug bundle")
        return
    path = bundle.export()
    print(f"exported: {path}")


def cmd_import_pipeline(args):
    con = _con()
    app_id = None
    if args.package:
        app_id = db.ensure_app(con, args.package)
    pid = pipeline_io.import_pipeline(
        con, args.file, goal=args.goal, app_id=app_id, entry=args.entry or None
    )
    print(f"proposal #{pid} ready to review (approve {pid})")


def cmd_export_pipeline(args):
    con = _con()
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=?", (args.id,)).fetchone()
    if not pipeline:
        print(f"pipeline {args.id} not found")
        return
    out = args.out or str(config.EXPORTS / f"{pipeline['name']}.json")
    path = pipeline_io.save_pipeline(con, pipeline, out)
    print(f"exported: {path}")


def cmd_pipeline_postcondition(args):
    con = _con()
    row = con.execute("SELECT * FROM pipelines WHERE id=?", (args.id,)).fetchone()
    if not row:
        print(f"pipeline {args.id} not found")
        return
    if args.json:
        try:
            post = json.loads(args.json)
        except json.JSONDecodeError as exc:
            print(f"invalid JSON: {exc}")
            return
        con.execute(
            "UPDATE pipelines SET postcondition_json=?, updated_at=datetime('now') WHERE id=?",
            (json.dumps(post, ensure_ascii=False), args.id),
        )
        con.commit()
        print(f"pipeline {args.id} postcondition={json.dumps(post, ensure_ascii=False)}")
    else:
        print(row["postcondition_json"] or "{}")


def cmd_app(args):
    con = _con()
    aliases = json.dumps(args.aliases or [], ensure_ascii=False)
    row = con.execute(
        "SELECT * FROM apps WHERE package_name=?", (args.package,)
    ).fetchone()
    if row:
        con.execute(
            "UPDATE apps SET name=?, aliases=? WHERE package_name=?",
            (args.name or row["name"], aliases, args.package),
        )
    else:
        con.execute(
            "INSERT INTO apps(package_name,name,aliases) VALUES(?,?,?)",
            (args.package, args.name or args.package, aliases),
        )
    con.commit()
    print(f"app {args.package} -> {args.name}")


def cmd_apps(args):
    con = _con()
    for row in con.execute("SELECT * FROM apps ORDER BY id"):
        print(f"#{row['id']} {row['package_name']} name={row['name']!r} aliases={row['aliases']}")


def cmd_evaluate(args):
    con = _con()
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=?", (args.id,)).fetchone()
    if not pipeline:
        print(f"pipeline {args.id} not found")
        return
    allowed, reason = safety.check(
        pipeline,
        assume_yes=getattr(args, "yes", False),
        interactive=sys.stdin.isatty(),
    )
    if not allowed:
        print(f"safety: {reason}")
        return
    try:
        summary = evaluate.evaluate_pipeline(con, pipeline, times=args.times)
    except Exception as exc:
        print(f"evaluate failed: {exc}")
        return
    print(
        f"eval #{summary['eval_id']}: passed {summary['passed']}/{summary['times']} "
        f"(verified {summary['verified']}/{summary['times']}) in "
        f"{summary['duration_ms']}ms"
    )


def cmd_evals(args):
    con = _con()
    for row in evaluate.latest(con, pipeline_id=args.pipeline, limit=args.limit):
        print(
            f"#{row['id']} pipeline={row['pipeline_id']} {row['passed']}/{row['times']} "
            f"verified={row['verified']} goal={row['goal'][:50]}"
        )


def cmd_policies(args):
    con = _con()
    for row in con.execute("SELECT * FROM policies ORDER BY priority, id"):
        print(
            f"#{row['id']} [{row['active']}] p{row['priority']} {row['name']} "
            f"{row['match_json']} -> {row['action_json']}"
        )


def cmd_cache(args):
    con = _con()
    if args.clear:
        print(f"cleared {db.cache_clear(con)} cached decisions")
        return
    row = con.execute("SELECT COUNT(*) c FROM decision_cache").fetchone()
    print(f"decision_cache entries: {row['c']}")


def cmd_debug(args):
    con = _con()
    if args.level:
        db.set_setting(con, "debug_level", args.level)
        print(f"debug_level={args.level}")
    else:
        print(f"debug_level={debug.debug_level(con)}")


def cmd_hint(args):
    con = _con()
    key = f"hint:{args.package}"
    if args.text:
        db.set_setting(con, key, args.text)
        print(f"{key} set")
    else:
        print(db.setting(con, key, "(none)"))


def cmd_hints(args):
    con = _con()
    for row in con.execute("SELECT key, value FROM settings WHERE key LIKE 'hint:%'"):
        print(f"{row['key']}: {row['value'][:120]}")


def cmd_tap_vision(args):
    dev = device.Device()
    image = dev.screencap()
    url, size = vision.DeepSeek.image_data_url(image)
    client = vision.DeepSeek()
    raw = client.chat(
        [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": f"Find this UI element and return its center point in the "
                        f"{size[0]}x{size[1]} image coordinate scale as JSON "
                        f'{{"point":[x,y],"found":true|false}}. Element: {args.description}',
                    },
                    {"type": "image_url", "image_url": {"url": url}},
                ],
            }
        ],
        json_mode=True,
        max_tokens=3000,
    )
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        print(f"model did not return JSON: {raw[:200]}")
        return
    if not data.get("found") or not data.get("point"):
        print("not found")
        return
    scale = dev.size()[0] / float(size[0])
    x, y = data["point"]
    dev.tap(x * scale, y * scale)
    print(f"tapped device ({int(x * scale)},{int(y * scale)}) tokens={client.last_usage.get('total_tokens')}")


def cmd_ask_vision(args):
    from . import maa_exec

    exec_ = maa_exec.MaaExec()
    image = exec_.image()
    url, size = vision.DeepSeek.image_data_url(image)
    client = vision.DeepSeek()
    raw = client.chat(
        [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": "Look at this Android screenshot carefully and answer the question. "
                        f"Question: {args.question}\n"
                        'Return JSON {"answer": true|false, "confidence": 0-1, "evidence": "what you see"}.',
                    },
                    {"type": "image_url", "image_url": {"url": url}},
                ],
            }
        ],
        json_mode=True,
        max_tokens=2000,
    )
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        print(raw[:400])
        return
    print(json.dumps(data, ensure_ascii=False))
    print(f"tokens={client.last_usage.get('total_tokens')}")


def cmd_ui(args):
    dev = device.Device()
    print(uiauto.summarize(dev.uiauto(), limit=args.limit))


def cmd_pkg(args):
    dev = device.Device()
    for package in dev.packages(args.pattern):
        print(package)


def cmd_reset(args):
    if config.DB_PATH.exists():
        config.DB_PATH.unlink()
    if config.RUNS.exists():
        import shutil

        shutil.rmtree(config.RUNS, ignore_errors=True)
    _con()
    print(f"reset {config.DB_PATH}")


def build_parser():
    parser = argparse.ArgumentParser(prog="maa_phone")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("goal", help="give the phone a goal")
    p.add_argument("text")
    p.add_argument("--yes", action="store_true", help="approve autonomy=confirm pipelines")
    p.add_argument("--engine", choices=["maa", "adb"], default="maa")
    p.add_argument("--force-ai", action="store_true")
    p.add_argument("--max-steps", type=int, default=None)
    p.add_argument("--pause-after", type=int, default=None)
    p.add_argument("--interrupt", choices=["ask", "queue", "now", "cancel"], default="ask")
    p.add_argument("--no-proposal", action="store_true")
    p.add_argument("--no-ai", action="store_true", help="skip AI naming for proposals")
    p.add_argument(
        "--verify",
        default="",
        help="JSON postcondition checked after an AI run, e.g. "
             "'{\"type\":\"pixel\",\"point\":[157,1233],\"rgb\":[251,114,153]}'",
    )
    p.set_defaults(func=cmd_goal)

    p = sub.add_parser("answer")
    p.add_argument("run_id", type=int)
    p.add_argument("text")
    p.add_argument("--engine", choices=["maa", "adb"], default=None)
    p.add_argument("--max-steps", type=int, default=None)
    p.add_argument("--no-ai", action="store_true")
    p.set_defaults(func=cmd_answer)

    p = sub.add_parser("thread")
    p.add_argument("--run", dest="run_id", type=int, default=None)
    p.add_argument("--limit", type=int, default=50)
    p.set_defaults(func=cmd_thread)

    p = sub.add_parser("resume")
    p.add_argument("run_id", type=int)
    p.add_argument("--yes", action="store_true", help="approve autonomy=confirm pipelines")
    p.add_argument("--engine", choices=["maa", "adb"], default=None)
    p.add_argument("--max-steps", type=int, default=None)
    p.add_argument("--pause-after", type=int, default=None)
    p.set_defaults(func=cmd_resume)

    p = sub.add_parser("process-queue")
    p.add_argument("--yes", action="store_true", help="approve autonomy=confirm pipelines")
    p.add_argument("--engine", choices=["maa", "adb"], default="maa")
    p.add_argument("--max-steps", type=int, default=None)
    p.set_defaults(func=cmd_process_queue)

    p = sub.add_parser("learn")
    p.add_argument("--no-ai", action="store_true")
    p.set_defaults(func=cmd_learn)

    sub.add_parser("proposals").set_defaults(func=cmd_proposals)

    p = sub.add_parser("approve")
    p.add_argument("id", type=int)
    p.set_defaults(func=cmd_approve)

    p = sub.add_parser("reject")
    p.add_argument("id", type=int)
    p.add_argument("--note", default="")
    p.set_defaults(func=cmd_reject)

    sub.add_parser("pipelines").set_defaults(func=cmd_pipelines)
    p = sub.add_parser("versions")
    p.add_argument("--pipeline", type=int, default=None)
    p.add_argument("--status", default="")
    p.add_argument("--limit", type=int, default=50)
    p.set_defaults(func=cmd_versions)

    p = sub.add_parser("mission-create")
    p.add_argument("name")
    p.add_argument("--description", default="")
    p.set_defaults(func=cmd_mission_create)

    p = sub.add_parser("mission-add")
    p.add_argument("mission_id", type=int)
    p.add_argument("--pipeline", type=int, default=None)
    p.add_argument("--version", type=int, default=None)
    p.add_argument("--goal", default="")
    p.add_argument("--overrides", default="")
    p.set_defaults(func=cmd_mission_add)

    sub.add_parser("missions").set_defaults(func=cmd_missions)

    p = sub.add_parser("mission-items")
    p.add_argument("mission_id", type=int)
    p.set_defaults(func=cmd_mission_items)

    sub.add_parser("elements").set_defaults(func=cmd_elements)

    p = sub.add_parser("runs")
    p.add_argument("--limit", type=int, default=20)
    p.set_defaults(func=cmd_runs)

    p = sub.add_parser("trace")
    p.add_argument("run_id", type=int)
    p.set_defaults(func=cmd_trace)

    p = sub.add_parser("export")
    p.add_argument("run_id", type=int)
    p.set_defaults(func=cmd_export)

    sub.add_parser("policies").set_defaults(func=cmd_policies)

    p = sub.add_parser("evaluate")
    p.add_argument("id", type=int)
    p.add_argument("--times", type=int, default=3)
    p.add_argument("--yes", action="store_true")
    p.set_defaults(func=cmd_evaluate)

    p = sub.add_parser("evals")
    p.add_argument("--pipeline", type=int, default=None)
    p.add_argument("--limit", type=int, default=10)
    p.set_defaults(func=cmd_evals)

    p = sub.add_parser("cache")
    p.add_argument("--clear", action="store_true")
    p.set_defaults(func=cmd_cache)

    p = sub.add_parser("debug")
    p.add_argument("level", nargs="?", choices=["off", "metadata", "screens", "full"])
    p.set_defaults(func=cmd_debug)

    p = sub.add_parser("hint")
    p.add_argument("package")
    p.add_argument("text", nargs="?", default="")
    p.set_defaults(func=cmd_hint)

    sub.add_parser("hints").set_defaults(func=cmd_hints)

    p = sub.add_parser("import-pipeline")
    p.add_argument("file")
    p.add_argument("goal")
    p.add_argument("--package", default="")
    p.add_argument("--entry", default="")
    p.set_defaults(func=cmd_import_pipeline)

    p = sub.add_parser("export-pipeline")
    p.add_argument("id", type=int)
    p.add_argument("--out", default="")
    p.set_defaults(func=cmd_export_pipeline)

    p = sub.add_parser("pipeline-postcondition")
    p.add_argument("id", type=int)
    p.add_argument("json", nargs="?", default="")
    p.set_defaults(func=cmd_pipeline_postcondition)

    p = sub.add_parser("app")
    p.add_argument("package")
    p.add_argument("name")
    p.add_argument("aliases", nargs="*")
    p.set_defaults(func=cmd_app)

    sub.add_parser("apps").set_defaults(func=cmd_apps)

    p = sub.add_parser("ui")
    p.add_argument("--limit", type=int, default=60)
    p.set_defaults(func=cmd_ui)

    p = sub.add_parser("tap-vision")
    p.add_argument("description")
    p.set_defaults(func=cmd_tap_vision)

    p = sub.add_parser("ask-vision")
    p.add_argument("question")
    p.set_defaults(func=cmd_ask_vision)

    p = sub.add_parser("pkg")
    p.add_argument("pattern", nargs="?", default="")
    p.set_defaults(func=cmd_pkg)

    sub.add_parser("reset").set_defaults(func=cmd_reset)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.func(args)
