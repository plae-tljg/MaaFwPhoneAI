import json
import sqlite3

from . import config


def connect(path=None):
    if path is not None and str(path) != ":memory:":
        path = str(path)
    else:
        path = str(path or config.DB_PATH)
    if path != ":memory:":
        config.DATA.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(path)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    return con


def init_db(con=None):
    con = con or connect()
    con.executescript(config.SCHEMA_PATH.read_text())
    _ensure_columns(con)
    con.commit()
    return con


def _ensure_columns(con):
    """Forward-only migrations for databases created by an older schema.sql."""
    tables = {
        "runs": (
            ("engine", "TEXT NOT NULL DEFAULT 'maa'"),
            ("bundle_path", "TEXT NOT NULL DEFAULT ''"),
            ("verified", "INTEGER NOT NULL DEFAULT 0"),
            ("verify_json", "TEXT NOT NULL DEFAULT '{}'"),
        ),
        "pipelines": (
            ("postcondition_json", "TEXT NOT NULL DEFAULT '{}'"),
        ),
    }
    for table, columns in tables.items():
        existing = {row["name"] for row in con.execute(f"PRAGMA table_info({table})")}
        for name, ddl in columns:
            if name not in existing:
                con.execute(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}")


def setting(con, key, default=None):
    row = con.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    return row["value"] if row else default


def set_setting(con, key, value):
    con.execute(
        "INSERT INTO settings(key,value) VALUES(?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)),
    )
    con.commit()


def ensure_app(con, package, name=None):
    row = con.execute("SELECT * FROM apps WHERE package_name=?", (package,)).fetchone()
    if row:
        return row["id"]
    cur = con.execute(
        "INSERT INTO apps(package_name,name,aliases) VALUES(?,?,'[]')",
        (package, name or package),
    )
    con.commit()
    return cur.lastrowid


def upsert_element(con, app_id, name, locator):
    con.execute(
        "INSERT INTO elements(app_id,name,locator_json) VALUES(?,?,?) "
        "ON CONFLICT(app_id,name) DO UPDATE SET locator_json=excluded.locator_json, "
        "status='live', updated_at=datetime('now')",
        (app_id, name, json.dumps(locator, ensure_ascii=False)),
    )
    con.commit()


def start_run(con, goal, app_id=None, pipeline_id=None, path="bootstrap", source="in_app", state="running", engine="maa"):
    cur = con.execute(
        "INSERT INTO runs(goal,app_id,pipeline_id,path,source,state,engine) VALUES(?,?,?,?,?,?,?)",
        (goal, app_id, pipeline_id, path, source, state, engine),
    )
    run_id = cur.lastrowid
    con.execute(
        "INSERT INTO messages(run_id,role,kind,content,source) VALUES(?,?,?,?,?)",
        (run_id, "user", "goal", goal, source),
    )
    con.commit()
    return run_id


def set_run_bundle(con, run_id, bundle_path):
    con.execute("UPDATE runs SET bundle_path=? WHERE id=?", (str(bundle_path), run_id))
    con.commit()


def finish_run(con, run_id, success, steps=None, ai_cost=0, duration_ms=0, error="",
               state=None, verified=None, verify=None):
    state = state or ("done" if success else "failed")
    con.execute(
        "UPDATE runs SET success=?, steps_json=?, ai_cost=?, duration_ms=?, error=?, state=?, "
        "verified=?, verify_json=?, finished_at=datetime('now') WHERE id=?",
        (
            int(bool(success)),
            json.dumps(steps or [], ensure_ascii=False),
            int(ai_cost),
            int(duration_ms),
            error,
            state,
            int(bool(verified)) if verified is not None else 0,
            json.dumps(verify or {}, ensure_ascii=False),
            run_id,
        ),
    )
    row = con.execute("SELECT goal FROM runs WHERE id=?", (run_id,)).fetchone()
    goal = row["goal"] if row else ""
    if success:
        content = f"Done: {goal}"
    else:
        content = f"Failed: {error or goal}"
    con.execute(
        "INSERT INTO messages(run_id,role,kind,content,payload_json,state,source) "
        "VALUES(?,?,?,?,?,'sent','in_app')",
        (
            run_id,
            "assistant",
            "result",
            content,
            json.dumps(
                {
                    "success": bool(success),
                    "verified": bool(verified) if verified is not None else False,
                    "ai_cost": int(ai_cost),
                    "duration_ms": int(duration_ms),
                },
                ensure_ascii=False,
            ),
        ),
    )
    con.commit()


def add_proposal(con, kind, candidate, source_run_id=None,
                 target_pipeline_id=None, target_version_id=None):
    cur = con.execute(
        "INSERT INTO proposals(kind,candidate_json,source_run_id,target_pipeline_id,target_version_id) "
        "VALUES(?,?,?,?,?)",
        (
            kind,
            json.dumps(candidate, ensure_ascii=False),
            source_run_id,
            target_pipeline_id,
            target_version_id,
        ),
    )
    con.commit()
    return cur.lastrowid


def add_message(con, role, kind, content, run_id=None, payload=None, source="in_app",
                state="sent"):
    cur = con.execute(
        "INSERT INTO messages(run_id,role,kind,content,payload_json,state,source) "
        "VALUES(?,?,?,?,?,?,?)",
        (
            run_id,
            role,
            kind,
            content or "",
            json.dumps(payload or {}, ensure_ascii=False),
            state,
            source,
        ),
    )
    con.commit()
    return cur.lastrowid


def messages(con, run_id=None, limit=50):
    if run_id is None:
        rows = con.execute(
            "SELECT * FROM messages ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
    else:
        rows = con.execute(
            "SELECT * FROM messages WHERE run_id=? ORDER BY id", (run_id,)
        ).fetchall()
    return list(reversed(rows)) if run_id is None else rows


def cache_get(con, key):
    con.execute(
        "UPDATE decision_cache SET hits=hits+1, last_used_at=datetime('now') WHERE key=?",
        (key,),
    )
    row = con.execute("SELECT * FROM decision_cache WHERE key=?", (key,)).fetchone()
    if row:
        con.commit()
    return row


def cache_put(con, key, goal, app, phash, prompt_hash, action):
    con.execute(
        "INSERT INTO decision_cache(key,goal,app,phash,prompt_hash,action_json) "
        "VALUES(?,?,?,?,?,?) "
        "ON CONFLICT(key) DO UPDATE SET action_json=excluded.action_json, "
        "last_used_at=datetime('now')",
        (key, goal, app, phash, prompt_hash, json.dumps(action, ensure_ascii=False)),
    )
    con.commit()


def cache_clear(con):
    removed = con.execute("SELECT COUNT(*) c FROM decision_cache").fetchone()["c"]
    con.execute("DELETE FROM decision_cache")
    con.commit()
    return removed


def rowdict(row):
    return dict(row) if row else None
