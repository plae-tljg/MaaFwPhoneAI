# Decisions (ADR-lite)

Short records so future maintainers (and their AI) know why before changing
things.

> **Convention note (later than the early ADRs):** under
> [`CONVENTIONS.md`](CONVENTIONS.md), a **pipeline/workflow** is the runtime
> MaaFW state-machine row; a **skill** is an authoring instruction from
> Everything-Maa. Early ADRs sometimes say "skill" for the runtime row
> (historical wording). The fresh v1 schema uses `pipelines`; no migration is
> needed because the app is reinstalled on a clean DB. See
> [`SCHEMA.md`](SCHEMA.md).

## ADR-001 — On-device only, no PC
**Context:** The user wants a real "AI phone", not a PC-tethered rig.
MaaFwApp/MAA-Meow prove MaaFW runs natively on Android via Shizuku/root.
**Decision:** The assistant runs entirely on the phone. PC is only a build
machine.
**Consequence:** Android 9+; Shizuku authorization after reboot (or root);
arm64-v8a build target.

## ADR-002 — Do not fork MaaFramework
**Context:** The touch/input layer looked like it might need patching.
**Decision:** Use MaaFW extension points only: the Android native control
unit, `override_pipeline`, run-plan execution, custom
recognition/action/controller callbacks if ever needed.
**Consequence:** MaaFW upgrades stay cheap. New input methods, if any, are
custom controller callbacks in the app, never core patches.

## ADR-003 — Fork MaaFwApp as the shell
**Context:** Shizuku spawning, watchdog, virtual display, frame capture,
overlay, scheduling, notifications, permissions, logs are months of work
already solved there; MAA-Meow is Arknights-core-specific, not a generic
MaaFW app.
**Decision:** Fork MaaFwApp and add a brain + assistant UI.
**Consequence:** The fork inherits AGPL-3.0 (accepted). Keep its AIDL
transaction-id discipline: ids only grow, never reorder.

## ADR-004 — Brain in Kotlin, in the app process
**Context:** Execution already lives in the privileged process behind AIDL;
a Python brain (Chaquopy or Agent) would add a runtime and a second IPC
path for a very small v0.
**Decision:** The brain (SQLite, resolver, compiler, AI client, learning)
is Kotlin in the app process. The fork's Agent host stays as the escape
hatch if the AI loop ever outgrows Kotlin.
**Consequence:** Logic is ported from the Python chatbot conceptually, not
literally. Kept small on purpose.

## ADR-005 — Database is the source of truth; pipeline JSON is compiled
**Context:** MaaFramework consumes pipeline JSON; files/PI packages are
awkward to maintain and to review.
**Decision:** Rows are authoritative. The compiler emits pipeline JSON at
run time and hands it to the runner.
**Consequence:** No build-time PI packing for learned skills. MaaFW
`interface.json`/PI remains only for the curated library surface.

## ADR-006 — Small core tables, no over-normalization
**Context:** chatbot-ai grew from a simple core into many tables; that
complexity misleads early design.
**Decision:** v0 starts with `apps`, `skills`, `elements`, `runs`,
`proposals`, `settings`. The pipeline graph stays a single `steps_json`
fragment per skill (like the chatbot's `flows.steps_json`); shared locators
are the one thing pulled out into rows. ADR-018 later added data-contract
primitives (`messages`, `decision_cache`, `policies`, `eval_runs`/
`eval_items`) when the method required them — the rule is "generic
primitive + JSON payload", not a fixed count.
**Consequence:** AI-friendly authoring (one artifact per skill), while
element fixes remain global. No nodes/edges tables in v0.

## ADR-007 — Deterministic-first with a confidence gate
**Context:** LLM latency and cost; wrong guesses are worse than asking.
**Decision:** Match skills with exact > substring > fuzzy and a threshold;
weak matches fall through to the AI. The model is never in the hot
recognition loop.
**Consequence:** New behavior should first try to be a row; the AI is a
safety net, not the primary path.

## ADR-008 — AI proposes, human approves
**Context:** Autonomous phone control is risky; silent self-modification
would be worse.
**Decision:** All AI-authored changes land in `proposals` as `pending`.
Approval upserts rows and flips status. Auto-approval is opt-in per kind
(later, for `alias`/`element_fix`).
**Consequence:** The Review screen is the gate. The learning loop can run
every night without consequence until reviewed.

## ADR-009 — Autonomy is data
**Context:** Some skills must never run unattended (payment, send, delete).
**Decision:** `skills.autonomy` ∈ `auto|confirm|never`, authored by the AI,
editable by the human, enforced by the bridge.
**Consequence:** A lightweight safety spine exists from day one without a
policy engine.

## ADR-010 — Defer complexity explicitly
**Context:** The temptation to port the whole grown-up chatbot (rules,
policies, flows, formulas, evals, decision cache) is high.
**Decision:** v0 ships none of them. Decision cache is the first v0.1
candidate because it directly cuts AI cost; the rest wait for a real task.
**Consequence:** Clear, small system. Every deferred item has a natural
place to land later (`settings`, new tables, or a resolver layer).

## ADR-011 — One `enqueue_goal` entry point for all surfaces
**Context:** Tile, notification, float ball, and in-app input are four
doors into the same system; per-surface logic would fork behavior.
**Decision:** Every surface calls `enqueue_goal(text, source, context)`;
only `runs.source` records the door used.
**Consequence:** New surfaces (share, voice, widget) cost a UI only.
Context (current app/screenshot) is best-effort.

## ADR-012 — Always ask when a goal arrives during a run
**Context:** Background mode does not fight the user, so queueing is safe;
foreground mode does, so silently continuing can be wrong. Stop-and-drop
can lose work.
**Decision:** The prompt sheet always asks: Queue / Do now / Cancel.
Queue is the default after a 10s timeout. **Do now** pauses the current
run after its step, starts the new goal, then auto-resumes the paused run.
**Consequence:** No silent scheduling decisions. One active run per
device; queue order is paused-first, then queued by id.

## ADR-013 — Pause/resume via step-wise execution
**Context:** MaaFramework cannot pause inside a pipeline; only stop.
**Decision:** A skill's `steps_json` is an ordered list of steps; the brain
submits one at a time and persists `runs.progress_json`
(`{step_index, vars, question}`) between them. Pause lands between steps
and survives process death; resume continues from `step_index`.
**Consequence:** Cross-step timeout semantics are the brain's job, not
MaaFW's. Trajectories learned by the AI are split at natural milestones to
form steps.

## ADR-014 — Instant learning card alongside the nightly sweep
**Context:** Waiting a day to capture a fresh AI success wastes the best
evidence (and the user's memory of intent).
**Decision:** A successful AI run immediately offers "Save as skill?
Review", which files a pending `proposals` row; the nightly worker still
sweeps remaining AI runs (count >= 2 per goal) and failing skills.
**Consequence:** Two proposal sources, one Review queue, one approval
path. Nothing auto-live.

## ADR-015 — Postconditions make success observable
**Context:** MaaFW matching alone said "success" while the camera replay took
no photo and an AI Bilibili run claimed a like that never happened. Silent
success would poison the learning set and erode trust.
**Decision:** skills carry `postcondition_json`; the runner checks it after
the steps and writes `runs.verified` + `runs.verify_json` evidence. Failing
the postcondition fails the run. AI runs can be held to the same bar with
`--verify`. A verified AI run's evidence is reused as its proposal's
postcondition.
**Consequence:** "all nodes matched" and "the goal happened" are now
different states; replay cannot silently succeed. Local verifier types
(`file_count`, `element`, `screen_text`, `pixel`) keep verification free;
`{}` skills run but report `verified=0`.

## ADR-016 — Native tool calling for the AI loop, JSON mode as fallback
**Context:** DeepSeek thinking models sometimes mapped `response_format:
json_object` onto internal DSML tool syntax, leaving unparseable content
(run #4). Coordinates and actions are structured data, not prose.
**Decision:** the agent calls a single `phone_action` function (strict
`action` enum) and falls back to JSON-content parsing plus one repair turn
only when tools are unavailable. Parsed actions are validated against the
enum before execution.
**Consequence:** one obtuse failure mode disappears; providers without tool
support still work; malformed actions trigger a retry instead of a wasted
step.

## ADR-017 — Learning cleans trajectories and prefers verified evidence
**Context:** early Phase B trajectories learned OCR targets like `NLG` and
`1` from point taps that merely landed inside large OCR boxes, kept
decorative glyphs (`●`), and let a plausible but wrong AI postcondition
replace the pixel check that had actually passed.
**Decision:** a point tap binds to OCR only when it hits the centre of a
reasonably sized text box (otherwise it stays a point + screenshot and
learns a template); lone digits/glyphs are dropped; near-identical taps
within 20px collapse; a verified run's own evidence wins over an
AI-suggested postcondition.
**Consequence:** proposals are smaller and replayable more often; dynamic
steps still need human review, and true automatic healing remains v0.1.

## ADR-018 — The data contract: stable vocabulary, free rows
**Context:** The temptation in an AI-maintained system is either to hardcode
each new behaviour (nothing is data) or to make an unbounded/EAV schema the
AI may freely mutate (nothing is trustworthy). chatbot-ai showed the middle
path: a small engine vocabulary with flexible JSON payloads in rows.
**Decision:** Code defines only a fixed vocabulary (run states, step
actions, locator kinds, verifier kinds, resolver modes, proposal kinds,
message kinds, sources, autonomy). Everything the product *knows or does*
is a row with a JSON payload. New behaviour must first try to be a row of an
existing vocabulary; a new primitive ships as one table/column + one
interpreter branch + one proposal kind/publisher + validation + tests +
forward-only migration + a vocabulary line in `docs/WORKING_STYLE.md`.
**Consequence:** The AI can maintain the knowledge base without code
changes; the engine stays small and reviewable. This supersedes any reading
of ADR-006 as "six fixed feature tables": the six are v0's primitives, not
a closed set.

## ADR-019 — IO is a message stream, not hidden run state
**Context:** The original interaction idea ("swipe the message panel over a
game, stop/pause, type what you want, let the AI do or learn it") cannot be
served by hidden CLI/job state. chatbot-ai already models conversations and
messages as rows, with Review as the operator IO.
**Decision:** `enqueue_goal(text, source, context)` is the only entry point;
goals, questions, answers, statuses, evidence cards and proposals are
`messages` rows in one thread, linked to runs. `needs_input` persists the
pending question there and survives process death. Queue / Do now / Cancel,
pause/resume and Review all operate on that stream.
**Consequence:** The Android Assistant screen and notification cards are
renderings of the same data; walking in mid-run is a message, not an
interrupt of hidden state. The prototype needs the message rows/publishers
before the Android UI, so the IO contract is testable first.

## ADR-020 — Store MaaFW protocol fields as data; the compiler only resolves
**Context:** The mature recognition/action behaviour we need (OCR
`expected`/`replace`/`model`, TemplateMatch `threshold`/`method`/`green_mask`,
FeatureMatch/ColorMatch/NN, ROI/offsets/order_by/index, target/target_offset,
`next`/`on_error`/anchors) already exists in MaaFramework. Re-inventing a
parallel mini-schema would lose that maturity and break interoperability
with MaaMCP/pipeline tooling.
**Decision:** `elements.locator_json` and `skills.steps_json` carry MaaFW
protocol field names and values 1:1. The compiler resolves `{"element": …}`
references, normalizes legacy shorthand, fills defaults and validates; it
does not translate geometry or reimplement matching. Any learned skill can be
exported as a standard MaaFW pipeline JSON and any such JSON can be imported
as a proposal.
**Consequence:** MaaMCP can benchmark/debug our nodes; Maa maintainers can
read our pipelines; new MaaFW algorithms become usable by widening the data
(not the engine). Fields we do not yet support validate as pass-through.

## ADR-021 — MaaMCP is a dev-time authoring bench, not a runtime dependency
**Context:** MaaMCP is a mature MCP server for AI-driven exploration,
template capture (`save_captured_image`), pipeline save/run and
`benchmark_node`. It is PC/Python/MCP/ADB-oriented and AGPL-3.0; MaaFwApp
already hosts the on-device MaaFW runtime.
**Decision:** Use MaaMCP on the PC for pattern extraction, ROI/threshold
tuning and pipeline authoring; ingest its pipeline JSON through the
proposal/Review path; export live skills for it to benchmark/share. The
on-device fallback loop is ours (`agent_maa`/Kotlin), following MaaMCP's
tool vocabulary and lessons. MaaMCP is not embedded in the APK and is not in
the recognition hot loop.
**Consequence:** We reuse the mature extraction/benchmark loop without
adding a Python/MCP runtime to the phone; the DB remains the source of
truth, and AGPL stays behind a tool/process boundary.

## ADR-022 — MaaMCP + Everything-Maa author workflows; the on-device agent is a bootstrap

**Context:** The first on-device Bilibili attempt became a coordinate-guessing
loop (repeated taps at y≈27); the next attempt actually liked the video
(pixel-proven) but was false-negatived by the LLM final verifier. The user's
correction was explicit: building a reusable
workflow should think like a veteran MaaFW developer — OCR, TemplateMatch,
ColorMatch, FeatureMatch, ROI, `order_by`/`index`, coordinate hygiene, node
benchmarking — not a hand-rolled coordinate agent.

**Decision:** Reusable workflows are authored on the PC with **MaaMCP**
(observe/extract/benchmark) and the **Everything-Maa skills** (contract,
field reference, generation, testing, diagnosis). They produce standard
MaaFramework pipeline JSON; the phone app imports that 1:1 through Review
and replays it. The Android `AgentRunner` / `proto/agent_maa` loop is a
**bootstrap only** for first observation; its point trajectories are not the
intended final learning artifact.

**Consequence:** The project gets the maturity of the MAA tooling for free,
the importer/compiler become the critical bridge, and the bootstrap cannot
silently define the product's skill vocabulary. Known follow-up: make the
Android repeat-tap guard cover model `tap` points too.

## ADR-023 — Reference MAA repositories are pinned checkouts, not runtime dependencies

**Context:** MaaFramework, MaaFwApp, MaaMCP and Everything-Maa move quickly;
docs that say "we use MaaMCP" but not which revision are impossible to
reproduce. The projects also have different licenses (LGPL vs AGPL vs MIT).

**Decision:** Record exact pins for each reference checkout
(`docs/MAA_STACK.md`) and keep the boundaries explicit:
- MaaFramework is linked as a library and not patched;
- MaaFwApp is forked (AGPL, source-available);
- MaaMCP is an external PC/dev-time tool, never embedded in the APK;
- Everything-Maa skills are vendored as MIT reference docs, not code
  dependencies;
- dependencies are refreshed deliberately and the pins updated in one place.

**Consequence:** Reproducible builds/reviews, clean license boundaries, and a
single place to check before claiming an integration exists.

## ADR-024 — Debugging is evidence-first and leaves a permanent trail

**Context:** The original session lost time to a clock jump, misread log
files, a false-success replay and an opaque native-library corruption. The
raw evidence existed but was scattered across run logs, DB rows,
screenshots, draw images and device state.

**Decision:** Every run must leave enough evidence to explain its outcome:
`runs` row (`state`, `success`, `verified`, `error`, `steps_json`,
`verify_json`), `messages` tying it to the user stream, and — where
available — debug bundle artifacts (`events.jsonl`, `manifest.json`,
screenshots/draws, `maafw.log`). Debugging must follow
[`DEBUGGING.md`](DEBUGGING.md): layer isolation, raw evidence over claims,
smallest reproducible artifact, then fix + regression/notes.

**Consequence:** A failure becomes a learning input instead of a mystery.
Verification (`verified`) is part of the debug contract, not an optional
report: a run without a postcondition is explicitly *unverified*, not
*proven*.

## ADR-025 — First-time AI uses the LLM verifier; consolidated pipelines use deterministic checks

**Context:** The first time the system does a task, no pipeline exists yet,
so there is nothing deterministic to replay. In that phase an LLM verifier is
the only available checker. It is imperfect: on the same final screenshot the
exact prompt once returned 3 true / 2 false, and in another run the toggle
was already active before the AI tapped it. But the alternative — requiring a
deterministic check before the first run exists — is circular.

**Decision:**
- **Bootstrap/first-time phase:** the LLM verifier is the checker. Make it as
  robust as possible: wait, capture multiple fresh final screenshots, retry,
  and keep all evidence. Prefer a focused crop for icon/toggle state rather
  than the full screenshot. The result is still a *candidate* pipeline
  version requiring human Review.
- **Consolidated/live pipeline phase:** a deterministic postcondition
  (`pixel`, `element`, `screen_text`, `file_count`, or MaaFW `Custom` check)
  is required. Replay must not depend on a model verdict, and a model `true`
  must never override a failed deterministic check.
- The maintaining AI must be able to read `runs`, `pipeline_versions`,
  `pipeline_version_runs`, `messages` and `proposals` to debug why a first AI
  run or replay failed, then propose a fix.

**Consequence:** Verification is phase-dependent: first contact is
model-judged but evidence-backed; after consolidation it is deterministic.
The model verdict is promotion advice for a candidate pipeline version, not
the long-term replay gate.

## ADR-026 — Runtime behavior is a recognition-gated pipeline graph (M9A pattern)

**Context:** The user pointed to **MAA1999/M9A** as a good sample. Its
`resource/**/pipeline/*.json` graphs use recognition (OCR / TemplateMatch /
ColorMatch / Custom), tight ROI, `order_by`/`index`, explicit `next` and
recovery transitions, and `pre/post_wait_freezes` instead of blind delays.
Complex logic lives in AgentServer custom actions/recognitions, while
task/game variations are data (PI tasks/options + resource overlays). Our
Android bootstrap instead drives a full-screenshot → LLM → raw-coordinate
loop, with no recognized state graph and a model-only final verifier.

**Decision:** The runtime format for a reusable workflow is a
**recognition-gated pipeline graph**. Behavior lives in pipeline data, not
in a general coordinate-agent prompt and not in app-specific Kotlin/Python
branches. The on-device LLM loop remains a first-contact bootstrap and an
input to authoring; it must not directly be the production skill source.
Custom code is allowed only as an AgentServer extension for logic that
pipeline JSON cannot express, following the M9A pattern. M9A is a structural
reference, never copied game data.

**Consequence:** The next concrete work is authoring/benchmarking the first
native pipeline (Bilibili test case) with MaaMCP + Everything-Maa, importing
it through the existing 1:1 pass-through, and replaying it with a
deterministic postcondition. New task behavior should first try to be a
node graph/row, not a new prompt branch.

## ADR-027 — A stored workflow is a pipeline graph, not a flattened node list

**Context:** The original design was explicit: each workflow is a state
machine in the MaaFramework pipeline format stored in the flexible DB rows,
so the AI can propose/edit it and MaaFramework can execute it. The current
importer/compiler/runner path, however, flattens an imported pipeline into an
ordered node list, renames nodes (`brain_s…`), and submits one task per node.
Node fields are stored, but `next`, `on_error`, anchors and the graph's state
transitions are not executed as a graph. That means our code currently does
not honour the design at the most important layer.

**Decision:** `skills.steps_json` (with `skills.entry`) is the authoritative
**pipeline graph** for a workflow. The importer must preserve the graph
structure and node names/references; the compiler/runtime must execute the
graph (or a well-defined uninterrupted subgraph) through MaaFramework.
Pause/queue may split execution at chosen boundaries, but must not silently
change graph semantics. Legacy flat step rows remain supported as a
compatibility/bootstrap form and must be explicitly marked as such.

**Consequence:** The next runtime code milestone is a graph-native path:
graph-shaped rows, graph validation, whole-graph/subgraph execution, and
node-level runs/evidence. This is a code problem and a data-shape problem —
not something fixed by a verifier tweak or a Bilibili-specific branch.

## ADR-028 — Vocabulary: pipelines are runtime data; skills are authoring instructions

**Context:** The words "skill" and "pipeline" were being used for different
things: the runtime MaaFW state machine row, and the Everything-Maa
instruction documents that teach an agent how to author/debug such a state
machine. That is a naming collision, not a design disagreement. The user's
convention is the standard AI-agent convention: skills are instruction
modules for the agent/MaaMCP authoring flow; workflows/pipelines are the
runtime state machines.

**Decision:** Adopt the vocabulary in [`CONVENTIONS.md`](CONVENTIONS.md):
- **pipeline/workflow** = native MaaFW pipeline graph/state machine stored in
  the DB (table `pipelines`);
- **skill** = Everything-Maa instruction document loaded lazily by family;
- **tool** = MaaMCP procedure (screencap, OCR, ROI/crop, save pipeline,
  benchmark node);
- **element/locator** = reusable recognition params referenced by pipeline
  nodes; **asset/template** = image files in the MaaFW resource bundle;
- **bootstrap** = temporary LLM loop for first contact only.

**Consequence:** The fresh v1 code/schema uses the pipeline vocabulary; docs and agent
instructions must not use "skill" for runtime workflows. Everything-Maa
skills stay docs and are loaded by `docs/maa-skills/index.json` family
selection, not all at once.
