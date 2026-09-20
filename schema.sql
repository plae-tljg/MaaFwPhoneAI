-- maa-phone schema v1 (SQLite, Android-friendly)
--
-- Convention (docs/CONVENTIONS.md):
--   * pipeline/workflow = runtime MaaFW pipeline graph/state machine;
--   * skill             = Everything-Maa authoring instruction (docs only);
--   * tool              = MaaMCP authoring procedure (external).
--
-- The DB is the source of truth. A pipeline is a native MaaFW pipeline graph
-- (nodes with recognition/action/next/on_error) stored as data. The AI
-- proposes changes (candidate pipeline versions, element fixes, hints,
-- policies); a human approves before a version becomes live.
--
-- See docs/GOAL.md, docs/CONVENTIONS.md, docs/SCHEMA.md.

CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL DEFAULT ''
);

-- Known applications. `aliases` lets goals resolve app names in any language.
CREATE TABLE IF NOT EXISTS apps (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  package_name TEXT NOT NULL UNIQUE,
  name         TEXT NOT NULL DEFAULT '',
  aliases      TEXT NOT NULL DEFAULT '[]',   -- JSON: ["设置","settings","系統設定"]
  active       INTEGER NOT NULL DEFAULT 1,
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Screenshot-hash decision cache for the bootstrap AI fallback (cost control):
-- an unknown screen solved once is not paid for again.
CREATE TABLE IF NOT EXISTS decision_cache (
  key         TEXT PRIMARY KEY,
  goal        TEXT NOT NULL DEFAULT '',
  app         TEXT NOT NULL DEFAULT '',
  phash       TEXT NOT NULL DEFAULT '',
  prompt_hash TEXT NOT NULL DEFAULT '',
  action_json TEXT NOT NULL DEFAULT '{}',
  hits        INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  last_used_at TEXT
);

-- A logical pipeline/workflow for one goal. One row may have many candidate
-- or historical versions (`pipeline_versions`). Several pipelines may share
-- the same goal; the user/mission decides which one to run.
CREATE TABLE IF NOT EXISTS pipelines (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  app_id       INTEGER REFERENCES apps(id),
  name         TEXT NOT NULL,
  goal         TEXT NOT NULL DEFAULT '',      -- human/AI readable intent
  aliases      TEXT NOT NULL DEFAULT '[]',    -- JSON array of goal patterns
  entry        TEXT NOT NULL DEFAULT '',      -- graph entry node name (required for native graphs)
  definition_json TEXT NOT NULL DEFAULT '{}', -- MaaFW-native graph: {NodeName: {recognition/action/next/...}}
  postcondition_json TEXT NOT NULL DEFAULT '{}', -- deterministic success check
  status       TEXT NOT NULL DEFAULT 'draft', -- draft | live | broken | archived
  autonomy     TEXT NOT NULL DEFAULT 'confirm', -- auto | confirm | never
  source       TEXT NOT NULL DEFAULT 'ai',    -- ai | bootstrap | maamcp | manual | import
  success      INTEGER NOT NULL DEFAULT 0,
  fail         INTEGER NOT NULL DEFAULT 0,
  last_run_at  TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Each AI/MaaMCP/manual authoring attempt produces a concrete graph version.
-- Candidate versions are the Review queue for pipelines; approving one makes
-- it the current live version. This is how multiple first-time AI runs for
-- the same goal are recorded and consolidated.
CREATE TABLE IF NOT EXISTS pipeline_versions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  pipeline_id  INTEGER NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
  version      INTEGER NOT NULL DEFAULT 1,
  entry        TEXT NOT NULL DEFAULT '',      -- graph entry node name
  definition_json TEXT NOT NULL DEFAULT '{}', -- MaaFW-native graph: {NodeName: {recognition/action/next/...}}
  postcondition_json TEXT NOT NULL DEFAULT '{}',
  status       TEXT NOT NULL DEFAULT 'candidate', -- candidate | approved | superseded | rejected
  source       TEXT NOT NULL DEFAULT 'ai',    -- ai | bootstrap | maamcp | manual | import
  source_run_id INTEGER REFERENCES runs(id),   -- primary run that produced this version
  evidence_json TEXT NOT NULL DEFAULT '{}',    -- screenshots/log/eval evidence
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  reviewed_at  TEXT,
  UNIQUE(pipeline_id, version)
);

-- Many-to-many provenance: a version may consolidate several AI runs.
CREATE TABLE IF NOT EXISTS pipeline_version_runs (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  pipeline_version_id INTEGER NOT NULL REFERENCES pipeline_versions(id) ON DELETE CASCADE,
  run_id             INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
  role               TEXT NOT NULL DEFAULT 'source', -- source | replay | evidence
  UNIQUE(pipeline_version_id, run_id, role)
);

-- Shared, healable recognition/locator rows referenced by pipeline nodes.
-- `locator_json` holds native MaaFW recognition params (OCR expected/roi,
-- TemplateMatch template/threshold, ColorMatch bounds, ...). Template image
-- files live in the versioned MaaFW resource bundle and are referenced here.
CREATE TABLE IF NOT EXISTS elements (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  app_id       INTEGER REFERENCES apps(id),
  name         TEXT NOT NULL,
  locator_json TEXT NOT NULL DEFAULT '{}',
  hits         INTEGER NOT NULL DEFAULT 0,
  misses       INTEGER NOT NULL DEFAULT 0,
  status       TEXT NOT NULL DEFAULT 'live',  -- live | stale
  updated_at   TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(app_id, name)
);

-- A user-facing persistent mission list. A mission is an ordered list of
-- mission items (pipelines/goals) the user wants to run together or later.
CREATE TABLE IF NOT EXISTS missions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT NOT NULL,
  description  TEXT NOT NULL DEFAULT '',
  enabled      INTEGER NOT NULL DEFAULT 1,
  schedule_json TEXT NOT NULL DEFAULT '{}',   -- reserved for scheduled/looped missions
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- One ordered item in a mission. It points at a pipeline (and optionally a
-- specific version; null = current live version) plus per-run overrides.
CREATE TABLE IF NOT EXISTS mission_items (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  mission_id   INTEGER NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
  position     INTEGER NOT NULL DEFAULT 0,
  pipeline_id  INTEGER REFERENCES pipelines(id),
  pipeline_version_id INTEGER REFERENCES pipeline_versions(id),
  goal         TEXT NOT NULL DEFAULT '',
  overrides_json TEXT NOT NULL DEFAULT '{}',
  enabled      INTEGER NOT NULL DEFAULT 1,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Durable mission queue. `state` is queued|running|paused|done|failed|cancelled;
-- the runner synchronizes it with runs.mission_item_id so queue execution
-- survives Activity recreation and can be inspected in the Data/Review UI.
CREATE TABLE IF NOT EXISTS mission_queue (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  mission_id     INTEGER NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
  mission_item_id INTEGER NOT NULL REFERENCES mission_items(id) ON DELETE CASCADE,
  position       INTEGER NOT NULL DEFAULT 0,
  state          TEXT NOT NULL DEFAULT 'queued',
  run_id         INTEGER REFERENCES runs(id),
  error          TEXT NOT NULL DEFAULT '',
  created_at     TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_mission_queue_mission ON mission_queue(mission_id, position);

-- One execution record. Pipeline runs link to pipeline/version; bootstrap AI
-- runs record the trajectory that can later be consolidated into a candidate
-- pipeline version. `steps_json` here is the run trajectory/evidence, not a
-- pipeline definition.
CREATE TABLE IF NOT EXISTS runs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  goal        TEXT NOT NULL DEFAULT '',
  app_id      INTEGER REFERENCES apps(id),
  pipeline_id INTEGER REFERENCES pipelines(id),
  pipeline_version_id INTEGER REFERENCES pipeline_versions(id),
  mission_item_id INTEGER REFERENCES mission_items(id),
  path        TEXT NOT NULL DEFAULT 'bootstrap', -- pipeline | bootstrap | manual
  success     INTEGER NOT NULL DEFAULT 0,
  steps_json  TEXT NOT NULL DEFAULT '[]',     -- run trajectory/evidence
  ai_cost     INTEGER NOT NULL DEFAULT 0,     -- tokens spent (0 for pipeline runs)
  duration_ms INTEGER NOT NULL DEFAULT 0,
  error       TEXT NOT NULL DEFAULT '',
  verified    INTEGER NOT NULL DEFAULT 0,     -- did the run pass its postcondition?
  verify_json TEXT NOT NULL DEFAULT '{}',     -- verifier evidence (before/after, box, score)
  state       TEXT NOT NULL DEFAULT 'done',   -- queued|running|paused|needs_input|done|failed|cancelled
  source      TEXT NOT NULL DEFAULT 'in_app', -- in_app|tile|notification|share|schedule
  engine      TEXT NOT NULL DEFAULT 'maa',    -- maa | adb (prototype engines)
  bundle_path TEXT NOT NULL DEFAULT '',       -- debug bundle dir for this run
  progress_json TEXT NOT NULL DEFAULT '{}',   -- step index, vars, pending question
  started_at  TEXT NOT NULL DEFAULT (datetime('now')),
  finished_at TEXT
);

-- Safety gates for goals that have no pipeline row (bootstrap AI included):
-- match_json matches the goal/app, action_json is allow|confirm|deny.
-- AI may propose rows through proposals.kind='policy'; human Review gates.
CREATE TABLE IF NOT EXISTS policies (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  app_id      INTEGER REFERENCES apps(id),
  name        TEXT NOT NULL DEFAULT '',
  priority    INTEGER NOT NULL DEFAULT 100,
  match_json  TEXT NOT NULL DEFAULT '{}',
  action_json TEXT NOT NULL DEFAULT '{"type":"confirm"}',
  active      INTEGER NOT NULL DEFAULT 1,
  created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

-- The IO stream: goals, questions, answers, status and learning cards.
-- Runs are the execution attached to these messages.
CREATE TABLE IF NOT EXISTS messages (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id       INTEGER REFERENCES runs(id),
  role         TEXT NOT NULL,               -- user | assistant | system
  kind         TEXT NOT NULL,               -- goal|question|answer|status|card|proposal|result
  content      TEXT NOT NULL DEFAULT '',
  payload_json TEXT NOT NULL DEFAULT '{}',
  state        TEXT NOT NULL DEFAULT 'sent',-- pending|sent|answered|dismissed|expired
  source       TEXT NOT NULL DEFAULT 'in_app',
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  answered_at  TEXT
);

-- Replay-K evaluation results: trust evidence for a pipeline version before
-- it is approved or while it is live.
CREATE TABLE IF NOT EXISTS eval_runs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  pipeline_id  INTEGER REFERENCES pipelines(id),
  pipeline_version_id INTEGER REFERENCES pipeline_versions(id),
  goal         TEXT NOT NULL DEFAULT '',
  times        INTEGER NOT NULL DEFAULT 1,
  passed       INTEGER NOT NULL DEFAULT 0,
  failed       INTEGER NOT NULL DEFAULT 0,
  verified     INTEGER NOT NULL DEFAULT 0,
  summary_json TEXT NOT NULL DEFAULT '{}',
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  finished_at  TEXT
);

CREATE TABLE IF NOT EXISTS eval_items (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  eval_run_id INTEGER NOT NULL REFERENCES eval_runs(id),
  run_id      INTEGER REFERENCES runs(id),
  success     INTEGER NOT NULL DEFAULT 0,
  verified    INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  error       TEXT NOT NULL DEFAULT ''
);

-- Generic Review queue for AI-authored changes that are not a whole pipeline
-- version: pipeline_fix|element_fix|alias|hint|policy. Candidate pipeline
-- versions live in `pipeline_versions.status='candidate'`.
CREATE TABLE IF NOT EXISTS proposals (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  kind           TEXT NOT NULL,               -- pipeline_fix|element_fix|alias|hint|policy
  target_pipeline_id INTEGER REFERENCES pipelines(id),
  target_version_id INTEGER REFERENCES pipeline_versions(id),
  source_run_id  INTEGER REFERENCES runs(id),
  candidate_json TEXT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'pending', -- pending|approved|rejected
  created_at     TEXT NOT NULL DEFAULT (datetime('now')),
  reviewed_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_pipelines_status    ON pipelines(status, app_id);
CREATE INDEX IF NOT EXISTS idx_pipelines_goal      ON pipelines(goal);
CREATE INDEX IF NOT EXISTS idx_versions_pipeline   ON pipeline_versions(pipeline_id, status);
CREATE INDEX IF NOT EXISTS idx_versions_status     ON pipeline_versions(status);
CREATE INDEX IF NOT EXISTS idx_mission_items       ON mission_items(mission_id, position);
CREATE INDEX IF NOT EXISTS idx_elements_app        ON elements(app_id, status);
CREATE INDEX IF NOT EXISTS idx_runs_started        ON runs(started_at);
CREATE INDEX IF NOT EXISTS idx_runs_goal           ON runs(goal);
CREATE INDEX IF NOT EXISTS idx_runs_pipeline       ON runs(pipeline_id, pipeline_version_id);
CREATE INDEX IF NOT EXISTS idx_proposals_status    ON proposals(status);
CREATE INDEX IF NOT EXISTS idx_messages_run        ON messages(run_id, id);
CREATE INDEX IF NOT EXISTS idx_policies_active     ON policies(active, priority);
CREATE INDEX IF NOT EXISTS idx_eval_items_run      ON eval_items(eval_run_id);
