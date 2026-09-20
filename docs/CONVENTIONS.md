# Conventions — pipelines, skills and tools

This file fixes the vocabulary so the DB, code, docs and agent instructions
stop using the same words for different things.

## 1. Terms

| Term | Meaning | Where it lives |
|---|---|---|
| **Pipeline** (or **workflow**) | A runtime state machine in the MaaFramework pipeline format: nodes with recognition/action, `next`/`on_error`, anchors, entry. This is the reusable automation artifact the AI can propose/edit. | target DB table **`pipelines`** (`definition_json` + `entry`) |
| **Skill** | Authoring/debugging instructions for an AI agent: when to use OCR vs TemplateMatch vs ColorMatch, ROI, `order_by`/`index`, how to crop a reference image, how to test a node. Examples: the Everything-Maa `SKILL.md` families. Skills are not runtime state machines. | `docs/maa-skills/` files, loaded lazily by family |
| **Tool** | A procedure that performs one authoring step for the agent (screencap, OCR, compute/crop ROI image, save pipeline, benchmark node). | MaaMCP, external dev-time server |
| **Element / locator** | Reusable recognition parameters referenced by pipeline nodes (OCR expected/roi, TemplateMatch template/threshold, ColorMatch bounds, …). | `elements` table (shared, healable locator rows) |
| **Asset / template** | Cropped image file referenced by a template locator. | versioned MaaFW resource bundle, e.g. `files/pi/brain/res_N/image/` |
| **Run** | One execution record: pipeline replay, bootstrap/LLM attempt, manual test. | `runs` table; proposal: `path = pipeline \| bootstrap \| manual` |
| **Proposal** | An AI-authored change awaiting Review. | `proposals` table; kinds: `pipeline_new`, `pipeline_fix`, `element_fix`, `alias`, `hint`, `policy` |
| **Bootstrap** | Temporary LLM screenshot→action loop used only for first contact with an unknown app/screen. Never promoted directly as a pipeline. | `AgentRunner` / `proto/agent_maa`; `path = bootstrap` |
| **Everything-Maa** | Repo of agent skills (instructions). | vendored at `docs/maa-skills/`, upstream pin recorded |
| **MaaMCP** | Repo of authoring tools/procedures. | external PC MCP server, never embedded in the APK |
| **MaaFramework** | Runtime execution/recognition engine. | library/`.so`, native pipeline protocol |
| **MaaFwApp** | Android privileged shell and runner. | `android/` fork |

## 2. Naming rules

1. Never call a runtime pipeline/workflow a "skill".
2. Never call an Everything-Maa authoring instruction a "pipeline" or
   "workflow".
3. AI authoring instructions are **skills**; runtime state machines are
   **pipelines**.
4. A pipeline row is JSON data (`definition_json`); it is not code.
5. New runtime behavior should first try to be a pipeline graph/node change,
   not a new Kotlin/Python branch.
6. The bootstrap LLM loop is exploratory only; it can write drafts/proposals,
   not live pipelines.
7. Do not use "AI memory", "agent skill" or "workflow" interchangeably in code
   names or DB columns. Follow this table.

## 3. Fresh schema, no legacy aliases

The fresh v1 schema already uses the target names:
`pipelines`, `pipeline_versions`, `pipeline_version_runs`, `missions`,
`mission_items`, `runs.pipeline_id`, `runs.pipeline_version_id`,
`path='pipeline|bootstrap|manual'`, and pipeline version candidates instead
of `proposals.kind='pipeline_new'`. No migration is needed because the app
is reinstalled on a clean DB. See [`SCHEMA.md`](SCHEMA.md).
