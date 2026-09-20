# M9A reference — what a mature Maa project looks like

This note records the structural lessons from
[MAA1999/M9A](https://github.com/MAA1999/M9A) (pin `4fa65aa`, 2026-09-18),
which the user pointed to as a good sample. It is a **reference project, not
a dependency**. We should copy its structure and rules, not its game content.

The short conclusion:

> Our product problem is not "MaaFramework cannot do it" and not "the
> verifier logic is the only bug". A mature Maa project describes UI
> navigation as **recognition-gated pipeline graphs** with state
> transitions, while our bootstrap loop is a generic
> screenshot → LLM → raw-coordinate → screenshot loop. The bootstrap may help
> discover a screen, but it is not the runtime skill format.

## 1. M9A structure (what to learn from)

```text
interface.json          PI V2 entry: controllers, resources, tasks, options
tasks/*.json            declarative tasks + options + pipeline_override
resource/<server>/
  pipeline/*.json       MaaFW node graphs (the actual behavior)
  image/**              OCR/template assets
  model/**              OCR/ONNX models
agent/custom/action/    AgentServer custom actions (complex logic)
agent/custom/reco/      AgentServer custom recognitions (advanced checks)
locales/                PI i18n
tests/ + tools/         validation, schema checks, CI
```

- **Behavior is data**: pipeline JSON files describe recognition + action +
  `next` transitions. The game-specific behavior is not hardcoded in a
  general LLM loop.
- **Recognition leads**: nodes use `OCR`, `TemplateMatch`, `ColorMatch`,
  `And`/`Or`, `Custom`, tight `roi`, `order_by`/`index`;
  `target`/`target_offset` derive clicks from the recognized box instead of
  arbitrary screen coordinates.
- **State transitions are explicit**: every action node has a `next` list
  covering the expected post-action screens; `[JumpBack]`/recovery nodes
  handle popups and interruptions. The graph, not a prompt, owns the state
  machine.
- **Freezes instead of blind delays**: `pre_wait_freezes` /
  `post_wait_freezes` and intermediate recognition nodes replace long
  `pre_delay`/`post_delay`.
- **Custom code is an extension point**, not the main path: complex logic
  (for example `MultiRecognition`, dynamic counters, data parsing) lives in
  AgentServer custom actions/recognitions, while normal navigation stays in
  pipeline JSON.
- **720×1280/1280×720 baseline**: coordinates, ROI and templates use the
  standard MaaFW base resolution.
- **Server variants are overlays**: a base resource plus per-server
  `resource/<server>/pipeline` overlays replace only what differs
  (package names, screens, images). That is a data pattern we can reuse for
  Android app variants and channels.
- **Declarative options**: `tasks/*.json` exposes options whose cases apply
  `pipeline_override` patches, so one pipeline supports many user choices
  without code forks.

## 2. Compare with our current bootstrap

| M9A-style production | Our current Android bootstrap (`AgentRunner.kt`) |
|---|---|
| `OCR`/`TemplateMatch` recognition per node | full-screenshot LLM grounding |
| click a recognized box (`target: true`) | model emits `point [x,y]` coordinates |
| explicit `next`/`on_error` state graph | free-form loop; only one action at a time |
| `pre/post_wait_freezes` and state checks | blind `wait` actions; model decides |
| deterministic recognition result | LLM vision opinion |
| reuse: one pipeline, many options/overrides | one model prompt per run |
| failure yields a node/detail to fix | failure yields a vague model assertion |
| custom actions for logic | point trajectories promoted as skills |

The bootstrap is still useful for **first contact**: open an unknown app and
observe what a screen looks like. It is not suitable as the reusable workflow
format, and it should not be the source of promoted skills.

## 3. What this means for maa-phone

### The code problem

The current Android code path treats the bootstrap as a workflow engine:

- `AgentRunner` plans from full screenshots and raw coordinates;
- `Learner` turns those point trajectories into proposals;
- `DeepSeekClient.verify` decides success with a single model call.

That is an architectural mismatch with the data-contract design. It is not
fixed by "a better prompt" or by making the final verifier say `true`.

### The skill/data problem

We have not yet produced a real Maa pipeline for a reusable task. Bilibili
is only a test case; the correct artifact is a generic pipeline graph
(`StartApp` → OCR search entry → `InputText` query → OCR/template results
with `order_by`/`index` → recognize video page → recognize/act like →
recognize/verify state), with recovery nodes. The Bilibili package name,
search text, ROI, templates and expected values are **data**, not Kotlin
code. A different app should follow the same shape without code changes.

### The verification problem

`view → action → verify` is not inherently wrong; the problem is what each
phase contains:

- view = one full screenshot + no recognized state;
- action = raw coordinates, not recognition-targeted;
- verify = another LLM opinion, nondeterministic (run #2 gave 3 true / 2
  false on the same screenshot).

The fix is `recognize → act → recognize` at every transition and a
deterministic final postcondition (pixel/colour, element/text, file count).
The model can remain an additional observer, not the gate (ADR-025).

## 4. What to copy conceptually (never copy game data)

1. Pipeline graph shape: explicit nodes, `next` coverage, recovery via
   `[JumpBack]`/`on_error`, `pre/post_wait_freezes`.
2. Recognition-first actions: OCR/template/color/ROI, `target` from the
   recognized box, `order_by`/`index` for lists.
3. Custom agent extension only when JSON cannot express the logic; register
   through AgentServer as M9A does.
4. Resource overlays for app variants/package differences.
5. Declarative options/overrides, so one workflow serves many goals.
6. Validation and tests around pipelines, not just around the runner.

## 5. What not to do

- Do not copy M9A's game-specific nodes, images, or ROI into maa-phone.
- Do not hardcode a Bilibili branch into Kotlin/Python.
- Do not promote point-coordinate bootstrap trajectories as production
  pipelines.
- Do not make the LLM verifier the only success check.
- Do not add blind delays or blind retries to hide unstable recognition;
  fix or recognize the intermediate state.

## 6. Practical next use

Use this reference while authoring the first native workflow:

1. MaaMCP observes and captures templates/ROI on a real device.
2. Author the node graph in M9A's style (recognition per node, explicit
   `next`, freezes not delays).
3. Import the pipeline JSON through the app's Review path (the importer and
   compiler pass-through already exist).
4. Set a deterministic postcondition for the toggle/state (ADR-025).
5. Replay and benchmark; failures become `element_fix`/`pipeline_fix`
   proposals, not prompt changes.

The concrete first target is the Bilibili test — but the output must be a
reusable, data-defined pipeline, not a hardcoded Bilibili code path.
