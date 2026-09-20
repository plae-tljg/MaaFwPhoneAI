# Maa-phone Android fork

This is the **MaaFwApp fork** that hosts the on-device brain and Assistant.
Upstream MaaFwApp usage/build docs remain valid (see `README.md` and
`INTEGRATION.md`); this document records *our* delta, build state, manual
tests and known issues.

- Reference checkout: `../../00ref/MaaFwApp` (pin `70cd377`)
- Fork source: this directory (AGPL-3.0)
- Package: `com.aliothmoon.maafw.maaphone`, label **Maa-phone**
- Latest source APK: `dist/maa-phone-v1-debug.apk` (sha256
  `b565cf5f7ca99e159d7fc242abd35732cee73f7a2bfe56ce3df690c92eeae9e8`);
  install over existing v1; a pre-v1 (`skills`) install must be removed
  first because the fresh v1 schema is not migrated (by design).
- Overall project status: [`../docs/STATUS.md`](../docs/STATUS.md)
- Goal/method: [`../docs/GOAL.md`](../docs/GOAL.md),
  [`../docs/WORKING_STYLE.md`](../docs/WORKING_STYLE.md),
  [`../docs/MAA_STACK.md`](../docs/MAA_STACK.md)
- Debugging: [`../docs/DEBUGGING.md`](../docs/DEBUGGING.md)

## 1. What works now

| Milestone | State | Evidence |
|---|---|---|
| **M0** — fork shell + MaaFramework native runtime | ✅ built and proven | demo PI reports `COMPLETED`; native `.so` strip corruption fixed |
| **M1** — in-app brain + deterministic pipeline | ✅ proven for `open settings` | DB → resolver → compiler → `RunPlanPayload` → privileged runner; no AIDL change |
| **M3/M3.2** — Assistant UI + bootstrap AI + pipeline import | ⚠️ built and installed; run #2 actually liked the video but the LLM verifier false-negatived it | Compose Assistant, live preview, Review, Data, Logs, Settings; native importer; strict final verify is nondeterministic (3 true / 2 false on the same screenshot) |
| **M2** — tile / notification / float ball / queue / pause | ⏳ not implemented on Android | prototype has the semantics; Android currently submits all steps in one plan |
| **M4** — on-device promotion/flywheel | ⏳ close | proposal #12 (AI-authored native clock graph) Test-replayed in run #42 with `path='pipeline'`, `verified=1` and a real OCR postcondition; approval and the imported MaaMCP workflow are still open |

The immediate next move is still to author/import a real workflow with
**MaaMCP + Everything-Maa**, review it and replay it with a deterministic
postcondition; the verifier/OCR blockers are now fixed. See
[`../docs/AUTHORING.md`](../docs/AUTHORING.md) and
[`../docs/STATUS.md`](../docs/STATUS.md) §2i/§5.

## 2. APK artifacts

All under `dist/`:

| APK | Milestone / feature | SHA-256 |
|---|---|---|
| `maa-phone-m0-debug.apk` | fork + minimal PI; native runtime fix | `c3c15c715f43895d5baf478032812e4578e170ca72f58cf8d4245836e5e444c3` |
| `maa-phone-m1-debug.apk` | brain + Assistant; deterministic `open settings` | `5c31e35b5d8f0474230f98602f42a56530cc0af46dff17ad2c5ffa34fdd77507` |
| `maa-phone-m3-debug.apk` | first AI fallback + learning scaffold | `ab5197b28fcc41ec7af37a6f97d3c7c3d06ba380d3459d5117dac268c78b6a59` |
| `maa-phone-m3-ui-debug.apk` | Compose UI, live preview, strict verify | `c1e7f3b079ed3eb9c9c441161644738c591e66503f8a28ae8105f777afe2748c` |
| `maa-phone-m3-pipeline-debug.apk` | native pipeline importer + compiler pass-through | `5f3ca9e65dd3983d3b75873ae132c8ec5c608ac7902fbed238bc49ea54c38eb6` |
| **`maa-phone-m3.2-debug.apk`** | previous: Logs/Data cards/Settings, model retry (old `skills` schema) | `001a296465d667b861a2247f7ab3311b7cb1caf1a322d24fefd87c811de56623` |
| **`maa-phone-v1-debug.apk`** | **latest source**: 300-step bootstrap budget + early-exit guard; fresh v1 schema (`pipelines`, `pipeline_versions`, `missions`) | `b565cf5f7ca99e159d7fc242abd35732cee73f7a2bfe56ce3df690c92eeae9e8` |

On 2026-09-19 the installed `base.apk` was pulled and its sha256 matched the
m3.2 artifact exactly, so the earlier ADB findings were definitely about that
build. The source now uses the fresh v1 schema (`pipelines`,
`pipeline_versions`, `missions`, `pipeline_id`); install
`dist/maa-phone-v1-debug.apk` after uninstalling the old app.

`dist/SHA256SUMS` currently records the latest APK; the table above is the
full artifact history. Debug APKs embed a local DeepSeek key when
`android/.env` or `proto/.env` exists — **do not distribute debug APKs**;
release builds should use a user-provided or separately injected key.

## 3. Build

Host-specific setup on this machine (see `../docs/STATUS.md` and the build
log in this directory's history): SDK overlay at
`../../android-sdk`, NDK 27.1, compileSdk 37, MaaFramework 5.13.1
arm64-v8a.

```bash
cd android

# fetch/deploy the pinned MaaFramework native release if needed
python3 scripts/setup_maa_framework.py --abi arm64-v8a

ANDROID_USER_HOME=/home/lkm/Music/test_maa/.android-home \
ANDROID_SDK_ROOT=/home/lkm/Music/test_maa/android-sdk \
ANDROID_HOME=/home/lkm/Music/test_maa/android-sdk \
GRADLE_USER_HOME=/home/lkm/Music/test_maa/.gradle-home \
./gradlew :app:assembleDebug

cp app/build/outputs/apk/debug/app-debug.apk dist/<name>.apk
sha256sum dist/<name>.apk > dist/SHA256SUMS
adb push dist/<name>.apk /sdcard/Download/
```

For the DeepSeek key at build time, precedence is:

1. environment `DEEPSEEK_API_KEY`;
2. Gradle `-Pdeepseek.apiKey=...`;
3. `android/.env`;
4. `proto/.env`.

The Assistant's Settings tab (`deepseek_api_key`, `deepseek_base`,
`deepseek_model`) overrides embedded values at runtime.

## 4. What the fork adds

`app/src/main/java/com/aliothmoon/maafw/brain/`:

| File | Role |
|---|---|
| `BrainDb.kt` | SQLiteOpenHelper over `assets/brain/schema.sql`; seeds apps/hints/pipelines; `runs`/`messages`/`proposals` helpers |
| `BrainModels.kt` | row models and alias parsing |
| `Resolver.kt` | deterministic alias scoring (exact/contains/similarity, threshold 65) |
| `Compiler.kt` | DB steps/elements → native MaaFW node JSON; native fragments pass through 1:1 |
| `BrainRunner.kt` | builds a `RunPlan` of one `RuntimeTask` per compiled step and drives the existing `RunnerPort` |
| `AgentRunner.kt` | temporary bootstrap loop: screenshot → DeepSeek → one native action → repeat; strict final verification |
| `AgentTools.kt` | one-node native run plans (`Screencap`, `Click`, `StartApp`, `InputText`, `ClickKey`, `Swipe`, `wait`) |
| `DeepSeekClient.kt` | OpenAI-compatible chat client, native `phone_action` tool calling, `locate`/`verify` helpers |
| `ImageTools.kt` | 720-short-side JPEG data URL and raw/image coordinate scaling |
| `Importer.kt` | standard MaaFW pipeline JSON → pipeline candidate/proposal; copies templates to a versioned bundle |
| `Learner.kt` | trajectory → proposal; approve/reject publishing |
| `BrainResources.kt` | versioned resource dir (`files/pi/brain/res_N/image/`) and paths |
| `SkillLoader.kt` | reads `assets/maa-skills/index.json`, selects one family, loads only that family's `SKILL.md`, logs the loaded family/skills and passes guidance to the model |
| `AssistantActivity.kt` | Compose UI: Run/Assistant, Runs, Review, Data, Logs, Settings; live virtual-display preview + latest AI screenshot |

Native/Java input bridge fix (this build):

- `app/src/main/native/bridge_input.cpp` now handles `INPUT` and calls
  `DriverClass.inputText(String, displayId)`.
- `InputControlUtils.inputText()` uses `KeyCharacterMap` events for ASCII
  and clipboard + `KEYCODE_PASTE` for non-ASCII; it clears the focused field
  first so typing replaces the old query.

Upstream MaaFwApp internals are reused, not modified, except for build
packaging fixes and the added brain.

## 5. Manual test and current on-device behaviour

### 5.1 Install / connect

1. Install the latest APK from `/sdcard/Download/` or
   `adb install -r dist/maa-phone-m3.2-debug.apk` (MIUI blocks adb installs
   unless **Install via USB** is enabled; otherwise install from Files).
2. Open **Maa-phone** once and grant notification/battery/overlay prompts.
3. Start **Shizuku** and authorize Maa-phone; choose Shizuku/root until the
   runner connects.
4. Two launcher icons exist:
   - **Maa-phone** — stock PI library (demo tasks `Demo: open Settings`,
     `Demo: open Camera`);
   - **Assistant** — the brain (goal input, runs, review, import).
   Most test confusion comes from running the brain flow from the wrong
   icon.

### 5.2 Deterministic M1 path (should work)

1. Open **Assistant**.
2. Press **Run demo: open settings** (or type `open settings`).
3. Expected: `Skill 'open_settings' (score 100) completed: 1 step(s)` and a
   green row in Runs. In foreground mode Settings opens on the main screen;
   in background mode it opens on the 1280×720 virtual display (the main
   screen does not change by design).

### 5.3 Bootstrap AI path (status after v1 input fix)

1. Open **Assistant → Settings**, confirm key/base/model, press **Test**.
2. Run:
   `open bilibili, search for 114514, open the first video and tap the like button`.
3. Current observed outcomes on the latest v1 APK
   (`fd9af2f8...`):
   - Numeric input: run #3 logged `text -> typed 350234`.
   - Chinese input: run #4 logged `text -> typed 阿米诺斯` and completed.
   - Toggle state: run #5 showed the video already liked before the tap;
     the model tapped it and toggled it off. Run #7 fixed this with a
     pre-tap crop check: first tap liked it, the focused check saw it was
     already active and skipped the second tap; final verification passed.
   - Search/first-time verification: the LLM verifier now waits, captures
     up to three fresh final screenshots and retries.
   - Skill loading is logged at the start of every bootstrap run:
     `skills loaded: family=pipeline-authoring -> [...] from
     maa-skills/index.json`.
   - UI keyboard: the Assistant content now uses `imePadding()` and a
     scrollable column so the goal field is not blocked by the IME.
   - Virtual-display HOME: `key home` on a non-primary display now launches
     the launcher on that display instead of sending a global HOME that
     changes the main screen. Run #10 proved the secondary launcher still
     renders black, so the prompt now prefers `launch <package>`; run #12
     (`open the finance manager app`) verified that path.
   - App catalog: Android 11+ `<queries><intent MAIN/LAUNCHER>` added so
     `queryIntentActivities` sees all launchable apps (21 -> 70), including
     `com.anonymous.financemanager`.
   - UI controls: the Run tab has **Stop**, **Replay + improve** and
     **Import pipeline**; Review has **Test replay** before Approve. Replay +
     improve replays the live pipeline and, on failure, asks the AI to adapt
     to the changed UI and file a `pipeline_fix` candidate. Max AI steps is in
     Settings (`agent_max_steps`, default 300, range 5–1000); the early-exit
     guard (`agent_repeat_limit`, default 5) stops repeated/empty actions.
   - A verified AI run already creates a candidate `pipeline_versions` row
     plus a pending `pipeline_new` proposal; the import button is for
     externally authored MaaFW pipeline JSON.
   - The runner compiles the whole definition to one MaaFW graph task
     (`next`/`on_error` preserved for native fragments). Replay and Review
     Test replay evaluate `pixel`, `element`, `screen_text`, bare MaaFW
     recognition maps and `file_count`; run #42 verified proposal #12 with a
     real OCR hit. Unsupported types are still reported as skipped, not
     false-passed.
4. Runs #4 and #7 each created a candidate `pipeline_versions` row and a
   pending proposal; review/approve them in the Review tab.
5. Correct next test is the **native pipeline import** path:
   push `pipeline.json` + templates to
   `/sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/brain/imports/`,
   press **Import MaaMCP pipeline.json**, review, approve, replay.
6. First-time AI remains model-verified by design (there is no pipeline yet);
   candidate Test replay now uses a deterministic postcondition when present
   (`pixel`/`element`/`screen_text`/recognition/`file_count`); prefer a
   deterministic postcondition before approving a version.

### 5.4 Live preview / background mode

The Assistant reuses MaaFwApp's `PreviewPort` + `MaaPreviewSurface`, so the
virtual display is visible inside the app. Foreground mode exposes the task
on the main screen. Coordinates learned in one display mode are not
portable across modes.

## 6. Debugging quick start

Full playbook: [`../docs/DEBUGGING.md`](../docs/DEBUGGING.md).

```bash
# installed version / update time
adb shell 'dumpsys package com.aliothmoon.maafw.maaphone | grep -E "versionName|lastUpdateTime|firstInstallTime"'

# brain DB — the source of truth
adb shell run-as com.aliothmoon.maafw.maaphone cp databases/brain.db files/db_copy.db
adb exec-out run-as com.aliothmoon.maafw.maaphone cat files/db_copy.db > /tmp/brain.db
sqlite3 -header -column /tmp/brain.db "SELECT id,path,state,success,verified,error FROM runs ORDER BY id;"
sqlite3 -header -column /tmp/brain.db "SELECT id,run_id,role,kind,substr(content,1,120) FROM messages ORDER BY id DESC LIMIT 20;"

# privileged runner
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/maafw.log | tail -100
adb shell 'ls -lt /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/run/ | head'
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/app.log | tail -50
adb shell 'ls -lt /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/debug/'

# model / UI
adb logcat -d -v time | grep -iE 'Brain|Assistant|DeepSeek|MaaFw|AndroidRuntime' | tail -120
adb shell 'ls -lt /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/brain/shots/ | head'
```

Known first checks:

- Wrong icon? (Maa-phone vs Assistant).
- `NOT_RUN`/`MAA_LOAD_FAIL`? Check native library hashes/strip fix.
- Settings invisible? Probably background mode; use foreground or preview.
- `Skill … completed with failures`? Check `maafw.log` for
  `task not found [node_name=action]` / override shape.
- `model returned no action`? Check key/base/model and the retry path.
- "success" but no real effect? Run the postcondition/vision check; never
  promote an unverified run.

## 7. Known limitations / next slices

- **No Android step-wise pause/queue**: `BrainRunner` sends all compiled
  steps in one plan; M2 must submit one step per `startRun`, persist
  `runs.progress_json`, and add the queue/Do-now/Cancel surfaces.
- **Postcondition coverage is still thin**: `pixel`/`element`/`screen_text`/
  recognition are wired and verified for proposal #12, and `file_count` is
  implemented, but imported pipelines often ship `postcondition={}` and the
  `file_count` path has not been exercised on a camera scenario yet.
- **Bootstrap AI is still point-driven**: it now receives real MaaFW
  OCR/TemplateMatch/ColorMatch observations and the OCR models ship with the
  PI, but its planner output is still coordinate trajectories; those are not
  the final learning artifact. It also only guards repeated `locate` taps,
  not model-supplied `tap` points, and run #2 used a pre-existing
  search-history/suggestion chip instead of typing `114514`.
- **Model verification is nondeterministic**: on the same final screenshot
  it returned 3 true / 2 false. Do not use it as the only promotion gate;
  that is the immediate correctness bug (ADR-025).
- **No token accounting**: Android `runs.ai_cost` is currently 0; cost
  evidence comes from the PC prototype.
- **Native import path not yet exercised end-to-end**: importer/pass-through
  compile, but no real MaaMCP pipeline has been imported and replayed
  verified yet.
- **Debug key embedded**: not distributable.
- **Dynamic-screen replay and auto-healing are v0.1** (prototype has the
  schema/counters, not the full loop).

The immediate planned step is documented in
[`../docs/STATUS.md`](../docs/STATUS.md) §5: run the MaaMCP +
Everything-Maa authoring pass for Bilibili, import `pipeline.json`, approve,
and prove a verified 0-token replay.
