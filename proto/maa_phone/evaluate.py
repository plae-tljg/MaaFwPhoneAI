"""Replay-K evaluation — evidence before a pipeline is trusted.

chatbot-ai gates knowledge with `eval_runs`/`eval_items`; the phone
equivalent is replaying a pipeline K times and recording success + postcondition
verification per attempt. On the PC prototype this drives the real device
through the normal runner; on Android the same loop can use MaaFW
Record/Replay/Dbg controllers for a cheaper, deterministic pass.
"""

import json
import time

from . import compiler, db, runner


def evaluate_pipeline(con, pipeline, times=3, goal=None, log=print):
    """Run a pipeline ``times`` times; return a summary dict.

    Each attempt is a normal run row (with postcondition verification), so
    the evaluation is auditable and feeds `elements.hits/misses`.
    """
    times = max(1, int(times))
    nodes = compiler.compile_maa_steps(con, pipeline)
    if not nodes:
        raise ValueError(f"pipeline {pipeline['id']} has no runnable compiled steps")
    goal = goal or pipeline["goal"] or pipeline["name"]
    cur = con.execute(
        "INSERT INTO eval_runs(pipeline_id,goal,times) VALUES(?,?,?)",
        (pipeline["id"], goal, times),
    )
    eval_id = cur.lastrowid
    con.commit()
    passed = failed = verified_count = 0
    items = []
    started = time.time()
    for attempt in range(times):
        log(f"  eval {eval_id}: attempt {attempt + 1}/{times}")
        run_id, status = runner.run_pipeline(con, pipeline, goal=goal, log=log)
        row = con.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
        success = bool(row and row["success"])
        verified = bool(row and row["verified"])
        passed += int(success)
        failed += int(not success)
        verified_count += int(verified)
        con.execute(
            "INSERT INTO eval_items(eval_run_id,run_id,success,verified,duration_ms,error) "
            "VALUES(?,?,?,?,?,?)",
            (
                eval_id,
                run_id,
                int(success),
                int(verified),
                int(row["duration_ms"] if row else 0),
                str(row["error"] if row else status),
            ),
        )
        con.commit()
        items.append({"run_id": run_id, "success": success, "verified": verified})
    summary = {
        "eval_id": eval_id,
        "pipeline_id": pipeline["id"],
        "pipeline": pipeline["name"],
        "times": times,
        "passed": passed,
        "failed": failed,
        "verified": verified_count,
        "rate": round(passed / times, 3),
        "duration_ms": int((time.time() - started) * 1000),
        "items": items,
    }
    con.execute(
        "UPDATE eval_runs SET passed=?, failed=?, verified=?, summary_json=?, "
        "finished_at=datetime('now') WHERE id=?",
        (
            passed,
            failed,
            verified_count,
            json.dumps(summary, ensure_ascii=False),
            eval_id,
        ),
    )
    con.commit()
    return summary


def latest(con, pipeline_id=None, limit=10):
    if pipeline_id is None:
        return con.execute(
            "SELECT * FROM eval_runs ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
    return con.execute(
        "SELECT * FROM eval_runs WHERE pipeline_id=? ORDER BY id DESC LIMIT ?",
        (pipeline_id, limit),
    ).fetchall()
