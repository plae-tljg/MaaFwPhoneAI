import json
import time

from . import compiler, config, db, debug, maa_exec, verifiers


def recognition_summary(node_detail):
    reco = getattr(node_detail, "recognition", None)
    if not reco:
        return {}
    summary = {"algorithm": str(getattr(reco, "algorithm", "")), "hit": bool(reco.hit)}
    if reco.box:
        summary["box"] = list(reco.box)
    best = getattr(reco, "best_result", None)
    if best is not None:
        score = getattr(best, "score", None)
        if score is not None:
            summary["score"] = round(float(score), 3)
        text = getattr(best, "text", None)
        if text:
            summary["text"] = text
    return summary


def _bump_element(con, pipeline, step, hit):
    name = (step.get("element") or "") if step else ""
    if not name or not pipeline["app_id"]:
        return
    if hit:
        con.execute(
            "UPDATE elements SET hits=hits+1, updated_at=datetime('now') "
            "WHERE app_id=? AND name=?",
            (pipeline["app_id"], name),
        )
    else:
        con.execute(
            "UPDATE elements SET misses=misses+1, updated_at=datetime('now') "
            "WHERE app_id=? AND name=?",
            (pipeline["app_id"], name),
        )


def run_pipeline(con, pipeline, goal=None, pause_after=None, resume_run=None, log=print):
    steps = compiler.compile_maa_steps(con, pipeline)
    postcondition = json.loads(pipeline["postcondition_json"] or "{}")
    run_id = resume_run["id"] if resume_run else None
    start_index = 0
    if resume_run:
        progress = json.loads(resume_run["progress_json"] or "{}")
        start_index = int(progress.get("step_index", 0))
        con.execute("UPDATE runs SET state='running' WHERE id=?", (run_id,))
        con.commit()
    exec_ = maa_exec.MaaExec()
    if run_id is None:
        run_id = db.start_run(
            con,
            goal or pipeline["goal"] or pipeline["name"],
            app_id=pipeline["app_id"],
            pipeline_id=pipeline["id"],
            path="pipeline",
            engine="maa",
        )
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
            "kind": "pipeline",
            "pipeline": pipeline["name"],
            "goal": goal or pipeline["goal"],
            "steps": len(steps),
            "engine": "maa",
            "debug_level": level,
            "device": exec_.device_name,
        }
    )
    started = time.time()
    status, error = "done", ""
    verify_ctx = verifiers.baseline(con, exec_, postcondition)
    for position in range(start_index, len(steps)):
        if pause_after is not None and (position - start_index) >= pause_after:
            con.execute(
                "UPDATE runs SET state='paused', progress_json=? WHERE id=?",
                (json.dumps({"step_index": position}), run_id),
            )
            con.commit()
            bundle.event({"type": "pause", "step": position})
            return run_id, "paused"
        item = steps[position]
        template = item.get("template")
        if template and not exec_.load_template(template):
            status, error = "failed", f"template missing: {template}"
            bundle.event({"type": "error", "step": position, "error": error})
            break
        event = {
            "type": "step",
            "index": position,
            "name": item["name"],
            "action": item["node"].get("action"),
        }
        if level in ("screens", "full"):
            event["screenshot"] = bundle.screenshot(f"step_{position}.png", exec_.image())
        ok, node_detail = exec_.run_node(item["name"], item["node"])
        event["ok"] = bool(ok)
        summary = recognition_summary(node_detail)
        if summary:
            event["recognition"] = summary
        reco = getattr(node_detail, "recognition", None)
        if level == "full" and reco is not None and getattr(reco, "draw_images", None):
            event["draw"] = bundle.draw(f"step_{position}.png", reco.draw_images[0])
        bundle.event(event)
        _bump_element(con, pipeline, item.get("source"), bool(ok))
        log(f"  step {position}: {item['name']} ok={ok} {json.dumps(summary, ensure_ascii=False)[:120]}")
        if not ok:
            status, error = "failed", f"step {position} not matched"
            break
    verified, verify_evidence = None, {"type": "none", "skipped": True}
    if status == "done" and postcondition.get("type") not in (None, "", "none"):
        verified, verify_evidence = verifiers.evaluate(
            con, exec_, postcondition, context=verify_ctx, app_id=pipeline["app_id"], log=log
        )
        bundle.event({"type": "verify", "ok": bool(verified), "evidence": verify_evidence})
        log(f"  verify: {json.dumps(verify_evidence, ensure_ascii=False)[:160]}")
        if not verified:
            status = "failed"
            error = f"postcondition failed: {json.dumps(verify_evidence, ensure_ascii=False)[:200]}"
    if status == "done":
        bundle.event({"type": "verified", "ok": True, "evidence": verify_evidence})
    duration = int((time.time() - started) * 1000)
    success = status == "done"
    if success:
        db.finish_run(
            con, run_id, True, duration_ms=duration,
            verified=verified, verify=verify_evidence,
        )
        con.execute(
            "UPDATE pipelines SET success=success+1, fail=0, status='live', "
            "last_run_at=datetime('now'), updated_at=datetime('now') WHERE id=?",
            (pipeline["id"],),
        )
    else:
        db.finish_run(
            con, run_id, False, duration_ms=duration, error=error,
            verified=verified, verify=verify_evidence,
        )
        con.execute(
            "UPDATE pipelines SET fail=fail+1, last_run_at=datetime('now') WHERE id=?",
            (pipeline["id"],),
        )
        bundle.event({"type": "failed", "error": error})
    con.commit()
    return run_id, status
