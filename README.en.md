# maa-phone

An on-device, AI-maintained automation system for Android phones, built on
the MAA ecosystem.

> **The method is the product:** put everything the assistant knows and does
> into a small SQLite knowledge base the AI can maintain; run deterministic
> verified workflows first; use the vision model only for the unknown; keep a
> human on the publish gate; let every run become evidence. Repeat cost
> trends to zero.

MaaFramework is the recognition/action engine. A fork of MaaFwApp is the
Android privileged shell. MaaMCP + Everything-Maa are the PC-side workflow
authoring bench. **maa-phone owns the knowledge DB, resolver, compiler,
verification, learning/promotion loop and IO** — and stores MaaFramework's
native protocol fields 1:1 as data.

## Start here

| Document | Why |
|---|---|
| [`docs/GOAL.md`](docs/GOAL.md) | **read first** — goal, central idea, scope, success criteria |
| [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) | vocabulary contract: pipelines are runtime state machines; skills are authoring instructions; MaaMCP is the tool bench |
| [`docs/WORKING_STYLE.md`](docs/WORKING_STYLE.md) | the AI-maintained free-structured DB method and extension rule |
| [`docs/IO.md`](docs/IO.md) | the interaction mode: one message stream, queue, questions, Review |
| [`docs/MAA_STACK.md`](docs/MAA_STACK.md) | exact roles/pins of MaaFramework, MaaFwApp, MaaMCP, Everything-Maa |
| [`docs/INTEGRATION.md`](docs/INTEGRATION.md) | field-level integration contract and ownership matrix |
| [`docs/AUTHORING.md`](docs/AUTHORING.md) | the veteran Maa authoring flow: MaaMCP + Everything-Maa → native pipeline → import → Review → replay |
| [`docs/STATUS.md`](docs/STATUS.md) | what is built, proven and still broken, with evidence and hashes |
| [`docs/DEBUGGING.md`](docs/DEBUGGING.md) | evidence-first debugging playbook for every layer |
| [`docs/DESIGN.md`](docs/DESIGN.md) | the concrete v0 architecture: resolver, compiler, learning, safety |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | short ADRs for every non-obvious design choice |
| [`docs/FORK_DELTA.md`](docs/FORK_DELTA.md) | what the MaaFwApp fork reuses/adds/changes, with milestones |
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | how the project got here: session reconstruction, bugs, pitfalls |
| [`docs/M9A_REFERENCE.md`](docs/M9A_REFERENCE.md) | lessons from the mature MAA1999/M9A sample: recognition-gated pipeline graphs, state transitions, custom code only as an extension |
| [`docs/PROTOTYPE.md`](docs/PROTOTYPE.md) | PC prototype phases, live results, limitations |
| [`docs/maa-skills/`](docs/maa-skills/) | vendored MaaFramework authoring skills (MIT, from Everything-Maa) with `index.json` family lazy-loading |
| [`docs/SKILL_LOADING.md`](docs/SKILL_LOADING.md) | load one skill family, not all of Everything-Maa at once |
| [`docs/SCHEMA.md`](docs/SCHEMA.md) | fresh v1 schema: pipelines, pipeline versions, missions, run evidence |
| [`docs/TODO.md`](docs/TODO.md) | current status ledger and prioritised task list |
| [`AGENTS.md`](AGENTS.md) | rules for AI maintainers: read order, hard rules, verification |
| [`schema.sql`](schema.sql) | the data contract in concrete tables |

## Current status (2026-09-19 late)

- **PC prototype is proven.** MaaFW-native brain, `34` tests pass; camera
  and Bilibili 114514 → like ran live with postconditions (AI once, then
  verified 0-token replays). Native pipeline import/export, `{app}`
  patterns, policies, message-stream `needs_input`/`answer`, decision cache
  and replay-K evidence all exist.
- **Android M0/M1 are proven.** The MaaFwApp fork builds and runs; the
  in-app `brain/` resolves `open settings` through the DB, compiles native
  MaaFW nodes and drives the privileged runner with no AIDL change.
- **Android M3.2 is built.** Assistant Compose UI (live virtual-display
  preview, Runs/Review/Data/Logs/Settings), API key/base/model settings,
  DeepSeek vision bootstrap, strict final-state verification and a native
  **MaaMCP pipeline importer**.
- **The graph-native runtime is not done yet.** The original design — store a
  MaaFW pipeline graph per workflow and let AI propose edits — is documented,
  but `Importer.kt`/`Compiler.kt`/`BrainRunner.kt` still flatten the graph
  and submit one task per node, so `next`/`on_error` are stored but not
  executed (ADR-027, `docs/STATUS.md` §2d). This is a code gap, independent
  of the Bilibili test.
- **Version/mission backend is wired.** Bootstrap/import now creates a
  candidate `pipeline_versions` row linked to its source run; approving a
  proposal promotes that version and updates the live pipeline. The PC
  prototype exposes `versions`, `missions`, `mission-create`, `mission-add`
  and `mission-items`; Android has the same DB helpers, with version/mission
  UI still to come.
- **On-device bootstrap now works through both search and toggle.**
  The v1 APK fixes MaaFwApp's missing native `INPUT` bridge case: numeric
  and Chinese `InputText` now works (run #3 typed `350234`; run #4 typed
  `阿米诺斯`; run #7 completed search→first video→like with `verified=1`).
  The runner also logs loaded skill families, pre-checks toggle state with a
  focused crop, never taps the same toggle twice, and retries final
  verification with fresh screenshots. Runs #4 and #7 created candidate
  versions #1/#2 and pending proposals #1/#2. The Assistant goal field now
  respects the IME. Run #10 showed HOME makes the virtual display black, so
  the bootstrap no longer relies on HOME; following MaaFwApp/MAA-Meow it
  syncs launchable apps from `PackageManager` into the `apps` registry and
  can `launch` the target package directly by label. Android 11+ package
  visibility was the missing piece: adding a `<queries><intent MAIN/LAUNCHER>`
  declaration raised the catalog from 21 to 70 launchable apps, and run #12
  (`open the finance manager app`) verified
  `launch -> launched com.anonymous.financemanager`.
- **Assistant controls:** Run tab has a Stop button, **Replay + improve**
  (replay a live pipeline; on failure, AI adapts to the changed UI and files a
  `pipeline_fix` candidate), and Import pipeline via system file picker. A
  verified AI run already creates a candidate `pipeline_versions` row +
  proposal automatically. Review has **Test replay** so a pending candidate is
  exercised as a real MaaFramework run before approval (linked as a
  `pipeline_version_runs` replay, not yet deterministically verified).
  Max AI steps is a Settings value (`agent_max_steps`, default 300, range
  5–1000) plus an early-exit setting (`agent_repeat_limit`, default 5).
  The in-app AI also has real MaaFW `recognize`/`benchmark` tools and can
  file an AI-authored native graph through `propose_pipeline` for Review
  Test replay — no Termux/Python runtime is needed.
- **Latest on-device test is a trust bug, not an action failure.** Run #1
  looped on direct coordinate taps. Run #2 actually liked the video (the
  like icon changed from gray `#61666D` to Bilibili pink `#FF6699` after the
  tap), but the LLM final verifier false-negatived the same screenshot (3
  true / 2 false over 5 calls), so no proposal was saved. No proposal has
  been promoted on-device yet. The fixes are deterministic toggle
  postconditions and the **MaaMCP + Everything-Maa authoring pass**
  producing a native Bilibili pipeline, then import → Review → verified
  replay. Pause/queue surfaces are still ahead.

Latest source APK:
`android/dist/maa-phone-v1-debug.apk`
(`b565cf5f…e9e8`) with the fresh v1 schema (`pipelines`,
`pipeline_versions`, `missions`). Install over v1; a pre-v1 install must be
removed first because there is intentionally no DB migration. Exact evidence
and gaps:
[`docs/STATUS.md`](docs/STATUS.md).

## The MAA stack at a glance

Naming used in the notes: **MaaFW** = MaaFramework, **MaaFwApp** = the
Android shell, **MaaMCP** = the PC MCP authoring/benchmark server,
**Everything-Maa** = the authoring-skill library.

| Project | Role | Integration |
|---|---|---|
| [MaaFramework](https://github.com/MaaXYZ/MaaFramework) | recognition/action engine (OCR, TemplateMatch, ColorMatch, ROI, actions) | real Python binding in `proto/`; prebuilt `v5.13.1` `.so` in the Android fork; native pipeline fields stored 1:1; compiler resolves refs/defaults only |
| [MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) | Android privileged shell (Shizuku/root, native controller, virtual display, overlay, logs) | forked at `android/`; the stock runner contract is reused; our delta is `brain/` + Assistant + importer |
| [MaaMCP](https://github.com/MAA-AI/MaaMCP) | PC/dev-time authoring bench (screencap/OCR, template capture, pipeline save/run, `benchmark_node`) | mapped; pipeline import/export implemented in the prototype, import implemented on Android; **not embedded** and not in the hot loop; actual Bilibili authoring pass is next |
| [Everything-Maa](https://github.com/KhazixW2/Everything-Maa) | veteran Maa workflow-authoring skills (field reference, coordinate hygiene, generate/test/diagnose) | eight relevant skills vendored under `docs/maa-skills/` (MIT); [`AUTHORING.md`](docs/AUTHORING.md) is the rulebook |

The method itself is ported from the `chatbot-ai` reference project
(`../00ref/chatbot-ai`): deterministic-first, AI proposes, human approves,
maintenance as a first-class loop.

## Repository layout

```

├── README.md              this file
├── schema.sql             the data contract (core + IO/cost/safety/eval tables)
├── docs/                  goal, method, integration, authoring, status, debugging, ADRs
├── proto/                 PC-side Python reference brain (MaaFW + ADB engines)
└── android/               MaaFwApp fork + on-device brain/ and Assistant
```

Reference checkouts (not runtime dependencies) live one level up in
`../00ref/`. See [`docs/MAA_STACK.md`](docs/MAA_STACK.md) for the exact
revision pins.

## Quick taste of the prototype

```bash
cd proto
python3 -m venv --system-site-packages .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install maafw
python tools/setup_maa.py
# put the provider key in proto/.env
.venv/bin/python -m pytest tests/ -q
.venv/bin/python -m maa_phone goal "open the camera app and take a photo"
.venv/bin/python -m maa_phone proposals
.venv/bin/python -m maa_phone approve 1
.venv/bin/python -m maa_phone goal "open the camera app and take a photo"   # 0 AI, verified
```

`proto/README.md` lists all commands. For Android build, install and test
steps, see `android/MAA_PHONE.md`.

## License / provenance

The Android fork inherits MaaFwApp's **AGPL-3.0**; a distributed fork stays
AGPL. MaaFramework is **LGPL-3.0** and is linked, not patched. MaaMCP is
**AGPL-3.0** and stays a separate dev-time tool. Everything-Maa skills are
**MIT** and are vendored under their own provenance.
