# Current status

Snapshot: **2026-09-19 23:45 HKT, after the M3.2 APK build and ADB re-investigation.**
Some earlier documents say 2026-09-20 because the machine clock jumped during
the original OpenCode session; where they conflict, use this page plus the
actual logs/hashes.

> **One-line status:** the PC prototype proves the full method
> (AI-once → verified pipeline → 0-token replay). On Android, M0/M1 and the
> v1 schema/Assistant exist; deterministic `open settings` works. The
> on-device bootstrap logs loaded skill families, fixes numeric/Chinese
> input and observes screens; it adapts existing pipelines after UI drift.
> Deterministic recognition postconditions and graph-native Test replay are
> now proven on-device (proposal #12, run #42, OCR hit `闹钟` 0.998). The
> remaining big items are an imported MaaMCP/M9A workflow, version/mission
> review surfaces, agent-IO `needs_input`, replay-K and automatic healing;
> see §2b–§2i and §5.

## 1. Build artifacts

| Artifact | What it is | SHA-256 |
|---|---|---|
| `android/dist/maa-phone-m0-debug.apk` | MaaFwApp fork + minimal PI; shell/native runtime fixed | `c3c15c715f43895d5baf478032812e4578e170ca72f58cf8d4245836e5e444c3` |
| `android/dist/maa-phone-m1-debug.apk` | in-app brain + Assistant; deterministic pipeline path | `5c31e35b5d8f0474230f98602f42a56530cc0af46dff17ad2c5ffa34fdd77507` |
| `android/dist/maa-phone-m3-debug.apk` | first AI fallback + learning scaffold | `ab5197b28fcc41ec7af37a6f97d3c7c3d06ba380d3459d5117dac268c78b6a59` |
| `android/dist/maa-phone-m3-ui-debug.apk` | Compose Assistant, live preview, strict verify | `c1e7f3b079ed3eb9c9c441161644738c591e66503f8a28ae8105f777afe2748c` |
| `android/dist/maa-phone-m3-pipeline-debug.apk` | native pipeline importer + pass-through compiler | `5f3ca9e65dd3983d3b75873ae132c8ec5c608ac7902fbed238bc49ea54c38eb6` |
| **`android/dist/maa-phone-m3.2-debug.apk`** | previous installed build (old `skills` schema) | `001a296465d667b861a2247f7ab3311b7cb1caf1a322d24fefd87c811de56623` |
| **`android/dist/maa-phone-v1-debug.apk`** | **v1 + 300-step/early-exit guard + Replay/improve + Review Test replay**; fresh v1 schema (`pipelines`, `pipeline_versions`, `missions`) + `pipeline` vocabulary; install over v1 or fresh | `b565cf5f7ca99e159d7fc242abd35732cee73f7a2bfe56ce3df690c92eeae9e8` |

Current test device: Redmi 23076RN8DY, Android 15 (API 35), arm64-v8a.
The updated `maa-phone-v1-debug.apk` (`b565cf5f…e9e8`) has been installed
over the existing v1 DB on 2026-09-20 14:05 (`adb install -r`; no schema
change). A pre-v1 install (`skills` schema) still requires uninstall because
there is intentionally no DB migration.

## 2. What is proven

### PC prototype (`proto/`, MaaFW-native)

- `34` unit tests pass (including pipeline-version and mission tests).
- **Camera:** AI run 5,107 tokens → verified postcondition (`file_count`,
  +1 photo) → two replays 0 tokens, each verified (`49→50→51`).
- **Bilibili 114514 → first video → like:** AI run 33,558 tokens → verified
  by pixel colour (`distance 3`) → deterministic replay 0 tokens / 30.6 s,
  6/6 native MaaFW nodes matched, pixel-verified.
- Negative evidence kept: run #5 false like, run #7 toggled an already-liked
  video, run #10 dynamic second-launch replay failure.
- The prototype implements the wider data contract: native MaaFW field
  plumbing, MaaMCP pipeline import/export, `{app}` patterns, enforced
  autonomy, policies, message-stream `needs_input`/`answer`, decision cache,
  replay-K (`eval_runs`/`eval_items`).

### Android fork (`android/`)

- **M0:** fork builds with MaaFramework 5.13.1 and runs the demo task; the
  native-library strip corruption is fixed
  (`packaging.jniLibs.keepDebugSymbols`); background/foreground display
  behaviour is understood.
- **M1:** `brain/` (SQLite over the shared schema, resolver, compiler,
  `RunPlan` through `RunnerPort`) and the Assistant launcher exist;
  `open settings` runs through the DB → compiler → privileged runner path
  with no AIDL change. Expected result: `Skill 'open_settings' (score 100)
  completed: 1 step(s).`
- **M3/M3.2:** Assistant Compose UI with live virtual-display preview,
  Runs/Review/Data/Settings/Logs tabs; one-node native actions (`Screencap`,
  `Click`, `StartApp`, `InputText`, `ClickKey`, `Swipe`); DeepSeek vision
  bootstrap; strict final vision verification; pipeline version candidate on
  success; API key/base/model settings; build-time `.env` injection for the
  debug APK; native pipeline import (`Importer.kt`) + compiler pass-through;
  template files copied into a versioned resource bundle.

## 2b. Latest ADB investigation (2026-09-19 23:45 HKT)

The installed APK was confirmed to be exactly the latest build: the on-device
`base.apk` sha256 matches
`dist/maa-phone-m3.2-debug.apk`
(`001a296465d667b861a2247f7ab3311b7cb1caf1a322d24fefd87c811de56623`).

The DB still contains only runs #1 and #2; no new run was written after the
last test. Their root causes are now separated:

| Run | What happened | Real cause |
|---|---|---|
| #1 (`阿米诺斯`) | Launched Bilibili, then repeatedly tapped around `y≈27` until `max steps reached`; never performed a search/type action. | The bootstrap is coordinate-only; the repeat-tap guard currently tracks only `locate` results, so model-supplied `tap` points are not guarded. |
| #2 (`114514`) | Navigated through the search page to the video, tapped `(355,575)`, then `done`. DB recorded `vision verify failed … like button gray`. | **The tap actually worked.** Pixel ground truth in the final `run2_final.png`: the like bbox `(328,570,62,52)` changed from gray `#61666D` before the tap (`run2_turn4.png`, 222 px) to Bilibili pink `#FF6699` + `#FFB2CC` after (`run2_turn5/6/final.png`, 235 px). The action succeeded; the final model verifier returned a false negative and blocked the proposal. |

The verifier itself is nondeterministic on the *same* final screenshot. Using
the exact `DeepSeekClient.verify` prompt five times gave **3 true / 2 false**
with contradictory evidence text, while pixels are unambiguous. Run #2 also
never called `text`: `114514` appeared because the model tapped a pre-existing
search-history/suggestion chip. That path is not reproducible on a clean
device.

**Conclusion:** M3.2's test failure is not a MaaFW/action failure. It is a
trust/verification bug plus a fragile coordinate bootstrap. Deterministic
local postconditions must decide toggle success; a single LLM verifier
verdict must not be the promotion gate (ADR-025).

### 2c. Reference review: what M9A says about the real gap

We checked out **MAA1999/M9A** as a structural sample (`00ref/M9A`,
`4fa65aa`) and wrote the lessons into [`M9A_REFERENCE.md`](M9A_REFERENCE.md).
The gap is larger than the verifier:

- A mature Maa project expresses behavior as **recognition-gated pipeline
  graphs**: every node has OCR/TemplateMatch/ColorMatch/ROI recognition,
  an action, explicit `next`/recovery, and `pre/post_wait_freezes` instead
  of blind delays. Bilibili-specific knowledge lives in data (pipeline JSON,
  ROI, templates, package/overrides), not in Kotlin.
- Our bootstrap does the opposite: full screenshot → LLM → raw coordinate,
  with no recognized state, no `next` graph, no deterministic postcondition.
  It is useful only for first contact with an unknown app.
- Therefore the problem is **both the code path and the missing pipeline
  artifact**:
  - code: `AgentRunner`/`Learner`/`verify` treat the bootstrap as the
    workflow engine and promotion source;
  - data: no native reusable pipeline exists yet for the test tasks.
- The fix is not "make the verifier pass" or hardcode Bilibili. It is:
  add generic primitives (deterministic verifier, recognition observation,
  proper pipeline import/execution) and author the task as data (pipeline
  JSON) using MaaMCP + Everything-Maa/M9A patterns. See
  [`AUTHORING.md`](AUTHORING.md) §§6–7 and `M9A_REFERENCE.md` §3–6.

## 2e. Input fix and successful run #4 (2026-09-20 09:33)

The previous failure at "enter numbers into search box" was a real app bug,
not (only) a model-planning issue:

- MaaFwApp's native bridge `DispatchInputMessage` had cases for touch and
  key events but **no `INPUT` case**. `AndroidNativeControlUnit.input_text()`
  therefore called `dispatch_input_message()`, got `0` (success) and
  `InputText` was a silent no-op for every character.
- Fix implemented:
  - `bridge_input.cpp` now handles `INPUT`, calls
    `DriverClass.inputText(String, displayId)` through JNI;
  - `InputControlUtils.inputText()` uses `KeyCharacterMap.getEvents()` for
    ASCII digits/letters, and falls back to clipboard + `KEYCODE_PASTE` for
    non-ASCII text such as Chinese;
  - it clears/selects the focused field first so typing replaces the old
    query rather than appending.
- APK `maa-phone-v1-debug.apk`
  `75ec928d…a476` contains the fix.

Live test after the fix:

- Run #3 (`350234`) logged `AI step: text -> typed 350234`; the numeric
  input stage succeeded. It then failed later at the like stage with the
  repeat guard (`repeat loop detected: tapped (343,690) repeatedly`).
- Run #4 (`阿米诺斯`) succeeded end-to-end for the search goal:
  `key back -> tap search -> text 阿米诺斯 -> enter`, final model
  verification passed, and the app created:
  - `pipeline_versions` row #1 (`status='candidate'`) for pipeline #2,
  - `pipeline_version_runs` row linking run #4,
  - pending `proposals` row #1 (`kind='pipeline_new'`,
    `target_version_id=1`).
- Run #4 also logged the skill family it loaded:
  `skills loaded: family=pipeline-authoring -> [maa-pipeline-guide,
  maa-pipeline-generate, maa-pipeline-option] from
  maa-skills/index.json` before the first model action.

The remaining `like` failure is a different problem: the model repeatedly
locates/taps the like button without a deterministic state check. The
on-screen verifier is still nondeterministic (ADR-025), so the next work is
a deterministic toggle postcondition and version Review.

## 2f. Toggle state, wait, and first-time verification (2026-09-20 10:01)

Run #5 showed the toggle-start-state problem: the video was **already liked**
before the run (the like pixel was pink `#FF6699` in `run5_turn6.png`), but
the model tapped it anyway, toggling it back to gray. This is not a wait
problem; it is a state/idempotency problem. The first-time AI does not know
the starting state reliably.

Fixes in `AgentRunner`/`DeepSeekClient`:

- **Pre-tap toggle state check:** for a `locate` action whose description is
  a toggle (like/follow/点赞/关注/收藏), the app crops a small region around
  the located point and asks the vision model only "is this control
  active/on/filled?". If active, it does **not** tap.
- **Never toggle twice:** if the model asks to tap the same toggle target
  again, the runner finishes and verifies instead of tapping a second time.
- **First-time verification is model-based, but retried:** there is no
  consolidated pipeline yet, so an LLM verifier is the only available first
  check. The runner now waits, captures up to three fresh final screenshots,
  and retries the verifier; state changes/animation can lag.
- **Search-loop recovery:** if the model repeatedly taps a field without
  typing in a search goal, the runner can type the query extracted from the
  goal once (generic search recovery, not a Bilibili branch).

Live result after these fixes:

- Run #7 (`search 350234 -> first video -> like`) finished
  `done / verified=1`: it tapped the like once, the focused pre-check saw it
  was already active, skipped the second tap, and final verification passed.
  It created pipeline version candidate #2 and pending proposal #2.

First-time AI remains model-judged by design; the consolidated pipeline must
carry a deterministic postcondition when it is approved.

## 2g. UI and display-scope issues (2026-09-20 10:15)

Two device-testing issues were reported and recorded in [`TODO.md`](TODO.md):

1. **Goal field blocked by the keyboard.** The Assistant content now uses
   `Modifier.imePadding()` and a vertically scrollable column, so the goal
   field stays reachable while the IME is open (source fixed; targeted APK
   built).
2. **`key home` changed the main screen while the target app was on the
   virtual display.** `InputControlUtils` special-cases `KEYCODE_HOME` for
   `displayId != 0`: it starts the launcher on that display instead of
   sending a global HOME event that the system routes to the primary
   display. However, run #10 showed all screenshots black (`1280x720`,
   mean `(0,0,0)`): the launcher does not render usefully on the secondary
   virtual display, so HOME is not a valid normalization step in background
   mode. The v1 build now counts HOME taps, fails after 3 with a clear
   error, and the prompt tells the model to use `launch <package>` instead.
   Following MaaFwApp/MAA-Meow, the Android brain now queries
   `PackageManager` for launchable apps and syncs them into the `apps`
   registry; the finance app can be opened by label without the drawer.
   HOME/drawer navigation remains an optional foreground-only test.
3. **Finance app was missing from the app catalog (root cause, fixed).**
   Android 11+ package-visibility filtering meant `queryIntentActivities`
   initially returned only 21 launcher apps, so
   `com.anonymous.financemanager` was invisible and the AI fell back to
   HOME. Adding a `<queries><intent MAIN/LAUNCHER>` declaration made the
   catalog see 70 launchable apps. Run #12 (`open the finance manager app`)
   then completed with `verified=1`:
   `AI step: launch -> launched com.anonymous.financemanager`, creating
   candidate version #3 + pending proposal #3.

## 2d. Design vs implementation — the honest gap

The user's original design (correctly) was: **DB rows hold MaaFW pipeline
graphs, i.e. per-workflow state machines; the AI can freely propose changes
to those rows; MaaCP/MaaFramework provides the runtime; Everything-Maa
provides the authoring skills (when to use ROI, OCR/template/color, how to
crop and store reference images)**. That design is recorded in
`WORKING_STYLE.md`, `DESIGN.md`, `INTEGRATION.md` and `AUTHORING.md`, but
the implementation does not yet realise all of it:

| Original design | Documented? | Implemented now? |
|---|---|---|
| A workflow is a native MaaFW pipeline graph (`next`, `on_error`, anchors, `roi`, `order_by`/`index`) stored as a DB row | yes | **partial.** `Compiler.kt` now emits one graph (entry + all nodes) and `BrainRunner.kt` submits it as a single MaaFW task, so `next`/`on_error` execute; legacy bootstrap step arrays are chained with `next`. What is still missing is authoring conditional graph nodes (e.g. ensure-toggle-if-inactive) and a tested native imported graph. |
| AI can freely propose/modify rows | yes | **partial.** Schema/proposals exist. The prototype has a publisher registry (`pipeline_new`, `pipeline_fix`, `element_fix`, `alias`, `hint`, `policy`); Android `Learner.approve` currently publishes a live pipeline directly, without a separate `pipeline_versions` candidate row yet. No generic row/graph validation gate yet. |
| ROI/OCR/TemplateMatch/ColorMatch and cropped reference images are data, authored via the Maa skills | yes | **partial.** The 8 Everything-Maa skills are vendored; `Importer.copyTemplates` copies referenced template files into `files/pi/brain/res_N/image/`; `BrainResources.kt` exists. But no actual MaaMCP authoring pass has run, so no real templates/ROI/graph have been produced/tested. |
| LLM fallback only bootstraps; it does not become the production workflow | yes (ADR-022/026) | **not enforced by code.** `AgentRunner`/`Learner` still turn point trajectories into pipeline proposals rather than authored graph versions. |
| Deterministic success conditions | yes (ADR-025) | **partial.** Android wires `pixel` postconditions and checks them on pipeline replay and Review Test replay; `element` / `screen_text` / `file_count` still need MaaFW recognition details or scoped storage. |

**So: not hardcoding Bilibili.** The missing code primitives are
(1) a graph-shaped pipeline row and (2) a runner that executes the graph;
the missing data artifact is the authored Bilibili pipeline (ROI/templates/
overrides) produced with MaaMCP + Everything-Maa. The LLM loop is a
bootstrap, not the missing pipeline.

## 2h. UI/UX fixes after run #12

- **Stop button** on the Run tab: requests cancellation, stops the
  privileged runner, and marks the active run `cancelled`.
- **Import pipeline** now opens a system file picker and imports the
  selected MaaFW `pipeline.json`; the old fixed-path check produced the
  "no pipeline files" message. A verified AI run already creates a
  candidate `pipeline_versions` row + `pipeline_new` proposal
  automatically — import is for external authoring output.
- **Max AI steps** is editable in Settings (`agent_max_steps`, default 300,
  clamped 5–1000) so long multi-stage goals are not cut off at 30.
- **Early-exit guard** (`agent_repeat_limit`, default 5, clamped 2–10) stops
  a bootstrap run after that many consecutive empty/failed results or
  identical repeated actions, instead of burning the whole 300-step budget.
  This is a safety valve, not a replacement for recognition-based pipelines.
- **Replay + improve** on the Run tab: replays the live pipeline; on replay
  failure it forces a clean app launch, gives the AI the old pipeline
  definition + failure evidence, and files a `pipeline_fix` candidate under
  the same pipeline. Live evidence: run #22 replay failed on Bilibili UI
  drift, run #23 adapted (7 steps, avoided double-toggle), verified and filed
  proposal #5; run #25/#26 repeated the loop with a launch reset and filed
  proposal #6.
- **Review Test replay**: pending proposals have a Test replay button. It
  compiles the candidate definition + candidate templates and records a
  `pipeline_version_runs` row with `role='replay'`; node completion is
  evidence only (`verified=0`) until deterministic postconditions are wired.
  Run #19 tested proposal #3 (pass); run #24 tested proposal #5 (node
  failures), proving the gate catches broken candidates before approval.
- **Recognition-detail bridge:** the privileged `RemoteService` now exposes
  `recognitionDirect(recoType, recoParamJson)`. `MaaRunner` runs
  `MaaTaskerPostRecognition`, resolves the reco id through task/node detail,
  and returns `{hit, box, algorithm, detail}`. The app's `AgentTools` uses it
  for a native OCR observation each frame and exposes a `recognize` action
  (`OCR` / `TemplateMatch` / `ColorMatch`) to the AI planner. Device evidence:
  run #35 logged `native OCR recognition: 185 chars` and solved the finance
  app goal.
- **Native DB contract:** `pipelines.definition_json` / `pipeline_versions.definition_json`
  are now MaaFW-native graph maps (`{Node: {recognition/action/next/...}}`) plus
  an explicit `entry`; the compiler no longer interprets app-specific step
  arrays or normalizes nested type/param shapes. `BrainDb` version 2 performs a
  one-time migration of old linear-step rows and pending proposals into native
  graphs, and invalid AI-authored graphs are rejected at `propose_pipeline`
  and at approval.
- **Kotlin MaaMCP tool port:** `recognize` (direct OCR/TemplateMatch/ColorMatch +
  detail), `benchmark` (N-run hit count / latency on a recognition), and
  `propose_pipeline` (AI-authored native graph map) are exposed to the model.
  `Learner.proposeNativeGraph` stores them as pending Review proposals;
  `Compiler` and `BrainRunner.testProposal` support graph-map definitions, so
  Test replay can compile and run them. This replaces the need for Termux or
  an embedded Python MaaMCP runtime in the app.
- **Graph-native execution:** `Compiler.kt` now emits one entry + all nodes and
  `BrainRunner.kt` submits that graph as a single MaaFW task, so `next` /
  `on_error` are executed by MaaFW instead of flattened into isolated tasks.
  Legacy bootstrap step arrays are converted to a linear `next` chain.
- **Deterministic postconditions (Android):** `Verifier.kt` now supports
  `pixel`, `element`, `screen_text`, `file_count` and MaaFW-native bare
  recognition maps. Replay and Review Test replay capture a fresh frame,
  run the recognition through `RemoteService.recognitionDirect`, and record
  the evidence; unsupported/missing postconditions are still reported as
  skipped rather than false-passed. Toggles continue to generate a pixel
  postcondition around the changed control.
- **Chat-like IO direction** recorded in `docs/TODO.md` P1-8: a
  ChatGPT/Codex/opencode-style modal with option buttons and free text,
  connected to `runs.state='needs_input'`.

## 2i. Deterministic replay and OCR model fix (2026-09-20 15:50 HKT)

After the v1 packaging turn, ADB verification found two real blockers and
fixed them:

1. **MaaFW had no OCR model on device.** `pi.zip` only shipped
   `resource/pipeline/maa_phone_demo.json`; every OCR call logged
   `OCRResMgr.cpp: Failed to load det or rec: [name=] [det=0x0] [rec=0x0]`.
   The `native OCR recognition: 185 chars` line from run #35/#36 was the
   bridge JSON; the OCR arrays inside were empty. `proto/bundle/model`
   (PP-OCR det/rec/keys) is now synced into `PI/brain_ocr/model/ocr`
   by `PiAssetsConventionPlugin`, and `BrainResources.resourcePaths` loads
   `brain_ocr` before `resource`.
2. **Old pending proposals were not repaired.** Proposal/version #11 still
   carried nested `{action:{type,param}}` / `{recognition:{type,param}}`
   maps from before the flat-native contract, so Test replay returned
   `bad_candidate`/failed. `PipelineGraph.migrateDefinition` now flattens
   those shapes during migration, and `BrainDb` version 3 re-runs that
   migration once for all rows/proposals (runtime compiler stays native-only).

On-device evidence after the fix:

```
BrainDb user_version = 3
proposal #11 graph -> flat {"action":"DoNothing", ...},
                     {"action":"StartApp","package":"com.android.deskclock", ...}
run #41  Test replay proposal #12 -> done, verified=0
         (workflow ran; postcondition still skipped because the bare
          recognition map was parsed after the blank-type early return)
run #42  Test replay proposal #12 -> done, verified=1
         verify_json = {"type":"recognition","node":"VerifyClockHome",
           "recognition":"OCR","params":{"expected":["闹钟","时钟",
           "计时器","Clock","Alarm"],"roi":[0,0,1280,720]},
           "hit":true,"required_hit":true,"ok":true,
           "box":[388,641,70,40],"algorithm":"OCR",
           "detail":"...{\"text\":\"闹钟\",\"score\":0.998008,
           \"box\":[388,641,70,40]}...",
           "candidate_replay":true,"deterministic_verified":true}
```

Review cards now show the candidate goal, entry, node list, postcondition
and linked replay runs (`run:role:success:verified`) so a human can approve
proposal #12 from evidence rather than from the old truncated JSON preview.

Android unit tests are green again (`463 tests, 0 failed, 2 skipped`):
the AIDL `FakePrivilegedService` was missing `recognitionDirect`, Assistant
buttons bypassed the `MaaButton`/`MaaOutlinedButton` UI convention, and a
new `PipelineGraphLegacyMigrationTest` covers the nested-graph repair.

## 3. What is not working / not built

| Gap | Evidence / detail | Next action |
|---|---|---|
| On-device Bilibili bootstrap is not trustworthy | Run #1 looped on direct coordinate taps (guard gap). Run #2 did like the video (pixel-proven) but the LLM verifier false-negatived it 2/5 times on the same screenshot, so no proposal was created. Run #2 also relied on an existing search-history chip instead of typing `114514`. | Add deterministic toggle postconditions (`pixel`/local check) before promotion; extend repeat guard to all tap points; add OCR/typed search. In parallel, author the native workflow with MaaMCP + Everything-Maa. |
| Search goals now work; like/toggle still unstable | Run #4 solved a search goal and created candidate version #1 + pending proposal #1. Run #3 typed the number but looped at the like stage and was stopped by the repeat guard. | Approve/review version #1; add deterministic toggle postcondition so like replay does not depend on the LLM verifier. |
| Android step-wise pause/queue not implemented | `BrainRunner.kt` currently submits all compiled steps in one `RunPlan`; pause/queue semantics exist in the PC prototype (`--pause-after`, `resume`, `process-queue`, `messages`), not in the Android UI. | M2: per-step `startRun`, `runs.progress_json`, queue / Do now / Cancel, notification/float-ball entry points. |
| On-device postconditions wired but not broadly exercised | Android now checks `pixel`/`element`/`screen_text`/bare MaaFW recognition on replay and Test replay; run #42 verified proposal #12 with a real OCR hit. `file_count` is implemented but has no device scenario yet, and imported pipelines still mostly carry `{}`. | Approve/import a real workflow with a deterministic postcondition and add a camera/file-count scenario; keep unsupported types explicitly skipped. |
| Bootstrap is still model-driven | `AgentTools` now gets per-frame MaaFW OCR/TemplateMatch/ColorMatch observations, and the OCR models are packaged, but the planner still emits point trajectories and can drift. The coordinate bootstrap stays discovery-only. | Keep native graph authoring as the product path; add the missing repeat guard for model-supplied `tap` points and keep bootstrap proposals Review-gated. |
| Android token accounting missing | `runs.ai_cost` on Android is `0`; tokens are not parsed from chat responses. | Parse usage from the provider response; write it to `runs.ai_cost`. |
| Version Review mostly done; mission UI/runner not implemented yet | Backend wiring is in and the Android Review tab now shows goal/entry/nodes/postcondition plus linked replay runs. Missions exist in the schema and PC CLI but have no Android UI or runner. | Add mission UI + "run item" path (`runs.path='pipeline'`) and a version diff/history view on top of the existing evidence inspector. |
| Actual MaaMCP authoring pass not run | MaaMCP is cloned/mapped and the importer exists, but no real Bilibili (or other) pipeline has been authored, imported and replayed. | Run the MaaMCP + Everything-Maa pass next. |
| Dynamic-screen replay still unsolved in the prototype | Run #9 replay passed; a later second launch hit a different state (run #10). | Per-step assert/retry/fallback and healing proposals. |
| Auto-healing not implemented | Failed element recognition does not yet generate `element_fix` proposals with new ROI/template/benchmark evidence. | v0.1 after replay-K gate. |
| Debug key in APK is not distributable | `android/.env`/`proto/.env` keys are embedded in the debug build. | Never distribute the debug APK; release build uses user-provided key or separate secret injection. |

## 4. Current on-device DB evidence (v1 APK)

Current fresh v1 DB, after installing `maa-phone-v1-debug.apk` and running
the search tests:

```
runs
  #1  bootstrap 350234+like    failed  max steps reached
  #2  bootstrap 350234+like    failed  max steps reached
  #3  bootstrap 350234+like    failed  repeat loop at like stage
      (text -> typed 350234 succeeded)
  #4  bootstrap 阿米诺斯       done    verified=1; version candidate #1 + proposal #1
  #5  bootstrap 350234+like    failed  final verify: toggled an already-liked video off
  #6  bootstrap 350234+like    failed  repeated search-field taps (auto-recovery/guard work)
  #7  bootstrap 350234+like    done    verified=1; version candidate #2 + proposal #2
  #8  bootstrap YouTube 350234+like failed  max steps reached
  #9  bootstrap finance budget/transaction failed max steps reached (HOME loop)
pipelines
  #1 open_settings                                  live
  #2 "open bilibili, search for 阿米诺斯"           draft
  #3 "open bilibili, search for 350234..."          draft
pipeline_versions
  #1 pipeline=2 v1 candidate source=bootstrap source_run=4
  #2 pipeline=3 v1 candidate source=bootstrap source_run=7
pipeline_version_runs
  version=1 run=4 role=source
  version=2 run=7 role=source
proposals
  #1 pipeline_new pending target_pipeline=2 target_version=1 source_run=4
  #2 pipeline_new pending target_pipeline=3 target_version=2 source_run=7
settings: deepseek_model=deepseek-v4-flash; hint:tv.danmaku.bili seeded
```

Interpretation: numeric and Chinese input, skill-family logging, toggle
pre-checks, first-time verifier retries, and the version/proposal/run
provenance path are all live. Deterministic postconditions and
version/mission UI are the remaining product work.

## 5. Ordered next steps

### P0 — produce the first verified on-device reusable workflow

> **Updated 2026-09-20 15:50:** Android now has deterministic
> `pixel`/`element`/`screen_text`/recognition checks, the OCR model is
> bundled, and Review Test replay verified proposal #12 in run #42.
> No promotion trusted a model verdict alone for that replay.

0. ~~**Replace model-only toggle verification on Android**~~ — **done**:
   deterministic postconditions are wired and recognition-verified; keep the
   LLM verifier only for first-run bootstrap acceptance.
1. Run MaaMCP against the phone with the Everything-Maa workflow-build /
   pipeline-guide / pipeline-generate skills.
2. Author the Bilibili workflow natively in the M9A pattern
   ([`M9A_REFERENCE.md`](M9A_REFERENCE.md)): OCR search entry/search text
   ("114514"), TemplateMatch first-video card, ROI/template (or ColorMatch)
   like icon, recognize→act→recognize, explicit `next`/recovery,
   `post_wait_freezes` instead of blind delays.
3. Benchmark nodes, then export `pipeline.json` + template images.
4. Push to `files/brain/imports/`, import in Assistant, inspect the
   proposal, approve only after a good review.
5. Replay on-device; require a real postcondition (pixel/element). Record
   `path='pipeline'`, `ai_cost=0`, `verified=1`.
6. Exercise one failure (e.g. different start state) and keep the evidence
   as the first healing input.

### P1 — close the Android method loop

7. Wire the four verifiers on Android (Custom/Agent nodes).
8. Implement per-step execution + queue/pause/resume + `messages` surfaces
   (M2), so pause/ask/approve are real on-device.
9. Wire `runs.ai_cost` and display it in Runs.
10. Extend the repeat-tap guard to all action points; add an OCR element
    list to the bootstrap if it remains.

### P2 — maintenance and trust

11. Make replay-K evaluation a promotion gate (Record/Replay/Dbg or
    MaaMCP `benchmark_node`).
12. Implement automatic element healing from failed debug bundles.
13. Add scenario YAML/on-device demo flows per user story (camera,
    Bilibili, dark mode, chat) as executable specs.

## 6. Definition of done for v0

- At least two real workflows (e.g. camera, Bilibili, dark mode) are live
  rows, proven by verified 0-token replays.
- The same goals can be authored/repaired through import/Review, not code.
- Every run has DB + debug evidence; every live pipeline has a postcondition.
- The safety gates (Review, autonomy, policies) are exercised and auditable.
- The README/docs match the implemented vocabulary; no undocumented live
  behavior.

## 7. How to verify this snapshot yourself

```bash
# PC prototype
cd proto && .venv/bin/python -m pytest tests/ -q
.venv/bin/python -m maa_phone runs
.venv/bin/python -m maa_phone pipelines
.venv/bin/python -m maa_phone thread

# Android artifacts
sha256sum ./dist/*.apk
adb shell 'dumpsys package com.aliothmoon.maafw.maaphone | grep -E "versionName|lastUpdateTime"'

# Android runtime evidence
adb shell run-as com.aliothmoon.maafw.maaphone cp databases/brain.db files/db_copy.db
adb exec-out run-as com.aliothmoon.maafw.maaphone cat files/db_copy.db > /tmp/brain.db
sqlite3 /tmp/brain.db "SELECT id,path,state,success,verified,error FROM runs ORDER BY id;"
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/maafw.log | tail -80
```

For the full investigation sequence, use
[`DEBUGGING.md`](DEBUGGING.md).
