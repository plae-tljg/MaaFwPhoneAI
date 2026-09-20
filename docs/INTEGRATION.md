# Integration — the MAA stack and maa-phone

This is the canonical document for how the mature MAA projects fit into
maa-phone: **MaaFramework**, **MaaFwApp**, **MaaMCP** and
**Everything-Maa**. It complements [`GOAL.md`](GOAL.md) (goal/central
idea), [`WORKING_STYLE.md`](WORKING_STYLE.md) (the AI-maintained DB
method), [`IO.md`](IO.md) (the human interaction contract) and
[`MAA_STACK.md`](MAA_STACK.md) (exact revision pins and license
boundaries).

> **Implementation status:** the field contract below is implemented in
> the PC prototype and partially in Android M1/M3.2. The actual MaaMCP
> authoring pass (produce a native Bilibili pipeline, import, review,
> replay verified) is still the next step; see [`STATUS.md`](STATUS.md).

## 0. The single principle

> MaaFramework is the mature **recognition/action engine**; MaaFwApp is the
> mature **Android privileged shell**; MaaMCP + Everything-Maa are the
> mature **workflow authoring/pattern-extraction/benchmark bench**.
> maa-phone owns the **knowledge DB, resolver, compiler, verification,
> learning loop and IO** — and stores MaaFW's *native protocol fields* as
> data. The compiler resolves element references, fills defaults and
> validates; it does not reimplement OCR, template matching or ROI math.

```
IO: tile / notification / float ball / Assistant / Review          (ours)
        │ enqueue_goal / messages / proposals
        ▼
Brain: SQLite knowledge DB + resolver + compiler + learner + IO    (ours; Kotlin on device)
        │  compiled MaaFW nodes
        ▼
MaaFwApp fork: app process ──AIDL──► privileged process            (reused shell)
   Assistant/Review/Library UI         MaaRunner: Resource/Tasker/Controller
                                        └── MaaFramework .so + AndroidNativeControlUnit
        ▲
        │ pipeline JSON / benchmark / debug
MaaMCP + Everything-Maa (PC, dev-time): authoring bench                 (external)
```

Reference checkouts: `../../00ref/MaaFramework`,
`../../00ref/MaaFwApp`, `../../00ref/MaaMCP`,
`../../00ref/Everything-Maa`, plus the method reference
`../../00ref/chatbot-ai`. Exact pins and roles:
[`MAA_STACK.md`](MAA_STACK.md).

Licenses: MaaFramework **LGPL-3.0** (linked as a library);
MaaFwApp and MaaMCP are **AGPL-3.0** (a distributed fork stays AGPL);
Everything-Maa skills are **MIT** (vendored docs).

---

## 1. MaaFramework — recognition and action substrate

The prototype already uses the real Python binding: `maa_exec.py` creates an
`AdbController`, `Resource().post_bundle(bundle)`, `Tasker`, runs
`override_pipeline(...)` + `post_task(entry)`, calls `post_recognition`,
`resource.post_image`, `set_log_dir`, `set_save_draw`, `set_save_on_error`,
and reads `NodeDetail.recognition` / `draw_images`. `compiler.py` emits
MaaFW node JSON; `runner.py` executes one node at a time; `agent_maa.py`
uses the same engine for the fallback loop. MaaFramework is therefore
already the prototype's execution layer; the Android fork only swaps the
controller/shell.

### 1.1 Recognition algorithms are element data

| MaaFW recognition | Fields stored in `elements.locator_json` | Our `type` |
|---|---|---|
| `TemplateMatch` | `template`, `roi`, `roi_offset`, `threshold`, `method`, `green_mask`, `order_by`, `index` | `template` |
| `OCR` | `expected`, `roi`, `roi_offset`, `threshold`, `replace`, `order_by`, `index`, `only_rec`, `model`, `color_filter` | `ocr` |
| `FeatureMatch` | `template`, `detector`, `ratio`, `count`, `green_mask`, `order_by`, `index` | `feature` (future) |
| `ColorMatch` | `lower`, `upper`, `method`, `count`, `connected`, `order_by`, `index` | `color` (future) |
| `NeuralNetworkClassify/Detect` | `model`, `expected`, `labels`, `threshold`, `roi`, `index` | `nn` (future) |
| `And` / `Or` | composition of the above (node references) | `all`/`any` (future) |
| `DirectHit` | `roi` or fixed `target` | `point` |
| `Custom` | `custom_recognition`, `custom_recognition_param` | verifier/escape hatch |

The compiler keeps those names unchanged; legacy `{"value": "...",
"contains": true}` OCR locators are normalized to `expected` regex at
compile time for backwards compatibility.

### 1.2 ROI, boxes and coordinates

MaaFW's flow is **recognize inside `roi` (+`roi_offset`) → get `box` →
compute `target` (+`target_offset`) → action**:

- `roi`: `[x, y, w, h]` in controller/raw-screenshot space; v5.6+ negative
  values are relative to the right/bottom edge; a string checks inside a
  previous node's box, and `[Anchor]Name` references an anchor (v5.9).
- `target` for `Click`: `true` = recognition box, `[x,y]` fixed point,
  `[x,y,w,h]` random inside a box, or a node/anchor reference.
- `order_by` (`Horizontal|Vertical|Score|Area|Length|Random|Expected`) +
  `index` choose among multiple hits (needed for lists and search results).
- Coordinates are controller/raw-screenshot pixels, **not** the model's
  720p image. The agent converts model coordinates back to controller
  space; MaaFwApp's `RunPlanPayload.screenWidth/Height` must match the
  control unit's framebuffer/touch space.
- Learned `point` locators must record the display profile (main display vs
  720P/1080P virtual display) they were learned on. Templates are crops and
  survive resolution changes better than points.

### 1.3 Actions, node attributes and runtime override

- Actions: `DoNothing`, `Click`, `LongPress`, `Swipe`, `MultiSwipe`,
  `TouchDown/Move/Up`, `Scroll`, `ClickKey`, `LongPressKey`, `KeyDown/Up`,
  `InputText`, `StartApp`, `StopApp`, `StopTask`, `Command`, `Shell`,
  `Screencap`, `Custom`.
- Node attributes: `timeout`, `pre_delay`, `post_delay`, `rate_limit`,
  `repeat`, `repeat_delay`, `wait_freezes`, `max_hit`, `next`, `on_error`,
  `inverse`, `enabled`, `anchor`, `attach`, `focus`.
- Runtime mutation: `Resource.override_pipeline()` and
  `Tasker.post_task(entry, pipeline_override)` let the DB stay the source
  of truth. No build-time PI package is needed for learned pipelines.
- Resources: `bundle/resource/{pipeline,image}`, `bundle/model/ocr/`
  (`det.onnx`, `rec.onnx`, `keys.txt`). Templates created after bundle load
  are registered with `Resource.post_image()` (the prototype already does
  this).

### 1.4 Controllers and evaluation

- `AdbController` (prototype, PC over USB),
- `AndroidNativeController` via `MaaAndroidNativeControlUnit` (MaaFwApp,
  on-device, no adb),
- `Custom` controller callbacks (escape hatch for new input/screencap
  methods — no MaaFW fork),
- `Record` / `Replay` / `Dbg` controllers (v5) for replay-K evaluation and
  deterministic UI tests before a proposal goes live.

### 1.5 Custom / Agent — verifiers and unusual actions

MaaFW cannot express `pixel`, `file_count` or an AI vision check as a built-in
recognizer. The sanctioned extension is `CustomRecognition`/`CustomAction`
registered through an **AgentServer** (Python or ELF, hosted by MaaFwApp).
On Android these become nodes such as `brain.pixel_verify`,
`brain.file_count_verify`, `brain.vision_verify`, instead of the prototype's
raw ADB checks.

---

## 2. MaaFwApp — the Android shell and its exact contract

We fork it; the brain is added, the shell is reused.

### 2.1 What it already provides

Shizuku/root privileged process + watchdog; native Android controller
(screencap/input on-device); 720P/1080P virtual-display background mode;
foreground overlay/float ball; notification and scheduling; permission
bootstrap; run logs/archives; an Agent host; the PI UI.

### 2.2 The runner contract (verified from source)

- `RemoteService.aidl`: `boolean startRun(String runPlanJson) = 51;`,
  `boolean saveCachedImage(String path) = 75;`. Transaction ids only grow;
  new methods append at the next free ids and are never reordered.
- `RunPlanPayload`: `resourcePaths`, `screenWidth`, `screenHeight`,
  `displayMode`, `agents`, `apkPath`, `nativeLibraryDir`, `piEnv`, and
  `tasks: List<RuntimeTaskPayload>`.
- `RuntimeTaskPayload`: `taskName`, `entry`, `pipelineOverrides:
  List<JsonObject>` (ordered patches, never pre-merged).
- `MaaRunner` calls
  `MaaTaskerPostTask(tasker, task.entry, JsonArray(task.pipelineOverrides).toString())`.

**Consequence:** the compiler's output rides the existing AIDL channel with
**no AIDL change**. A run plan is built with our generated entry node as
`entry` and our compiled nodes as `pipelineOverrides`.

### 2.3 Step-wise execution, pause and resume

MaaFW runs a pipeline graph to completion. The correct design is to execute
the stored graph (or a well-defined uninterrupted subgraph) as one task, and
pause at graph boundaries by persisting `runs.progress_json`. Submitting one
node at a time is useful for pause/control, but it must not silently destroy
`next`/`on_error`/anchor semantics.

**Current state:** the PC prototype and Android M1/M3.2 both still flatten
an imported pipeline or step list into ordered nodes and submit one task per
node; node names are also rewritten (`brain_s…`). Therefore `next`/
`on_error` fields are stored but **not executed as a graph** yet. A
graph-native row + runner is required (see `STATUS.md` §2d and ADR-027).
This is a code milestone, not a verifier tweak.

### 2.4 Screenshots, logs, AI primitives

- `saveCachedImage(path)` receives screenshots as files (binder size limit),
  which the agent and debug bundles read.
- Run logs/dirs from `MaaRunner` become the debug bundle's `maafw.log`.
- Single OCR/click/swipe actions for the AI fallback are expressed as
  one-node run plans first. A small AIDL direct-control group is only added
  if latency demands it (append-only ids).

### 2.5 Brain, UI and resources

- `brain/` lives in the app process: SQLiteOpenHelper over
  `assets/brain/schema.sql`, resolver, compiler, AI client, learner,
  IO/review. The privileged process never knows about the DB.
- UI: existing PI screens stay as **Library**; new **Assistant**, **Queue**
  and **Review** screens render the message stream (see `IO.md`).
- `resourcePaths` includes our built-in bundle (pipeline/images/OCR models)
  alongside any PI path; learned templates are written to a writable
  resource dir and loaded with `post_image`.
- License: AGPL-3.0; the distributed fork stays AGPL.

---

## 3. MaaMCP — the AI authoring / pattern-extraction bench

MaaMCP is an MCP server exposing MaaFW to an external AI assistant. Its
tools include:

| Group | Tools |
|---|---|
| devices | `find_adb_device_list`, `find_window_list`, `connect_adb_device`, `connect_window` |
| vision | `ocr`, `screencap` (region + 720 short-side normalization) |
| control | `click`, `double_click`, `swipe`, `input_text`, `click_key`, `keyboard_shortcut`, `scroll` |
| pipeline mode | `start_pipeline`, `stop_pipeline`, `get_new_messages`, `get_pipeline_status` |
| pipelines | `get_pipeline_protocol`, `save_pipeline`, `load_pipeline`, `run_pipeline`, `benchmark_node`, `clear_pipeline_resources` |
| templates | `save_captured_image` (cropped PNG → bundle `image/`) |
| agents | agent supervisor for Custom recognition/action |

It is the mature loop for **pattern extraction and workflow creation**:

1. AI explores via `screencap` / `ocr` / control tools.
2. Crops a target with `screencap(region=...)`, visually confirms it.
3. `save_captured_image(...)` writes a real `TemplateMatch` template.
4. `save_pipeline(...)` produces standard MaaFW pipeline JSON.
5. `benchmark_node(..., iterations=N)` reports hit rate, score range and
   latency to tune `threshold` / `roi` / `expected`.

Integration paths (in order of preference):

- **Dev-time bench**: use MaaMCP from a PC/agent to explore, extract and
  benchmark; feed the resulting pipeline JSON through an **importer** into
  `proposals` → Review → `elements`/`pipelines` rows.
- **Interoperability**: our compiler can **export** any live pipeline as
  standard MaaFW pipeline JSON for MaaMCP to run, debug, benchmark or
  share with MAA maintainers; import/export is lossless for protocol
  fields.
- **Reference for the fallback loop**: `agent_maa` reimplements MaaMCP's
  observe→decide→act loop with DB evidence, postconditions, resumability
  and cost tracking. Tool vocabulary and lessons (OCR-first, click success
  ≠ visual success, region/normalization) follow MaaMCP.
- **Optional companion**: on PC, MaaMCP can act as the L3 agent while the
  DB/learner stay ours.

MaaMCP is **not** embedded in the APK or put in the hot recognition loop:
it is PC/Python/MCP/ADB-oriented, MaaFwApp already hosts the on-device
runtime, and AGPL code should stay behind a process/tool boundary.

---

## 3b. Everything-Maa — the authoring skill layer

Everything-Maa is a set of **agent skills** that encode how an experienced
MaaFW developer builds a workflow: contract/state-machine design, the field
reference, coordinate hygiene, OCR/ROI/template sweeps, node generation,
testing and diagnosis. It is documentation/process, not a runtime library.

In this repo the relevant skills are vendored under
[`maa-skills/`](maa-skills/) (`maa-workflow-build`, `maa-pipeline-guide`,
`maa-pipeline-generate`, `maa-pipeline-testing`, `maa-pipeline-option`,
`maa-cli-operate`, `maa-wiki`, `maa-diagnose`) at upstream pin `8985260`.
[`AUTHORING.md`](AUTHORING.md) is the operational flow that combines them
with MaaMCP. They are copied (MIT), never edited in place.

---

## 4. Ownership matrix — what we reuse vs what we own

| Capability | Mature solution | Our code / data | Do not |
|---|---|---|---|
| OCR, pattern matching, colors, NN | MaaFramework algorithms + models | `elements.locator_json` native fields; compiler resolves refs | hand-roll NCC/UIAutomator as the product path |
| Coordinates, ROI, offsets | MaaFW `roi`/`roi_offset`/`target`/`target_offset` | locator/step JSON 1:1; display profile per learned point | invent a parallel geometry DSL |
| Workflow execution | MaaFW pipeline graph + runtime override | intended: `pipelines.definition_json` + `pipelines.entry` executed as a graph/subgraph (current code still flattens: see §2.3/ADR-027) | build-time PI packing for learned pipelines |
| On-device shell | MaaFwApp privileged runner, native controller, virtual display | thin binding via `startRun(RunPlanPayload)` | fork/patch MaaFramework or the native control unit |
| AI authoring / pattern extraction | MaaMCP tools + benchmark | importer/exporter + proposals/Review | put MaaMCP in the hot loop or embed it |
| Custom checks | MaaFW Custom + Agent | `pixel`, `file_count`, `vision` custom nodes | raw ADB shell in the product |
| Knowledge DB, resolver, learning, review, IO | — | `schema.sql`, `brain/`, `learn.py`, messages | hide behaviour in code the AI cannot reach |

---

## 4b. Authoring vs runtime (important correction)

Reusable workflows are authored with **MaaMCP + Everything-Maa skills**
(exploration, OCR/ROI/template/color methods, node generation, testing) and
produce standard pipeline JSON. The phone app imports that JSON 1:1 as
native pipeline fragments, reviews it, and replays it. The app's on-device
DeepSeek loop is a bootstrap for first observation only; it must not be the
source of learned point-coordinate pipelines. See [`AUTHORING.md`](AUTHORING.md)
and ADR-022.

**Implemented bridge:** `proto/pipeline_io.py` (import/export commands) and
Android `Importer.kt` + `Compiler.kt` pass-through. **Still to do:** run the
actual MaaMCP authoring + benchmark pass for the first native workflow,
import it, approve, and prove a verified 0-token replay on device.

## 5. Implementation order and current state

| Slice | State |
|---|---|
| Native field plumbing (`pipeline_model.py`; compiler resolves refs/defaults only) | ✅ prototype |
| Pipeline import/export (`pipeline_io.py`; `import-pipeline`/`export-pipeline`) | ✅ prototype; Android importer/pass-through built |
| Postconditions (`file_count`, `element`, `screen_text`, `pixel`) | ✅ prototype; Android wiring next |
| Replay-K evaluation (`evaluate` + `eval_runs`/`eval_items`) | ✅ primitive; hard promotion gate next |
| Android **M0** (fork shell + native runtime) | ✅ built and proven |
| Android **M1** (`brain/` + resolver + compiler + deterministic run) | ✅ built and proven (`open settings`) |
| Android **M3.2** (Assistant, bootstrap AI, strict final verify, native import button) | ✅ built and installed |
| Android **M2** (tile/notification/float-ball, queue, step-wise pause/resume) | ⏳ next surfaces work |
| Actual **MaaMCP + Everything-Maa authoring pass** for the first native workflow | ⏳ immediate next step |
| On-device verifiers as MaaFW `Custom`/Agent nodes | ⏳ M3+/M4; `pixel` verifier + `recognitionDirect` bridge are in |
| In-app AI recognition tool (`OCR`/`TemplateMatch`/`ColorMatch`) | ✅ bridge + `recognize`/`benchmark` actions wired |
| AI-authored native pipeline graph via Kotlin tool layer | ✅ `propose_pipeline` + graph-map compiler/test replay; Everything-Maa skills guide it |
| Automatic element healing | ⏳ v0.1 |

The PC prototype is the behavioural spec for the Kotlin port; the Android
fork reuses the runner contract with no AIDL change so far. Current
evidence and gaps: [`STATUS.md`](STATUS.md).

## 6. Debugging the integration

When a workflow crosses a repo boundary, debug at the lowest layer first:

- DB/row/compiler: inspect `pipelines.definition_json` and compiled node JSON.
- Runner: inspect `maafw.log` and the run postcondition; check override
  shape `{nodeName: node}`.
- MaaFW: inspect draw images, benchmark single nodes, verify ROI/target and
  display profile.
- Authoring: reproduce with MaaMCP (`screencap`, `ocr`,
  `save_captured_image`, `benchmark_node`) before importing.

Full playbook: [`DEBUGGING.md`](DEBUGGING.md).
