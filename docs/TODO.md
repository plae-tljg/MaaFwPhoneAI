# TODO / status ledger

Snapshot: **2026-09-20 16:45 HKT**, after the MaaMCP/Everything-Maa authoring
pass, proposal #12 approval + mission replay, persistent agent-question
recovery, chat transcript, Mission queue/pause/cancel/schedule, and the
Import-pipeline button layout fix. Debug builds are local-only; device evidence
is runs #42/#43/#45/#46-#49/#52.

Current source: DB v4 adds `mission_queue`; install over v3/v2 upgrades in
place. The previous `maa-phone-v1-debug.apk` hash is obsolete.

## 0. Recently completed

- Fresh v1 schema: `pipelines`, `pipeline_versions`, `pipeline_version_runs`,
  `missions`, `mission_items`; runtime renamed `skills` → `pipelines`.
- Bootstrap/import creates candidate `pipeline_versions` linked to the source
  run; approval promotes a version and updates the live pipeline.
- Proto CLI: `pipelines`, `versions`, `missions`, `mission-create`,
  `mission-add`, `mission-items`.
- Android DB helpers for pipelines/versions/missions; `Learner` uses them.
- Latin/Chinese/numeric `InputText` fixed: MaaFwApp native bridge now handles
  `INPUT`, direct key-character events for ASCII, clipboard+paste for CJK.
- Skill loader: packages Everything-Maa `index.json` + skills into assets;
  logs `skills loaded: family=... -> [...]` and passes guidance to the model.
- Toggle handling: focused pre-tap state check, never tap the same toggle
  twice, final verifier retries with fresh screenshots.
- Generic search-loop recovery: repeatedly tapping a field can trigger one
  auto-text of the query extracted from the goal.
- UI keyboard fix: `imePadding()` + scrollable Assistant content so the goal
  field is not hidden by the IME.
- Virtual-display HOME fix: HOME key on display != 0 launches the launcher on
  that display instead of sending a global HOME to the primary display.
- Live results: run #4 (`阿米诺斯`), run #7 (`350234 → like`), and run #12
  (`open the finance manager app`) completed with `verified=1` and created
  candidate versions/proposals.
- App catalog: Android 11+ `<queries><intent MAIN/LAUNCHER>` added; catalog
  now indexes 70 launchable apps, including `com.anonymous.financemanager`.
  Run #12 (`open the finance manager app`) launched it directly and passed.
- **Stop button** added to the Assistant Run tab; it requests cancellation,
  stops the privileged runner and marks the run `cancelled` where possible.
- **Import pipeline** now uses a system file picker instead of a fixed
  `files/brain/imports/pipeline.json` path, so "no pipeline file" no longer
  depends on that location. A verified AI run **already** creates a
  candidate `pipeline_versions` row plus a `pipeline_new` proposal
  automatically; the import button is for externally authored MaaFW JSON.
- **Max AI steps** is now a setting (`agent_max_steps`, default 300, range
  5–1000) plus an early-exit setting (`agent_repeat_limit`, default 5,
  range 2–10) in the Assistant Settings tab. Empty model responses/actions,
  failed engine results, and repeated identical action signatures count
  toward early exit.
- **Replay + improve** (Run tab): replay live pipeline; on failure, AI reads
  the existing pipeline definition + failure evidence, adapts to the current
  UI and files a `pipeline_fix` candidate. **Review Test replay** executes a
  pending candidate and links the run as replay evidence before approval.
- **Recognition-detail bridge**: `RemoteService.recognitionDirect(...)`
  returns real MaaFW OCR/TemplateMatch/ColorMatch detail. The AI gets an
  automatic per-frame OCR observation and can call `recognize` itself.
- **Kotlin MaaMCP tool port**: `recognize`, `benchmark` and
  `propose_pipeline` are exposed to the model; AI-authored graph maps are
  stored as Review proposals and compiled/test-replayed by the graph-native
  runner. No Termux / embedded Python is required.
- **Native graph storage contract**: `definition_json` now stores MaaFW-native
  node maps directly, with an explicit `entry`; DB v3 migrates old linear rows/
  proposals and repairs legacy nested `action:{type,param}` /
  `recognition:{type,param}` shapes once. Nested shapes are rejected at runtime,
  not normalized there.
- Proto tests: **34 passed**.
- **Deterministic Android postconditions**: `Verifier` now supports `pixel`,
  `element`, `screen_text`, `file_count`, and bare MaaFW recognition maps
  (`{node, recognition, expected, roi}`). Element/screen-text checks use
  `RemoteService.recognitionDirect(...)`; file-count captures a baseline
  before the pipeline starts.
- **OCR model bundle**: `proto/bundle/model` (PP-OCR det/rec/keys) is packed
  into `PI/brain_ocr` and added to brain resource paths. Before this the
  recognition bridge returned JSON with empty OCR detail; the MaaFW log showed
  `OCRResMgr: Failed to load det or rec: [name=]`.
- **Test replay #42 verified**: proposal #12 (7-node AI-authored graph:
  StartApp → OCR verify → DoNothing) replayed with
  `verified=1`; evidence JSON contains a real OCR hit
  `{"text":"闹钟","score":0.998008,"box":[388,641,70,40]}`.
- **DB v3 migration repair**: legacy nested action/recognition maps
  (`{"action":{"type":...,"param":{...}}}`) are flattened once at migration
  time. On-device proposal/version #11 were repaired; runtime compiler stays
  native-only.
- **Review evidence inspector**: Review cards now show goal, entry, node
  count/first nodes, postcondition, and linked replay runs
  `id:role:success:verified` before Approve/Reject.
- **Android unit tests are green**: `466 tests`, `0 failed`, `2 skipped`.
  This also fixes the stale `FakePrivilegedService` (missing new AIDL
  `recognitionDirect`) and routes Assistant buttons through `MaaButton` /
  `MaaOutlinedButton`.
- **Chat-like agent IO (`ask`/`needs_input`)**: the planner can call
  `{"action":"ask","question":...,"options":[...],"allow_free_text":...}`;
  `AgentRunner` writes a `messages` question row, sets
  `runs.state='needs_input'`, shows an Assistant modal with option buttons and
  a free-text field, then resumes the same run when the user answers.
  Device-rendered and cancel-tested; the temporary debug hook used to open the
  modal without an AI key was removed afterwards.
- **Android token accounting**: `DeepSeekClient` accumulates provider
  `usage.total_tokens` (or prompt+completion fallback); `AgentRunner` drains it
  into `runs.ai_cost` at finish, and the Runs tab shows `cost=<n>tok`.
- **Chat is per-run, not one concatenated context**: `DeepSeekClient` already
  rebuilds a request as system + current user image only, with the last 8
  actions inside the user text; it never sends the whole cross-run message
  table. The Assistant Chat tab now selects a specific `run_id` (buttons for
  recent runs) instead of rendering the global message stream, matching the
  actual per-run context.
- **Bootstrap per-step speed**: reuse the frame captured at step start for
  native OCR (no second MaaFW Screencap task), call the vision screen observer
  only when native OCR has no real text, cut action delay 1200→250 ms and
  max-token caps, add per-step elapsed time to status. Mock-API run #4 went
  from ~10.4 s to ~6.8 s for the same 2-step trajectory; real latency still
  depends on the provider.
- **Proposal survives a verifier false-negative**: `Learner.propose` takes
  `visionVerified`; a failed final LLM verify still files a candidate with
  `{"vision_verified":false,"proposed_from_failed_verification":true}` so
  Review/Test replay can decide. Proposal conversion failures are caught and
  surfaced in run status instead of silently dropping the trajectory.
- **Official DeepSeek model default**: if no explicit model setting and the
  base URL is `api.deepseek.com`, use `deepseek-chat` instead of the custom
  gateway name `deepseek-v4-flash`; Settings carries a hint. This avoids an
  invalid-model retry loop looking like “slow AI / no proposal”.
- **Assistant preview reuses MaaFwApp modules**: `rememberMovablePreview`,
  `LivePreview`, `FullscreenPreview`, touch markers and watchdog state now
  drive the Assistant cell. A missed `surfaceChanged` after `setFixedSize`
  is retried, click-to-expand survives Activity recreation, and the idle
  label uses the watchdog/display state instead of runner phase alone.
- **Import-pipeline button fix**: the Run tab action row is now a `FlowRow`
  with `maxLines=1`; "Import pipeline" no longer squeezes to one character per
  row (device UI dump confirms its own row).
- **MaaMCP / Everything-Maa authoring pass**: `proto/pipelines/clock_app_maamcp.json`
  is a real native graph authored with the MaaMCP pipeline protocol and the
  vendored Everything-Maa guide/generate/testing skills. `Importer` now accepts
  a thin `{pipeline, entry, postcondition}` wrapper, creates a
  `pipelines` + candidate `pipeline_versions` row, and device Test replay
  verified it: proposal #14 → version #13 → run #52 `verified=1` with OCR hit
  `闹钟`. Plain-map import proposal #13 → run #44 also `verified=1`.
- **Proposal #12 approved and replayed as a mission item**: Review approval
  promoted version #12/pipeline #9 live; mission item #2 run #45 finished
  `path='pipeline'`, `mission_item_id=2`, `verified=1`.
- **Persistent agent-question recovery + chat transcript**: question messages
  are inserted with `state='pending'`; `BrainDb.latestPendingQuestion()` joins
  `runs.state='needs_input'` with the unresolved message, and the Assistant
  reopens the modal after Activity/process recreation. Answering calls
  `AgentRunner.resume`, which reconstructs trajectory/history from
  `runs.steps_json` and continues the run; Cancel dismisses the question and
  cancels the run. The Chat tab renders a role-aligned transcript with
  question/options/state labels.
- **Mission queue / pause / cancel / schedule**: DB v4 `mission_queue` persists
  queued/running/done/failed/cancelled items and their run ids. Run all,
  Pause/Resume (between items), Cancel (stops the current MaaFW run), Clear
  queue, and an in-app scheduled run-all are in the Missions tab. Device
  evidence: queue run #46/#47, schedule run #48/#49, all `done` with
  `mission_item_id` links.
- **Mission UI + runner**: new Assistant **Missions** tab creates/deletes
  missions, pins live pipelines or adds goal items, enables/disables items,
  runs an item through `BrainRunner.runMissionItem`, and shows the latest run
  state/verified evidence. Run #43 (`open_settings` pinned to mission item #1)
  recorded `path='pipeline'`, `success=1`, `mission_item_id=1`; a candidate
  version can be pinned and run through the same path.

## 1. P0 — immediate correctness and product surface

| # | Task | Why / acceptance | Status |
|---|---|---|---|
| P0-1 | Verify UI keyboard fix on device | Goal input stays visible while IME is open; type a goal and see the full field. | source fixed; build installed; needs manual/device check |
| P0-2 | Stop using HOME as virtual-display normalization | Run #10 showed every screenshot black after `key home`: the launcher on the secondary display does not render, and HOME is global. MaaFwApp/MAA-Meow resolve packages with `PackageManager.getLaunchIntentForPackage` and launch them directly; we sync launchable apps into the `apps` registry and launch by package. Run #12 (`open the finance manager app`) now passes. HOME/drawer stays an optional foreground-only test. | ✅ done (run #12 verified) |
| P0-3 | Version Review UI | List candidate `pipeline_versions`, show graph/steps/evidence, approve/reject; approval updates live pipeline. | mostly done: Review cards now show goal/entry/nodes/postcondition and linked replay runs; a full diff/version history view is still missing |
| P0-4 | Deterministic postcondition for approved pipelines | First-time AI may use the LLM verifier; after approval a pipeline replay must use `pixel`/`element`/`file_count`/`screen_text`/Custom. | ✅ recognition-style (`element`/`screen_text`/bare MaaFW map) wired and device-verified by run #42; `pixel` and `file_count` wired, file_count still needs a device scenario test. |
| P0-5 | Mission UI + runner | Create missions, add pipeline/version items, run an item into `runs(path='pipeline')`; failures become evidence. | ✅ done through M2 MVP: create/pin/enable/delete, single-item and queued runs, pause/cancel, persisted queue and in-app schedule (runs #43/#45–49). Android alarm-independent background scheduling is not claimed. |
| P0-6 | Graph-native execution | Execute stored `next`/`on_error`/anchors as a graph; stop flattening/renaming nodes. | ✅ compiler/runner graph-native and conditional AI-authored graph #12 Test replay verified (run #42); a tested externally imported M9A/Bilibili graph is still pending. |

## 2. P1 — bootstrap reliability and AI maintenance

| # | Task | Why / acceptance | Status |
|---|---|---|---|
| P1-1 | AI can read tables and debug | Maintenance AI reads `runs`, `pipeline_versions`, `pipeline_version_runs`, `messages`, `proposals`; proposes `pipeline_fix`/`element_fix` with evidence. | tables/CLI exist; AI-facing query tool/loop not done |
| P1-2 | First-time toggle robustness | Use focused crop before/after toggle; handle already-active state; record state evidence. | pre-tap check and retry implemented; more validation needed |
| P1-3 | Wait/stabilize semantics | Use M9A-style `pre/post_wait_freezes` / explicit recognition instead of blind waits. | prompt-level waits only; not in pipeline execution yet |
| P1-4 | Android token accounting | Parse provider usage into `runs.ai_cost`; show cost in Runs. | ✅ done: `usage.total_tokens` (or prompt+completion) drains into `runs.ai_cost`; Runs shows `cost=<n>tok` |
| P1-5 | Search-field recovery polish | Auto-recovery currently types query on repeated field taps; make it logged/visible and test with several apps. | implemented, needs test |
| P1-6 | Register apps/hints for finance and other test targets | Add `<queries><intent MAIN/LAUNCHER>` so the catalog sees all launchable apps; verify `com.anonymous.financemanager` and other user apps. | ✅ done (run #12); keep aliases/hints updated as needed |
| P1-8 | Chat-like agent IO with approve/ask | A ChatGPT/Codex/opencode-like thread/modal: assistant can show a plan or question, offer option buttons (Yes/No/Apply/Cancel) plus a free-text field, let the user answer, and continue the run from `needs_input`. | ✅ done incl. recovery: `ask` tool, option/free-text modal, `needs_input`, persisted pending question, process-recovery resume from `steps_json`, and a role-aligned Chat transcript tab. |
| P1-7 | Repeat/loop guard policy | Distinguish “repeat tap without progress” from legitimate multi-step waits; fail early with a useful message. | implemented as `agent_repeat_limit` (empty/failed results + identical action signatures), plus the three-tap/screen-recognition point guard; native OCR observations are now fed by packaged models, but model-supplied `tap` points still need the same guard |

## 3. P2 — authoring, healing and trust

| # | Task | Why / acceptance | Status |
|---|---|---|---|
| P2-1 | MaaMCP + Everything-Maa authoring pass | Produce a native pipeline graph with OCR/TemplateMatch/ColorMatch/ROI, benchmark nodes, import and replay. | ✅ first pass done: clock-app OCR/ROI graph in `proto/pipelines/`, imported as candidate versions #13, Test replay run #52 `verified=1`; a Bilibili/TemplateMatch workflow remains future work. |
| P2-2 | MaaFW Record/Replay/Dbg or MaaMCP `benchmark_node` promotion gate | Replay-K before a version goes live. | Android now has a single candidate Test replay + `pipeline_version_runs` evidence; replay-K and deterministic verification still not wired |
| P2-3 | Automatic element healing | Failed recognition creates `element_fix` with new ROI/template/benchmark evidence. | not done |
| P2-4 | Scenario specs per user story | Camera, Bilibili, finance-app budget/transaction, dark mode. | not done |
| P2-5 | Release key handling | Debug APK embeds local key; release must use user-provided/separately injected key. | not done |

## 4. Known current test failures / evidence

- Run #9/#10 (`go to home, use the finance manager app, create a budget...`)
  failed with repeated `key home` and max steps. Run #10 screenshots are all
  black (`1280x720`, mean `(0,0,0)`), showing that HOME does not produce a
  usable home screen on the virtual display and the AI is blind-retrying.
  The v1 build now fails fast after 3 HOME taps and the prompt says to use
  `launch <target package>` instead of HOME. The finance app package still
  needs to be registered in `apps` for direct launch.
- Bilibili like is non-idempotent. Run #5 toggled an already-liked video off;
  run #7 succeeded with the pre-tap focused state check. Any consolidated
  pipeline must include a deterministic liked/unliked postcondition.
- The bootstrap is still model-driven. The LLM can omit `text`, loop taps, or
  misread a full-screen icon; the current mitigations are focused crop,
  auto search recovery, retries and early failure. This is accepted for
  first-run discovery, not for deterministic replay.
- Fixed: MaaFw OCR had no model files on device. `PI/brain_ocr/model/ocr`
  now ships from `proto/bundle/model`; run #42 proves real OCR hits again.
- Fixed: pending legacy graph proposals (e.g. #11) that were written before
  the flat-native contract are repaired by the v3 DB migration.

## 5. Definition of done for the next demo

1. On a clean v1 install, the UI keyboard does not hide the goal field.
2. `key home` in background mode does not disturb the main screen.
3. Review UI can approve candidate versions; an approved pipeline replays
   with `path='pipeline'` and a deterministic postcondition.
4. Mission UI can add that pipeline and run it from the mission list.
5. The maintenance AI can read the resulting rows and explain/propose a fix
   for one failed run.
