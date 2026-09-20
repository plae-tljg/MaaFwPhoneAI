# Schema — pipelines, versions and missions

Fresh v1 schema (no migration needed; the app is reinstalled on a clean DB).
Conventions are in [`CONVENTIONS.md`](CONVENTIONS.md).

## 1. Core idea

A **pipeline** is a logical reusable workflow for one goal. Because one goal
can be solved by several first-time AI runs, a pipeline owns many
**pipeline versions**. Each AI / MaaMCP / human authoring attempt produces a
candidate version; Review approves one as the live version. The user can add
pipelines to a **mission list** and run them later.

```
first-time AI run(s) ──► candidate pipeline_version(s)
                                │ Review / compare / edit
                                ▼
                       approved version = live pipeline
                                │ add to mission
                                ▼
                         mission_item ──► run (path='pipeline')
```

## 2. Tables

### `pipelines` — logical workflow

| Column | Notes |
|---|---|
| `id` | pipeline id |
| `app_id` | optional app scope |
| `name`, `goal`, `aliases` | identity and resolver patterns |
| `entry` | graph entry node |
| `definition_json` | native MaaFW pipeline graph (`{nodeName: node}`) or legacy step array |
| `postcondition_json` | deterministic success condition |
| `status` | `draft \| live \| broken \| archived` |
| `autonomy` | `auto \| confirm \| never` |
| `source` | `ai \| bootstrap \| maamcp \| manual \| import` |
| `success`, `fail`, `last_run_at` | stats |
| `created_at`, `updated_at` | provenance |

There is **no unique constraint on `goal`**: multiple pipelines may target
the same goal (different versions/strategies). Selection is by resolver
score, status, stats, or an explicit mission item.

### `pipeline_versions` — concrete graph snapshots

| Column | Notes |
|---|---|
| `id` | version id |
| `pipeline_id` | owning pipeline |
| `version` | monotonic per pipeline |
| `entry`, `definition_json`, `postcondition_json` | the graph |
| `status` | `candidate \| approved \| superseded \| rejected` |
| `source` | ai / bootstrap / maamcp / manual / import |
| `source_run_id` | primary AI run that produced it |
| `evidence_json` | screenshots/log/eval references |
| `created_at`, `reviewed_at` | review trail |

Candidate versions are the Review queue for pipelines. Approving one marks
the previous live version `superseded` and updates the owning `pipelines`
row.

### `pipeline_version_runs` — consolidation provenance

A version may consolidate several AI runs. This join table records which
runs were used as sources, replays or evidence:

| Column | Notes |
|---|---|
| `pipeline_version_id`, `run_id` | relation |
| `role` | `source \| replay \| evidence` |

### `missions` / `mission_items` — user's run list

- `missions`: name, description, enabled, schedule, timestamps.
- `mission_items`: ordered (`position`) links to `pipelines` and optionally a
  specific `pipeline_version_id`; `goal` and `overrides_json` allow per-run
  variation; `enabled`.

Running a mission item creates a `runs` row with `path='pipeline'`,
`pipeline_id`, `pipeline_version_id` and `mission_item_id`.

### `runs` — execution evidence

Now links to pipeline/version/mission:

| Column | Notes |
|---|---|
| `pipeline_id`, `pipeline_version_id`, `mission_item_id` | what was run |
| `path` | `pipeline \| bootstrap \| manual` |
| `steps_json` | run trajectory/evidence (not the pipeline definition) |
| `success`, `verified`, `verify_json`, `error`, `ai_cost` | result/evidence |

### `elements`

Shared, healable locator rows referenced by pipeline nodes. `locator_json`
keeps native MaaFW fields; template image files live in the versioned MaaFW
resource bundle and are referenced from here.

### `proposals`

Generic review queue for changes that are not a whole pipeline version:
`pipeline_fix | element_fix | alias | hint | policy`. Pipeline candidates do
not need a `proposals` row; they are `pipeline_versions.status='candidate'`.

### Other tables

- `apps`, `settings`, `decision_cache`, `policies`, `messages`, `eval_runs`,
  `eval_items` keep their roles.

## 3. Pipeline versions in the bootstrap/import flow

1. A bootstrap AI run records its trajectory in `runs`.
2. If verified, the AI/import flow creates or reuses a `pipelines` row for
   the goal and inserts a `pipeline_versions` row:
   - `status='candidate'`;
   - `definition_json` = native MaaFW graph (not raw point-only when
     possible);
   - `source_run_id` + `pipeline_version_runs` evidence.
3. Review shows candidate versions side by side; the human can:
   - approve one as live;
   - reject/supersede others;
   - add the live pipeline to a mission.
4. A mission run creates `runs(path='pipeline')`; failures create
   `pipeline_fix`/`element_fix` proposals or new candidate versions.

## 4. Implementation status

Implemented in the fresh v1 source:

- `schema.sql` + Android asset create `pipelines`, `pipeline_versions`,
  `pipeline_version_runs`, `missions`, `mission_items`;
- bootstrap `learn.propose_from_run` and `pipeline_io.import_pipeline` create a
  candidate `pipeline_versions` row linked to the source run and file a
  `pipeline_new` proposal;
- `learn.approve` syncs the candidate JSON, approves the version and updates
  the owning pipeline;
- CLI: `versions`, `missions`, `mission-create`, `mission-add`,
  `mission-items`;
- Android `BrainDb` has the same backend helpers; `Learner` creates/approves
  pipeline versions; Data tab lists the new tables.

Still pending: version-comparison Review UI on Android, mission UI/runner,
and graph-native execution of `next`/`on_error` without flattening.

## 5. Why this shape

- **Multiple AI runs per goal** are first-class: every run can produce a
  version; `pipeline_version_runs` records consolidation.
- **One logical goal can have several pipelines**: no `UNIQUE(goal)`; the
  mission/Review chooses.
- **AI can freely edit graphs** because a version is JSON data.
- **Mission list is explicit**, not hidden in run state.
- **Evidence is linked**, so every version can be traced to the runs that
  produced/proved it.
- **Everything-Maa skills stay docs** and are loaded per family; they are not
  runtime rows.
