"""Pipeline, version and mission operations (fresh v1 schema).

A pipeline is a logical workflow for one goal. Each authoring attempt
(bootstrap AI run, import, manual edit) creates a ``pipeline_versions`` row.
Candidate versions are reviewed; approving one makes it the live definition
of the pipeline. A mission is a user-facing ordered list of pipeline items.
"""

import json
from datetime import datetime

from . import db


def _now():
    return datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")


def _slug(value, fallback="pipeline"):
    text = str(value or "").strip().lower()
    out = "".join(ch if ch.isalnum() else "_" for ch in text).strip("_")
    return (out or fallback)[:40]


def ensure_pipeline(con, goal, app_id=None, name=None, source="ai", aliases=None):
    """Return a draft/live pipeline row for this goal/app, creating one if needed."""
    row = con.execute(
        "SELECT * FROM pipelines WHERE goal=? AND COALESCE(app_id,-1)=COALESCE(?,-1) "
        "AND status IN ('draft','live') ORDER BY CASE status WHEN 'live' THEN 0 ELSE 1 END, id LIMIT 1",
        (goal, app_id),
    ).fetchone()
    if row:
        return row
    pipeline_name = name or _slug(goal)
    cur = con.execute(
        "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,status,source) "
        "VALUES(?,?,?,?, '{}', 'draft', ?)",
        (
            app_id,
            pipeline_name,
            goal,
            json.dumps(aliases or [goal], ensure_ascii=False),
            source,
        ),
    )
    con.commit()
    return con.execute("SELECT * FROM pipelines WHERE id=?", (cur.lastrowid,)).fetchone()


def add_candidate(
    con,
    pipeline_id,
    definition,
    *,
    entry="",
    postcondition=None,
    source="ai",
    source_run_id=None,
    evidence=None,
    run_role="source",
):
    """Create a new candidate version for a pipeline and link its source run."""
    row = con.execute(
        "SELECT COALESCE(MAX(version),0)+1 AS next_version FROM pipeline_versions WHERE pipeline_id=?",
        (pipeline_id,),
    ).fetchone()
    version = int(row["next_version"])
    cur = con.execute(
        "INSERT INTO pipeline_versions(pipeline_id,version,entry,definition_json,"
        "postcondition_json,status,source,source_run_id,evidence_json) "
        "VALUES(?,?,?,?,?, 'candidate', ?,?,?)",
        (
            pipeline_id,
            version,
            entry or "",
            json.dumps(definition, ensure_ascii=False),
            json.dumps(postcondition or {}, ensure_ascii=False),
            source,
            source_run_id,
            json.dumps(evidence or {}, ensure_ascii=False),
        ),
    )
    version_id = cur.lastrowid
    if source_run_id is not None:
        con.execute(
            "INSERT OR IGNORE INTO pipeline_version_runs(pipeline_version_id,run_id,role) VALUES(?,?,?)",
            (version_id, source_run_id, run_role),
        )
    con.commit()
    return version_id


def approve_version(con, version_id):
    """Approve a candidate version and make it the pipeline's live definition."""
    version = con.execute("SELECT * FROM pipeline_versions WHERE id=?", (version_id,)).fetchone()
    if version is None:
        raise ValueError(f"pipeline version {version_id} not found")
    if version["status"] == "approved":
        return con.execute("SELECT * FROM pipelines WHERE id=?", (version["pipeline_id"],)).fetchone()
    if version["status"] != "candidate":
        raise ValueError(f"pipeline version {version_id} is {version['status']}, not candidate")

    con.execute(
        "UPDATE pipeline_versions SET status='superseded', reviewed_at=? "
        "WHERE pipeline_id=? AND status='approved'",
        (_now(), version["pipeline_id"]),
    )
    con.execute(
        "UPDATE pipeline_versions SET status='approved', reviewed_at=? WHERE id=?",
        (_now(), version_id),
    )
    con.execute(
        "UPDATE pipelines SET entry=?, definition_json=?, postcondition_json=?, "
        "status='live', updated_at=? WHERE id=?",
        (
            version["entry"] or "",
            version["definition_json"],
            version["postcondition_json"] or "{}",
            _now(),
            version["pipeline_id"],
        ),
    )
    con.commit()
    return con.execute("SELECT * FROM pipelines WHERE id=?", (version["pipeline_id"],)).fetchone()


def reject_version(con, version_id):
    version = con.execute("SELECT * FROM pipeline_versions WHERE id=?", (version_id,)).fetchone()
    if version is None:
        raise ValueError(f"pipeline version {version_id} not found")
    if version["status"] not in ("candidate", "rejected"):
        raise ValueError(f"pipeline version {version_id} is {version['status']}, not candidate")
    con.execute(
        "UPDATE pipeline_versions SET status='rejected', reviewed_at=? WHERE id=?",
        (_now(), version_id),
    )
    con.commit()
    return f"pipeline version {version_id} rejected"


def versions_for(con, pipeline_id, status=None):
    """List versions newest-first, optionally filtered by status."""
    if status:
        return con.execute(
            "SELECT * FROM pipeline_versions WHERE pipeline_id=? AND status=? "
            "ORDER BY version DESC",
            (pipeline_id, status),
        ).fetchall()
    return con.execute(
        "SELECT * FROM pipeline_versions WHERE pipeline_id=? ORDER BY version DESC",
        (pipeline_id,),
    ).fetchall()


def latest_live_version(con, pipeline_id):
    return con.execute(
        "SELECT * FROM pipeline_versions WHERE pipeline_id=? AND status='approved' "
        "ORDER BY version DESC LIMIT 1",
        (pipeline_id,),
    ).fetchone()


def candidates(con, limit=50):
    return con.execute(
        "SELECT v.*, p.name AS pipeline_name, p.goal AS pipeline_goal "
        "FROM pipeline_versions v JOIN pipelines p ON p.id=v.pipeline_id "
        "WHERE v.status='candidate' ORDER BY v.id DESC LIMIT ?",
        (limit,),
    ).fetchall()


# ---------------------------------------------------------------------------
# Missions


def create_mission(con, name, description="", schedule=None):
    cur = con.execute(
        "INSERT INTO missions(name,description,schedule_json) VALUES(?,?,?)",
        (name, description, json.dumps(schedule or {}, ensure_ascii=False)),
    )
    con.commit()
    return con.execute("SELECT * FROM missions WHERE id=?", (cur.lastrowid,)).fetchone()


def add_mission_item(
    con,
    mission_id,
    *,
    goal="",
    pipeline_id=None,
    pipeline_version_id=None,
    overrides=None,
    position=None,
    enabled=True,
):
    if position is None:
        row = con.execute(
            "SELECT COALESCE(MAX(position),0)+1 AS next_position FROM mission_items WHERE mission_id=?",
            (mission_id,),
        ).fetchone()
        position = int(row["next_position"])
    cur = con.execute(
        "INSERT INTO mission_items(mission_id,position,pipeline_id,pipeline_version_id,"
        "goal,overrides_json,enabled) VALUES(?,?,?,?,?,?,?)",
        (
            mission_id,
            position,
            pipeline_id,
            pipeline_version_id,
            goal,
            json.dumps(overrides or {}, ensure_ascii=False),
            1 if enabled else 0,
        ),
    )
    con.commit()
    return con.execute("SELECT * FROM mission_items WHERE id=?", (cur.lastrowid,)).fetchone()


def list_missions(con):
    return con.execute("SELECT * FROM missions ORDER BY id").fetchall()


def list_mission_items(con, mission_id):
    return con.execute(
        "SELECT * FROM mission_items WHERE mission_id=? ORDER BY position, id",
        (mission_id,),
    ).fetchall()


def mission_item_pipeline(con, item):
    """Return (pipeline, version) for a mission item, respecting explicit version."""
    pipeline = con.execute("SELECT * FROM pipelines WHERE id=?", (item["pipeline_id"],)).fetchone()
    if item["pipeline_version_id"]:
        version = con.execute(
            "SELECT * FROM pipeline_versions WHERE id=?", (item["pipeline_version_id"],)
        ).fetchone()
    else:
        version = latest_live_version(con, item["pipeline_id"]) if item["pipeline_id"] else None
    return pipeline, version
