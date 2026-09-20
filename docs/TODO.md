# TODO / status ledger

Snapshot: **2026-09-20 10:15 HKT**, after the v1 input/toggle/UI fixes and
the `search 阿米诺斯` / `search 350234 → like` live tests.

Current APK: `android/dist/maa-phone-v1-debug.apk`
sha256 `e673b57f0f6bdd50b18ebd033efb78e2b6048c55977ba544a32bfe9ad9c7e9e1`
(pushed to `/sdcard/Download/`; uninstall old app first because v1 has no DB
migration).

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
  node maps directly, with an explicit `entry`; DB v2 migrates old linear rows/
  proposals once. Nested `action:{type,param}` / `recognition:{type,param}`
  shapes are rejected, not normalized at runtime.
- Proto tests: **34 passed**.

## 1. P0 — immediate correctness and product surface

| # | Task | Why / acceptance | Status |
|---|---|---|---|
| P0-1 | Verify UI keyboard fix on device | Goal input stays visible while IME is open; type a goal and see the full field. | source fixed; build installed; needs manual/device check |
| P0-2 | Stop using HOME as virtual-display normalization | Run #10 showed every screenshot black after `key home`: the launcher on the secondary display does not render, and HOME is global. MaaFwApp/MAA-Meow resolve packages with `PackageManager.getLaunchIntentForPackage` and launch them directly; we sync launchable apps into the `apps` registry and launch by package. Run #12 (`open the finance manager app`) now passes. HOME/drawer stays an optional foreground-only test. | ✅ done (run #12 verified) |
| P0-3 | Version Review UI | List candidate `pipeline_versions`, show graph/steps/evidence, approve/reject; approval updates live pipeline. | partial: Review lists proposals with Test replay / Approve / Reject; candidate graph/evidence inspector still missing |
| P0-4 | Deterministic postcondition for approved pipelines | First-time AI may use the LLM verifier; after approval a pipeline replay must use `pixel`/`element`/`file_count`/`screen_text`/Custom. | Android `pixel` verifier wired on live replay + candidate Test replay; `element`/`screen_text`/`file_count` still pending; toggle runs generate a pixel postcondition from the post-tap frame. |
| P0-5 | Mission UI + runner | Create missions, add pipeline/version items, run an item into `runs(path='pipeline')`; failures become evidence. | schema/backend ready; UI/runner not done |
| P0-6 | Graph-native execution | Execute stored `next`/`on_error`/anchors as a graph; stop flattening/renaming nodes. | partial: Compiler emits one graph and BrainRunner submits one MaaFW task; legacy linear steps are chained with `next`. Conditional graph authoring and a tested imported native graph still pending. |

## 2. P1 — bootstrap reliability and AI maintenance

| # | Task | Why / acceptance | Status |
|---|---|---|---|
| P1-1 | AI can read tables and debug | Maintenance AI reads `runs`, `pipeline_versions`, `pipeline_version_runs`, `messages`, `proposals`; proposes `pipeline_fix`/`element_fix` with evidence. | tables/CLI exist; AI-facing query tool/loop not done |
| P1-2 | First-time toggle robustness | Use focused crop before/after toggle; handle already-active state; record state evidence. | pre-tap check and retry implemented; more validation needed |
| P1-3 | Wait/stabilize semantics | Use M9A-style `pre/post_wait_freezes` / explicit recognition instead of blind waits. | prompt-level waits only; not in pipeline execution yet |
| P1-4 | Android token accounting | Parse provider usage into `runs.ai_cost`; show cost in Runs. | not done |
| P1-5 | Search-field recovery polish | Auto-recovery currently types query on repeated field taps; make it logged/visible and test with several apps. | implemented, needs test |
| P1-6 | Register apps/hints for finance and other test targets | Add `<queries><intent MAIN/LAUNCHER>` so the catalog sees all launchable apps; verify `com.anonymous.financemanager` and other user apps. | ✅ done (run #12); keep aliases/hints updated as needed |
| P1-8 | Chat-like agent IO with approve/ask | A ChatGPT/Codex/opencode-like thread/modal: assistant can show a plan or question, offer option buttons (Yes/No/Apply/Cancel) plus a free-text field, let the user answer, and continue the run from `needs_input`. | `messages` table + Assistant UI exist; option/free-text modal and continuation are not built |
| P1-7 | Repeat/loop guard policy | Distinguish “repeat tap without progress” from legitimate multi-step waits; fail early with a useful message. | implemented as `agent_repeat_limit` (empty/failed results + identical action signatures), plus the three-tap/screen-recognition point guard; tune with real runs |

## 3. P2 — authoring, healing and trust

| # | Task | Why / acceptance | Status |
|---|---|---|---|
| P2-1 | MaaMCP + Everything-Maa authoring pass | Produce a native Bilibili pipeline graph with OCR/TemplateMatch/ColorMatch/ROI, benchmark nodes, import and replay. | MaaMCP/exporter/import bridge exist; pass not run |
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

## 5. Definition of done for the next demo

1. On a clean v1 install, the UI keyboard does not hide the goal field.
2. `key home` in background mode does not disturb the main screen.
3. Review UI can approve candidate versions; an approved pipeline replays
   with `path='pipeline'` and a deterministic postcondition.
4. Mission UI can add that pipeline and run it from the mission list.
5. The maintenance AI can read the resulting rows and explain/propose a fix
   for one failed run.
