# Handoff — goal, history, current state and how to continue

Snapshot: **2026-09-19 late (HKT).** Some earlier notes say 2026-09-20
because the machine clock jumped during the original OpenCode session; where
dates conflict, use [`STATUS.md`](STATUS.md) and the raw logs/hashes.

This document is for whoever (human or AI) continues the project. It records
where the work came from, what is actually implemented, what failed, and how
to pick up without re-discovering the same things.

## 0. Sources reviewed

The handoff was compiled from:

- the original OpenCode session
  `ses_f453f2f26ffepIPk86HirhYG6F` — *"MaaMCP phone automation DB structure
  research"* — reconstructed in insertion order under
  `../../00ref/opencode_history/` (`MAIN_true_order.txt` is the safe one;
  the machine clock jumped, so `ORDER BY time_created` is misleading);
- the DeepSeek Harness sessions on this workspace (the long
  `session-17bc26a2…` run and its compacted fork), which continued the
  implementation through Android M0/M1/M3.2 and ended when the next turn
  exceeded the 1M context window;
- the actual code, DBs, logs, APKs and git checkouts in this workspace.

Concretely, the continuation context came from:

- DeepSeek Harness workspace session
  `session-17bc26a2-cfff-4e8c-ac13-0bdcb58bda2e`
  (the long build/verify run: research, design, prototype, Android M0/M1/M3,
  M3.2);
- its compacted/resumed fork `session-f099d998-67bb-4b84-8e3e-e64f63272531`,
  where the final "update all the docs" turn hit
  `CONTEXT_WINDOW_EXCEEDED` at **1,048,576 tokens** (792,585 input +
  256,000 completion request) and could not answer;
- the `/compact` follow-up also failed for the same reason.

That is why this document set exists: the final goal/status/debugging
recording had to be completed in a fresh, smaller context.

## 1. The goal in one paragraph

Read [`GOAL.md`](GOAL.md) and [`WORKING_STYLE.md`](WORKING_STYLE.md) first.
Short version: `maa-phone` is an on-device Android assistant whose automation
knowledge is a small SQLite DB the AI maintains. Deterministic pipelines
compile to real MaaFramework pipeline JSON; the vision model is only the
fallback; every AI success is recorded, verified and (after human approval)
promoted into a reusable pipeline; repeat AI cost trends to zero. The method
(small stable engine vocabulary + free-structured JSON rows + propose/Review
+ verification + evidence) is ported from `chatbot-ai`; MaaFramework is the
execution substrate, not the thesis.

## 2. What the previous session actually did

The session's timestamps are misleading because the clock jumped backwards.
Using SQLite `rowid` insertion order, the true timeline was:

| Rowids | Wall clock | Work |
|---|---|---|
| 1209–1356 | 01:39–03:13 | researched chatbot-ai / MaaMCP / MaaFramework / MaaFwApp; wrote the v0 design package (`DESIGN.md`, `DECISIONS.md`, `FORK_DELTA.md`, `schema.sql`); built the Phase A raw-ADB prototype; live-tested camera + Bilibili; documented `PROTOTYPE.md` |
| 1357–1449 | 03:13–03:31, then clock rolled back to 19:32 | built the MaaFW-native runner (`maa_exec.py`, `runner.py`, compiler v2, `agent_maa.py`, debug bundles); tested camera through MaaFW |
| 1450–1464 | 19:43–19:51 | stuck in a loop over a replay that "succeeded but took no photo" (the photo appeared ~3 s later; the check ran immediately), then DeepSeek returned empty responses. The session died with `finish=None` on the last messages. |

The immediate symptom — **silent success** — had no fix when the session
died. That is the exact gap the design had warned about.

## 3. What was completed after the crash

The PC prototype (`proto/`) is the behavioral reference and is tested (`34`
unit tests pass).

- **Postconditions** — `pipelines.postcondition_json`, `runs.verified`,
  `runs.verify_json`; `verifiers.py` implements `file_count`, `element`,
  `screen_text`, `pixel`. The runner fails a replay whose recognizers
  matched but whose real-world effect did not happen. AI runs can be
  verified with `goal ... --verify '<json>'`; verified evidence becomes the
  proposal's postcondition (ADR-015/017).
- **Native tool calling** — `agent_maa` calls a `phone_action` function
  (strict enum) with JSON-mode fallback, fixing the DSML leak that killed an
  early Bilibili run (ADR-016).
- **Learning cleanup** — point taps bind to OCR only when centred on a
  reasonably sized text box; decorative glyphs/lone digits are dropped;
  near-identical taps collapse; `apps`/hints make the agent prefer
  `launch`.
- **Native field plumbing + bridge** — `pipeline_model.py` normalizes and
  validates MaaFW fields; `compiler.py` resolves refs/defaults only;
  `pipeline_io.py` imports/exports standard MaaFW pipeline JSON
  (ADR-020/021).
- **Data-contract work** — `{app}` aliases, autonomy enforcement, policies,
  message-stream `needs_input`/`answer`, decision cache and replay-K
  evaluation.
- **Debug layer** — per-run bundles (`events.jsonl`, `manifest.json`,
  screenshots, draws, `maafw.log`), `trace`, `export`.
- **Live verified results**:
  - Camera: AI 5,107 tokens → two replays 0 tokens, `verified=1`, exactly
    one new photo each (49→50→51).
  - Bilibili: AI 33,558 tokens, `verified=1` via pixel check → replay
    0 tokens / 30.6 s, 6/6 MaaFW nodes matched, `verified=1`.
  - Negative evidence retained: run #5 (like never happened), run #7
    (toggled an already-liked video), run #10 (second launch showed a
    different Bilibili state).

## 4. What was built afterwards (Android M0–M3.2)

Details and hashes: [`STATUS.md`](STATUS.md). Summary:

- **M0 — shell proven.** Fork of MaaFwApp (`android/`, reference `70cd377`)
  builds with MaaFramework 5.13.1. First APK rejected every run because
  Gradle/NDK `llvm-strip` corrupted the prebuilt `libfastdeploy_ppocr.so`
  (`empty/missing DT_HASH/DT_GNU_HASH`). Fixed with
  `packaging.jniLibs.keepDebugSymbols`; a rebuilt APK matches the release
  library byte-for-byte and the demo task reports `COMPLETED`.
- **M1 — in-app brain + Assistant.** `brain/` (SQLite over the shared
  schema, resolver, compiler, `RunPlan` via `RunnerPort`) drives the
  privileged runner with **no AIDL change**. `open settings` runs
  deterministically. The first M1 bug was the override shape: a bare node
  instead of `{ nodeName: node }`; fixed.
- **M3/M3.2 — bootstrap AI + UI + import.** Assistant Compose UI with live
  virtual-display preview, Runs / Review / Data / Logs / Settings tabs;
  one-node native actions (`Screencap`, `Click`, `StartApp`, `InputText`,
  `ClickKey`, `Swipe`); DeepSeek vision loop; strict final-state
  verification; a pipeline candidate proposal on success; API key/base/model
  settings with build-time `.env` injection; native pipeline importer with
  template copying into a versioned bundle.

## 5. What failed (and why it matters)

The on-device Bilibili bootstrap did **not** produce a successful verified
run, but the failure is now understood precisely (ADB re-check,
2026-09-19 23:45):

- **Run #1** (goal with 阿米诺斯): repeatedly tapped around `y≈27` (status
  bar) until `max steps reached`. The repeat-tap guard only tracks
  `locate` points, not model-supplied `tap` points; no search/type action
  happened.
- **Run #2** (114514): reached the video page, located/tapped `(355,575)`,
  then called `done`. The DB says the final vision check failed with "like
  button gray". **That is a false negative.** Pixel ground truth: before
  the tap `run2_turn4.png` had gray `#61666D` at the like bbox
  `(328,570,62,52)`; after the tap `run2_turn5/6/final.png` had Bilibili
  pink `#FF6699` + `#FFB2CC` in the same bbox. The like action worked. The
  exact verifier prompt, replayed five times on the same final screenshot,
  returned **3 true / 2 false** with contradictory evidence. Run #2 also
  never called `text`; it reached `114514` by tapping a pre-existing
  search-history/suggestion chip.
- Root cause classes: (a) a single LLM verifier is not a reliable
  promotion gate; (b) the bootstrap is coordinate-based and has no OCR
  element list. The architecture correction (ADR-022/025) is deterministic
  local postconditions (especially for toggles) and native authoring with
  **MaaMCP + Everything-Maa** instead of trusting point trajectories.

Current on-device DB evidence:

```
runs #1/#2 = ai, failed, verified=0
run #2 real end state = liked (pixel-proven); verifier = false negative
proposals  = (none)
settings   = deepseek-v4-flash; hint:tv.danmaku.bili seeded
```

## 6. What is still open

Ordered by priority:

### P0 — first verified on-device reusable workflow

0a. **Make the pipeline row a real graph and execute it (ADR-027).** The
    original design was correct: store a MaaFW pipeline graph per workflow
    and let AI propose edits. Current `Importer.kt` + `Compiler.kt` +
    `BrainRunner.kt` flatten the graph, rename nodes and submit one task per
    node, so `next`/`on_error` are stored but not executed. Fix the runtime
    path before judging the idea.
0b. **Fix the verifier primitive (ADR-025):** wire a deterministic toggle
    postcondition (pixel colour or MaaFW `Custom` node) so the LLM verifier
    is advisory, not the gate. The installed run #2 already liked the video
    (gray `#61666D` → pink `#FF6699`), but the same final screenshot produced
    3 true / 2 false from the LLM verifier.
1. Run MaaMCP + Everything-Maa against the phone for Bilibili, using the
   M9A pipeline pattern ([`M9A_REFERENCE.md`](M9A_REFERENCE.md)): OCR
   search entry/search text, TemplateMatch first-video card,
   template/ColorMatch like icon, ROI, `order_by`/`index`,
   recognize→act→recognize, explicit `next`/recovery. M9A is structure
   reference only; no game data and no Bilibili hardcoding.
2. Benchmark nodes (`benchmark_node`), export `pipeline.json` + templates.
3. Import via Assistant → inspect proposal → Review → approve.
4. Replay deterministically with a real postcondition; record
   `path='pipeline'`, `ai_cost=0`, `verified=1`.
5. Exercise one failure case and keep it as healing evidence.

### P1 — close the Android method loop

6. Wire postcondition verifiers on Android (`pixel`, `file_count`,
   `element`, `screen_text`) via MaaFW `Custom`/Agent nodes.
7. Implement M2 surfaces and execution semantics: tile / notification /
   float ball, Queue / Do now / Cancel, step-wise pause/resume through
   `runs.progress_json`.
8. Record `runs.ai_cost` from provider usage and show it in Runs.
9. Extend the repeat-tap guard to all action points; add an OCR/recognition
   observation list to the bootstrap if it stays.

### P2 — maintenance and trust

10. Make replay-K a promotion gate (MaaFW Record/Replay/Dbg or MaaMCP
    `benchmark_node`), and implement automatic `element_fix` healing.
11. Add executable user-story scenarios (camera, Bilibili, dark mode, chat).

## 7. How to continue in this workspace

### 7.1 PC prototype

```bash
cd proto
.venv/bin/python -m pytest tests/ -q
.venv/bin/python -m maa_phone runs
.venv/bin/python -m maa_phone pipelines
.venv/bin/python -m maa_phone thread
.venv/bin/python -m maa_phone trace <run_id>
.venv/bin/python -m maa_phone export <run_id>
```

`--engine maa` is the product-relevant path; `--engine adb` is the Phase A
harness. Keep evidence: `reset` deletes the DB and `data/runs/`.

### 7.2 Android

Build environment and current APK hashes are in
[`../android/MAA_PHONE.md`](../android/MAA_PHONE.md). The canonical build
command is the long `ANDROID_USER_HOME=... ANDROID_SDK_ROOT=...
GRADLE_USER_HOME=... ./gradlew :app:assembleDebug` in that document.

Pull evidence:

```bash
adb shell run-as com.aliothmoon.maafw.maaphone cp databases/brain.db files/db_copy.db
adb exec-out run-as com.aliothmoon.maafw.maaphone cat files/db_copy.db > /tmp/brain.db
sqlite3 /tmp/brain.db "SELECT id,path,state,success,verified,error FROM runs ORDER BY id;"
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/maafw.log | tail -80
```

The full triage sequence is in [`DEBUGGING.md`](DEBUGGING.md).

## 8. Things to know before changing code

- **The clock may jump; mtimes lie.** Use SQLite `rowid`/insertion order for
  chronology.
- **Two icons confuse testers.** `Maa-phone` is the stock PI library with
  demo tasks; `Assistant` is the brain. Most "open settings failed" reports
  were run from the wrong icon or with M1 installed over a broken M0.
- **Override shape matters.** Every compiled node patch must be
  `{ nodeName: node }`; that single bug caused "Skill … completed with
  failures" on every early M1 run.
- **Display mode matters.** Background mode runs on the virtual display;
  learned coordinates are display-profile-specific.
- **Strict verification is intentional, but a model verdict is not ground
  truth.** Do not "fix" a failed run by weakening the verifier; instead add
  a deterministic postcondition. Run #2 actually liked the video and was
  false-negatived by the LLM verifier (ADR-025).
- **Do not grow the bootstrap into the product.** Reusable workflows go
  through MaaMCP + Everything-Maa and the importer; the bootstrap is for
  first contact only (ADR-022).
- **Debug keys are in debug APKs.** Do not distribute the current debug
  build; release should use a user-provided or separately injected key.
- **Reference pins matter.** See [`MAA_STACK.md`](MAA_STACK.md) before
  claiming an integration; refresh the pins deliberately.

## 9. File map for the next maintainer

| Need | Read / edit |
|---|---|
| Goal / scope | `docs/GOAL.md` |
| Method / vocabulary / extension rule | `docs/WORKING_STYLE.md` |
| Human IO contract | `docs/IO.md` |
| MAA repo roles / pins / licenses | `docs/MAA_STACK.md` |
| Runner + native field contract | `docs/INTEGRATION.md` |
| Workflow authoring flow | `docs/AUTHORING.md`, `docs/maa-skills/` |
| Architecture / ADRs / milestones | `docs/DESIGN.md`, `docs/DECISIONS.md`, `docs/FORK_DELTA.md` |
| Current state / gaps | `docs/STATUS.md` |
| Failure triage commands | `docs/DEBUGGING.md` |
| Prototype implementation | `proto/`, `proto/README.md` |
| Android implementation | `android/`, `android/MAA_PHONE.md` |
| Data contract | `schema.sql` |
