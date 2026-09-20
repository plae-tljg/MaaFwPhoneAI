# Design (v0)

> **Read [`GOAL.md`](GOAL.md) first for the goal, then
> [`WORKING_STYLE.md`](WORKING_STYLE.md) for the method it implements (a
> small stable engine vocabulary + a free-structured DB the AI maintains)
> and [`IO.md`](IO.md) for the human interaction contract. This document is
> the concrete v0 architecture. For "what is actually built right now", see
> [`STATUS.md`](STATUS.md).

## 1. The idea

Hardcoded automation behavior becomes rows in a database. A small
deterministic resolver replays pipelines from the DB; the AI drives the phone
only when nothing matches, and each AI success is promoted — after a human
approves — into a pipeline. AI cost per task trends to zero; the AI's real job
is daily maintenance of the rows.

The whole system in one example:

1. You tell the assistant: *"open settings and turn on dark mode"*.
2. No pipeline matches, so the **AI drives the phone** (screenshot/OCR, decide,
   act) and logs the successful trajectory as a `runs` row.
3. That night the AI reads the run and files a **proposal**: pipeline
   `turn_on_dark_mode`, aliases `["dark mode","深色模式","夜间模式"]`, steps =
   the OCR clicks it used.
4. You approve it in the app.
5. Next time: pipeline matches, **deterministic replay, no AI, ~3s, no cost**.
6. A system update moves the label, the pipeline fails twice, the AI files
   an `element_fix` proposal with the new locator; approving it heals every
   pipeline that uses that element.

## 2. Where it runs

Entirely on the phone, no PC. The app is a fork of **MaaFwApp**
(see [`MAA_STACK.md`](MAA_STACK.md) for the exact upstream pin and
integration boundary):

```
app process (normal app)                     privileged process (Shizuku/root)
 ┌─────────────────────────────┐    AIDL    ┌────────────────────────────────────┐
 │ Assistant UI, review screen │◄──────────►│ MaaFramework Resource/Tasker/       │
 │ brain: SQLite, resolver,    │ RemoteSvc  │ Controller + Android native control │
 │ compiler, AI fallback       │            │ unit; virtual display; frame capture│
 └─────────────────────────────┘            └────────────────────────────────────┘
```

- The privileged process runs MaaFramework and the native controller
  (screencap/input without adb), started by Shizuku or root.
- Background tasks run on a virtual display (720P/1080P) while the phone
  stays usable; foreground mode exposes an overlay panel.
- The app process hosts the brain and talks to the runner over the fork's
  existing AIDL surface (`startRun(RunPlanPayload)`, `saveCachedImage`,
  `touchDown/Move/Up`, virtual display calls). See `FORK_DELTA.md`.

MaaFramework is not forked. The touch/input layer stays as shipped; custom
controller callbacks remain the escape hatch if a new input method is ever
needed.

## 3. The resolver (deterministic first)

For every user goal, in order:

1. **Pipeline aliases** — match `pipelines.aliases` for
   `status='live'`: exact > substring > fuzzy, with a confidence gate. Weak
   matches never guess; they fall through.
2. **Patterns with slots** — aliases containing `{app}` / `{setting}` are
   resolved against the `apps` table.
3. **AI fallback** — the run is executed by the agent loop and recorded
   with `path='ai'`.
4. **Ask the user** — if the AI fails, report and stop.

`pipelines.autonomy` gates execution: `auto` runs immediately, `confirm`
asks before acting, `never` refuses (useful for payment/delete pipelines).

## 4. Interaction model

One entry point serves every surface:

```
tile | notification | float ball | in-app  -->  enqueue_goal(text, source, context)
```

A **Quick Settings tile** (swipe the shade anywhere, even over a game), the
**persistent notification** of the run service, the **float ball / overlay
panel**, and the in-app **Assistant screen** all call `enqueue_goal`; no
surface has private logic. Context (current app, screenshot) is attached
when available. Voice and share-sheet input are v0.1.

Run lifecycle (one active run per device; everything else queues):

```
queued -> running -> done | failed
            |  ^
            v  | resume
          paused
            |
      needs_input (AI asks a question, waits)
            |
        cancelled (user stop)
```

When a new goal arrives while a run is active, the prompt sheet always
asks: **Queue** (default after a 10s timeout — never silently drop the
running run), **Do now**, or **Cancel**. *Do now* = pause the current run
after its current step, start the new goal, and automatically resume the
paused run when it finishes. *Stop* is instant; *Pause* finishes the
current step and persists progress so it survives process death.

Because pause must land between graph boundaries, **pipelines execute in
controlled segments**: the
brain submits one step at a time to the runner, persisting
`runs.progress_json` (`{step_index, vars, question}`). MaaFramework cannot
pause inside a pipeline, so this is the only clean pause boundary.

First run: a permission wizard (notifications, overlay, battery whitelist,
Shizuku/root, model key) then an empty-first prompt screen with examples.
An optional demo pipeline is loaded through the real Review flow so the
promotion loop is visible on day one.

Learning feeds back at the surface: after an AI success, an instant
**"Save as pipeline? Review"** card creates a pending proposal (the nightly
sweep still catches everything else).

## 5. Execution model

> The field-level contract (MaaFW recognizer/action/ROI fields stored 1:1,
> the MaaFwApp run-plan channel, MaaMCP's role) is in
> [`INTEGRATION.md`](INTEGRATION.md).

- `pipelines.definition_json` + `pipelines.entry` carry the **native MaaFW
  pipeline graph**: nodes with recognition/action/`next`/`on_error`.
  Legacy/bootstrap rows may still store an ordered array
  of smaller step fragments; nodes may reference shared locators as
  `{"element": "name"}`. The data model is the graph; step-wise execution is
  only the pause strategy.
- The **compiler** resolves element references from `elements.locator_json`,
  injects global defaults, and produces plain MaaFW pipeline JSON.
- The target design is to execute the stored graph and submit **one
  uninterrupted graph segment at a time** to the privileged runner (via the
  run-plan channel), so pause can land at a chosen boundary without breaking
  `next`/`on_error` semantics. The current Android build does not execute
  graph semantics yet: it flattens nodes and submits one task per node (see
  `STATUS.md` §2d and ADR-027).
- Every execution writes a `runs` row with `state` (`queued|running|
  paused|needs_input|done|failed|cancelled`), `source` (which surface), and
  `progress_json` for pause/resume. AI runs additionally store their
  trajectory in `runs.steps_json`; pipeline runs leave it `[]`.
- Every pipeline may carry a **postcondition** (`pipelines.postcondition_json`)
  checked after the last step; the result is stored as `runs.verified` plus
  `runs.verify_json` evidence. A run whose recognizers all matched but whose
  postcondition fails is a failure. Local verifier types (`file_count`,
  `element`, `screen_text`, `pixel`) keep this free. The PC prototype
  implements them; the Android fork still needs to map them to MaaFW
  `Custom`/Agent nodes (see `STATUS.md`).
- `elements.hits/misses` are updated from run events so stale locators are
  visible in data.

## 6. Daily maintenance (the point of the project)

A `learn` worker (WorkManager, nightly) does exactly three things:

- **Promote**: group successful `runs` where `path='ai'` by normalized goal;
  when count >= 2, ask the AI to convert the best trajectory into a
  `proposals(kind='pipeline_new')` row (pipeline + aliases + graph +
  element refs + postcondition). Verified runs are preferred; an AI run that
  was independently verified contributes its verified evidence as the
  pipeline's postcondition. Unverified AI successes stay reviewable but are not
  trusted as ground truth.
- **Heal**: for live pipelines with recent failures, ask the AI to inspect
  the failing run/trajectory and file `pipeline_fix` or `element_fix`.
- **Alias**: if a pipeline succeeded via a goal variant not covered by its
  aliases, file `kind='alias'`.

Reviewing happens in the app: approve / reject (bulk approve allowed).
Approving upserts the affected rows and flips status. Nothing the AI writes
is live before approval. `alias` and `element_fix` may later be
auto-approved via a setting once trusted.

## 7. Tables: core + data-contract primitives

The six core tables are v0's **instances of the data contract** in
`WORKING_STYLE.md`, not a closed set. Per ADR-018/019 three data-shaped
primitives have since been added; every row is AI-proposable through
`proposals` (new knowledge kinds need one publisher + validation + tests,
not a bespoke code path).

| Table | Role | Chatbot analogue |
|---|---|---|
| `apps` | known applications + name aliases | `bots` |
| `pipelines` | pipeline rows: goal, aliases, native MaaFW graph, postcondition, stats, autonomy | `faq` + `flows` |
| `elements` | shared, healable native MaaFW locators + hit/miss stats | `products` |
| `runs` | execution + learning dataset (trajectory, cost, result, state, verified) | `messages` |
| `proposals` | AI propose → human approve queue; kind→publisher registry | `extractions` |
| `settings` | keys: schema_version, ai config, thresholds, hints | `settings` |
| `messages` | IO stream: goals, questions, answers, status/result cards | `conversations` + `messages` |
| `decision_cache` | goal + app + screenshot phash → action (cost) | `answer_cache` |
| `policies` | unknown-goal safety: `match_json` → allow/confirm/deny | `policies` |

Details and columns: `schema.sql`. Deliberately deferred (ADR-010): rules
DSL/flows, formulas, eval suite, embeddings, multi-device, desktop
console; replay-K evaluation and automatic healing are the next v0.1
items.

## 8. Safety (minimum for v0)

- `pipelines.autonomy` is authored by the AI but editable by the human; the
  bridge enforces it (`confirm` shows the planned steps before running).
- `policies` rows gate goals with no pipeline (AI fallback), e.g. payment /
  send / delete; `match_json` uses a small DSL, `action_json` is
  allow/confirm/deny. Policies are AI-proposable and human-reviewed.
- Nothing AI-authored is live before approval.
- All actions are rows in `runs` (with `steps_json` + error), so every
  automation is auditable after the fact.

## 9. Cost control

Ordered by impact:

1. **Promotion** — the big one. A task solved by AI once becomes a
   deterministic pipeline; repeat cost is zero.
2. **Deterministic-first** — the LLM is never called for known goals.
3. **Decision cache** (v0.1) — screenshot perceptual hash + prompt hash →
   cached AI decision, so even unknown screens don't re-call the model on
   every frame.
4. **Local recognizers and verifiers** — MaaFW OCR/template/ONNX plus free
   postcondition checks run on-device; the model is reserved for planning,
   recovery and (rarely, review-time) open-ended visual questions, never the
   hot loop.

## 10. Originality (honest summary)

Not novel as capability: AI-driven phones (Doubao/AutoGLM), LLM Android
agents (Droidrun), AI-generated Maa pipelines (MaaMCP, MaaFW issue #896),
trajectory-to-workflow induction (Agent Workflow Memory), Android
memory-augmented agents (AutoDroid). Novel as a system: a relational
knowledge base as the source of truth for phone automation, a
deterministic-first resolver with AI strictly as a cacheable fallback, an
AI-propose/human-approve promotion loop with shared healable locators, and
all of it running on-device on MaaFramework. MaaMCP (authoring bench) and
Everything-Maa (authoring skills) are reused for the front end of authoring, but they
do not own a promoted, self-maintaining knowledge DB; that is this project's
contribution.

## 11. Lineage

- `../../00ref/chatbot-ai` — the pattern being ported (deterministic-first,
  review queue, promotion loop, cost as a design constraint).
- `MaaFwApp` — the Android shell being forked (Shizuku runner, virtual
  display, overlay, scheduling, logs).
- `MaaMCP` — the PC-side authoring/pattern-extraction/benchmark bench; our
  importer accepts its standard pipeline JSON and our exporter emits live
  pipelines for it. The on-device fallback loop follows its vocabulary and
  lessons but is only a bootstrap.
- `Everything-Maa` — the veteran authoring skills (workflow contract, field
  reference, coordinate hygiene, generate/test/diagnose); vendored under
  `docs/maa-skills/`.
- `MaaFramework` — execution substrate; its pipeline JSON is the compiled
  output format and its native fields are stored as data 1:1.

## 12. Current implementation status

The v0 architecture above is the target; it is not all live yet. The PC
prototype implements resolver/compiler/verification/learning/IO; the Android
fork has M0/M1 plus the M3.2 bootstrap and importer. Pause/queue surfaces,
on-device postcondition verifiers, the MaaMCP authoring pass and automatic
healing are still open. Read [`STATUS.md`](STATUS.md) for the precise
done/not-done list and [`DEBUGGING.md`](DEBUGGING.md) for how to inspect
each layer.
