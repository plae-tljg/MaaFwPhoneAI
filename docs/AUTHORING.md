# Authoring workflows the Maa way (MaaMCP + Everything-Maa)

This is the corrected authoring architecture. The app's on-device vision
loop is a **bootstrap**, not the workflow builder. Reusable workflows are
authored with mature Maa tooling, stored as native MaaFramework pipeline
fragments, reviewed, and replayed.

> **Current state:** the import bridge and native pass-through compiler are
> built (`proto/pipeline_io.py`, Android `Importer.kt` + `Compiler.kt`). The
> actual MaaMCP + Everything-Maa authoring pass for a real workflow (start
> with Bilibili) has **not** been run yet. That is the immediate next step
> in [`STATUS.md`](STATUS.md). The on-device bootstrap failed twice on
> Bilibili: once by looping on direct coordinate taps, and once by actually
> liking the video but being false-negatived by the LLM verifier. The action
> is not the main problem; deterministic verification and the Maa way are the
> critical path.

## 1. Why

A veteran MaaFW developer does not build a workflow from raw screen
coordinates. They:

1. observe the real UI first (`screencap`, `ocr`, boxes);
2. derive clicks from recognition, not constants;
3. choose the recognition method the screen actually offers:
   `OCR`, `TemplateMatch`, `FeatureMatch`, `ColorMatch`,
   `NeuralNetworkClassify/Detect`, `And`/`Or`, `DirectHit` only for pure
   flow/action nodes;
4. constrain recognition with `roi`/`roi_offset`; use `order_by`/`index`
   for lists; `target` defaults to the recognized box center;
5. add a post-action recognition/state check instead of assuming a tap
   worked;
6. verify with a testing ladder before accepting the workflow.

### The two skill layers (do not confuse them)

- **Everything-Maa is the authoring instruction layer.** Its skills tell the
  agent *how to decide*: when OCR is better than TemplateMatch, when to use
  ColorMatch, when a tight `roi`/`roi_offset` is required,
  `order_by`/`index` for lists, how to crop a stable reference region, and
  how to verify with a testing ladder.
- **MaaMCP is the tool layer that executes those instructions**: `screencap`
  region crops, `ocr` boxes, `save_captured_image` writes the cropped PNG,
  `save_pipeline` writes the graph JSON, and `benchmark_node` measures it.
- **maa-phone is the runtime/knowledge layer.** The resulting pipeline JSON
  becomes a Review proposal; approval stores the graph row; referenced
  images are copied into the versioned MaaFW `resource/image/` bundle
  (`files/pi/brain/res_N/image/`) and replayed with MaaFramework.
- **The on-device LLM loop is only a bootstrap** for first contact; it is
  not the substitution for these two layers.

A concrete structural sample from the MAA ecosystem is
[`MAA1999/M9A`](https://github.com/MAA1999/M9A), checked out at
`../../00ref/M9A`; see [`M9A_REFERENCE.md`](M9A_REFERENCE.md). It shows how a
mature project writes behavior as recognition-gated pipeline JSON with
explicit `next` recovery, resource overlays, and AgentServer custom code only
where JSON is insufficient. Copy the structure, never the game content.

The vendored skills in [`maa-skills/`](maa-skills/) are the detailed guides:
`maa-workflow-build` (contract/exploration/acceptance), `maa-pipeline-guide`
(field reference + coordinate hygiene), `maa-pipeline-generate`
(OCR/ROI/template sweeps), `maa-pipeline-testing`, `maa-pipeline-option`,
`maa-cli-operate`, `maa-wiki`, `maa-diagnose`.

## 2. Roles

| Layer | Tool / project | Responsibility |
|---|---|---|
| Authoring / exploration | **MaaMCP** (`screencap`, `ocr`, `click`, `swipe`, `input_text`, `click_key`, `save_pipeline`, `run_pipeline`, `benchmark_node`, `save_captured_image`) | observe states, capture templates, benchmark nodes, write pipeline JSON |
| Authoring skills | **Everything-Maa** | workflow contract, state machine design, pipeline generation, testing, diagnosis |
| Runtime / knowledge | **maa-phone** (this repo) | DB rows, resolver, compiler, native pipeline execution via MaaFwApp, Review, runs/evidence |
| Execution | **MaaFwApp fork** | privileged MaaFW runner + Android native controller |
| Bootstrap only | on-device DeepSeek loop (`AgentRunner`) | first contact with an unknown app; its output should become native nodes, not point steps |

MaaMCP and Everything-Maa run on the PC next to the device. The phone app
does not embed MaaMCP and does not reimplement its tool surface.

## 3. The flow

```
MaaMCP explore/observe ──► task contract (Everything-Maa)
        │
        ▼
design recognition→action→recognition state machine
        │  OCR / TemplateMatch / ColorMatch / ROI / order_by
        ▼
pipeline.json + image/ templates (720x1280 base)
        │  adb push to  .../files/brain/imports/
        ▼
Assistant → "Import MaaMCP pipeline.json"  ──► proposals (pending)
        │
        ▼
Review → approve ──► pipelines/elements rows (native pipeline graphs)
        │
        ▼
replay on MaaFwApp runner (0 AI tokens) + runs/verified evidence
        │
        ▼
failures → AI/heal proposals → Review
```

### Importer behaviour

`Importer.importPipeline` stores each node as `{"pipeline": <node>}` in
`pipelines.definition_json`, and template files referenced by the pipeline are
copied from `files/brain/imports/` into a versioned bundle
(`files/pi/brain/res_N/image/`); `brain_bundle_version` is bumped so MaaFW
reloads the resource before replay.

**Important current limitation:** storing the fields is not the same as
executing the graph. The Android import path currently flattens the pipeline
to an ordered node list (skipping pure wrapper nodes), `Compiler.kt` renames
nodes to `brain_s…`, and `BrainRunner.kt` submits one task per node.
Therefore `next` / `on_error` / anchor references are stored in the node
JSON but are not honoured end-to-end yet. Executing the stored pipeline as a
real MaaFW graph is a required code milestone (see `STATUS.md` §2d and
ADR-027), not something the current APK already does.

## 4. Hard rules (from maa-pipeline-guide)

- `target` defaults to `true` (click the recognized box); do not write
  `DirectHit` + hardcoded coordinate arrays to click a UI element.
- `roi` limits recognition; it is not a click coordinate.
- Prefer OCR for stable text; TemplateMatch for stable icons (crop the
  stable part, avoid counters/red dots/animations); ColorMatch for stable
  state colors; `And`/`Or` to combine.
- Scale screenshots/templates/ROI to the 720x1280 base.
- Recognize → act → recognize; always verify the post-action state.
- Lists: use `order_by`/`index` and a narrow ROI instead of offsets into an
  unrecognized neighbor.
- Explicit waits: prefer an intermediate recognition node; for startup /
  animation / loading, use short `pre_delay`/`post_delay`/`*_wait_freezes`
  and a following state check. Do not hide problems with long blind delays.

## 5. On-device bootstrap loop (temporary)

`AgentRunner` currently uses screenshots + DeepSeek vision +
`locate`/`tap`/`text`/`key`/`swipe`. It is useful to get the first
observation, but its point/coordinate output is **not** the intended
learning artifact:

- do not promote point-tap trajectories as final pipelines;
- convert observed transitions into native pipeline nodes (OCR/TemplateMatch/
  ColorMatch with ROI) using the authoring flow above;
- repeated-tap guard and strict final-state verification exist so a failed
  bootstrap run cannot file a false proposal.

## 6. The first real authoring pass (Bilibili target)

This is the concrete version of the flow above, ordered cheapest-first:

1. **Observe.** MaaMCP `screencap` + `ocr` on the Bilibili start state,
   search screen, search results, video page and bottom action bar. Record
   the raw screenshots and boxes; do not guess coordinates.
2. **Contract.** Write the state machine with `maa-workflow-build`: start →
   search → results → video → like; define the acceptance per transition.
3. **Recognize.** Use `maa-pipeline-guide` to choose:
   - search entry and search text: `OCR` with tight `roi` and `expected`;
   - first video card: `TemplateMatch` on a stable crop (avoid counters,
     red dots, animation);
   - like icon: `TemplateMatch` or `ColorMatch` on a tight ROI, with
     `order_by`/`index` if the page has several action icons.
4. **Generate.** `maa-pipeline-generate` to sweep ROI/threshold/expected
   and produce `pipeline.json`; `save_captured_image` for every template.
5. **Test.** `maa-pipeline-testing` + `benchmark_node` for hit rate, score
   range and latency; add recognize→act→recognize and a final state check.
6. **Import.** Push `pipeline.json` + templates to `/sdcard/Download/`,
   press **Import pipeline** in the Assistant and pick the file; inspect the
   generated `pipeline_new` proposal + candidate `pipeline_versions` row and
   the copied template names. `Importer` accepts a plain node map or the thin
   `{pipeline, entry, postcondition}` wrapper documented in
   `proto/pipelines/README.md`.
7. **Review.** A human checks aliases, steps, app id and postcondition.
8. **Replay.** Approve, run the goal deterministically, require a real
   postcondition (like state pink/red), and keep the run/debug evidence.
9. **Harden.** Re-run from a different start state (already liked / different
   page) and turn failures into healing proposals.

> **First executed pass (2026-09-20):** `proto/pipelines/clock_app_maamcp.json`
> is the clock-app variant of this flow (OCR/ROI verify, explicit `next` and
> `on_error`, deterministic OCR postcondition). It was imported, created
> candidate version #13, Test-replayed as run #52 with `verified=1`, and the
> same wrapper format is what the future Bilibili pass should use. The
> TemplateMatch/Bilibili half is still open.

## 7. Testing and debugging the authoring flow

- Benchmark each recognition node before wiring the graph.
- Keep the import/export lossless check: a compiled live pipeline re-exported
  should still carry `roi`, `threshold`, `method`, `order_by`, `index`,
  `target`, `next`, `on_error`.
- Use the testing ladder in [`DEBUGGING.md`](DEBUGGING.md) §7:
  unit → compiler → single node → transition → replay-K → on-device smoke.
- If the on-device bootstrap is used at all, add `/data/local/tmp`-style
  observation first; do not trust its `done` without independent
  verification (see `DEBUGGING.md` §5).
