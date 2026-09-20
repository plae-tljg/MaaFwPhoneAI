# Debugging maa-phone — the evidence-first playbook

This project has four executable layers (DB/brain, model loop, MaaFwApp
runner, MaaFramework native engine) plus an authoring bench (MaaMCP). Most
bugs are one of:

1. **the row is wrong** — resolver/compiler/DB data;
2. **the model is wrong** — bad action, no action, wrong coordinate;
3. **the pipeline is wrong** — override shape, node names, ROI/target,
   recognition not matching, wrong display profile;
4. **the shell is wrong** — privileged process/native libraries/display
   mode;
5. **the truth is wrong** — the run said success but the real state did not
   change.

The debugging method is the same in every case:

> **Do not trust a layer's own claim. Find the smallest reproducible
> artifact, inspect the raw evidence at the layer below it, fix the root
> cause, and leave a log/row/test behind so the next failure is one query
> away.**

## 0. The first five commands

For Android/on-device failures:

```bash
# 1. What is installed, and when?
adb shell 'dumpsys package com.aliothmoon.maafw.maaphone | grep -E "versionName|lastUpdateTime|firstInstallTime"'

# 2. Pull the brain DB (runs/messages/proposals/settings are the source of truth)
adb shell run-as com.aliothmoon.maafw.maaphone cp databases/brain.db files/db_copy.db
adb exec-out run-as com.aliothmoon.maafw.maaphone cat files/db_copy.db > /tmp/brain.db

# 3. What did the last runs actually say?
python3 - <<'PY'
import sqlite3
con = sqlite3.connect('/tmp/brain.db')
for r in con.execute("SELECT id,path,state,success,verified,error,substr(started_at,1,19) FROM runs ORDER BY id DESC LIMIT 10"):
    print(r)
PY

# 4. What did MaaFramework/privileged process report?
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/maafw.log | tail -80

# 5. What did the app process report?
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/app.log | tail -40
```

For PC-prototype failures:

```bash
cd proto
.venv/bin/python -m pytest tests/ -q
.venv/bin/python -m maa_phone runs
.venv/bin/python -m maa_phone trace <run_id>
.venv/bin/python -m maa_phone export <run_id>       # zip the debug bundle
```

## 1. Evidence hierarchy: what counts as truth?

When claims disagree, trust in this order:

1. **Independent postcondition**: `pixel` colour, `file_count` delta,
   `element`/`screen_text` recognition, or a second vision check that
   quotes the final state.
2. **Raw artifacts**: screenshots at the exact moment, MaaFW draw images,
   `maafw.log`, event JSONL.
3. **DB rows**: `runs` (`state`, `success`, `verified`, `error`,
   `steps_json`, `verify_json`), `messages`, `proposals`.
4. **Model claims**: `done`, a summary, or a vision answer (useful, but not
   ground truth, and demonstrably nondeterministic — see §5.4).

A run where recognizers matched but the postcondition failed is a failed
run. Never promote it. This was the explicit lesson from the camera replay
that "succeeded" but took no photo, and the Bilibili run that claimed a like
that never happened.

## 2. Layer 1 — Database / resolver / compiler

The DB is the source of truth. Start there.

```bash
# Android
sqlite3 /tmp/brain.db '.tables'
sqlite3 -header -column /tmp/brain.db \
  "SELECT id,goal,path,state,success,verified,error,duration_ms FROM runs ORDER BY id DESC LIMIT 20;"
sqlite3 -header -column /tmp/brain.db \
  "SELECT id,run_id,role,kind,substr(content,1,120) FROM messages ORDER BY id DESC LIMIT 30;"
sqlite3 -header -column /tmp/brain.db \
  "SELECT id,kind,status,source_run_id,substr(candidate_json,1,200) FROM proposals ORDER BY id;"
sqlite3 -header -column /tmp/brain.db \
  "SELECT id,name,goal,status,autonomy,aliases,substr(definition_json,1,200) FROM pipelines;"
sqlite3 -header -column /tmp/brain.db \
  "SELECT key,substr(value,1,200) FROM settings;"

# PC prototype
sqlite3 -header -column proto/data/maa.db "SELECT * FROM runs ORDER BY id DESC LIMIT 20;"
```

Check in order:

- Did the resolver actually hit the intended pipeline? (`runs.path`,
  `pipelines.aliases`, `pipelines.status`).
- Did the compiler emit nodes? On Android, look at the seeds and
  `steps_json`; the compiler skips unknown step shapes.
- Is `steps_json` a legacy action array or a native pipeline fragment
  (`{"pipeline": {...}}`)? Both are valid, but the shape determines what to
  check.
- Is `verified` meaningful? An empty `postcondition_json` means the run is
  *unverified*, not *proven*.
- Did a proposal publish the wrong app/element? Check `proposals.status`
  and the source run.

**Schema/migration bugs:** `proto/db.py` uses forward-only migrations and
`schema.sql` is the fresh baseline. A failure that only appears on an
existing DB is usually a missing column/migration path; reproduce on the
old DB, not a fresh one. Android `BrainDb` is a `SQLiteOpenHelper` over the
same `assets/brain/schema.sql`; check the asset version if the schema
changed.

## 3. Layer 2 — MaaFwApp runner / privileged process

### 3.1 Run logs and outcomes

```bash
adb shell 'ls -lt /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/run/ | head'
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/run/<file>.jsonl
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/app.log | tail -60
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/log/maafw.log | tail -120
adb logcat -d -v time | grep -iE 'MaaFw|MaaRunner|maaphone|Brain|Shizuku|FATAL|AndroidRuntime' | tail -120
```

Read outcomes literally:

- `COMPLETED` = MaaFramework did what the plan said. It does **not** mean
  the user goal happened (there may be no postcondition).
- `COMPLETED_WITH_FAILURES` = at least one task/node failed; MaaFW's
  per-task message is the useful part.
- `NOT_RUN` / `Rejected` = the privileged process refused the plan before
  execution (native load failure, busy runner, invalid plan).
- `Cancelled` = a stop/lifecycle event.

### 3.2 Pipeline override shape

MaaFW's `pipelineOverride` is an **object mapping node name → node**, not a
bare node. The M1 first-run bug was exactly this: a bare
`{"action":"StartApp"}` was parsed as a node named `action`, and
`maafw.log` said:

```
task not found [node_name=action]
parse_task failed [key=action] [value=StartApp]
```

Correct shape:

```json
{
  "brain_s1_0": {
    "recognition": "DirectHit",
    "action": "StartApp",
    "package": "com.android.settings"
  }
}
```

On Android, `BrainRunner.kt` and `AgentTools.kt` wrap every compiled node.
If you add a runner path, preserve that invariant.

### 3.3 Native library load failures

Symptom: privileged process refuses all runs; `MAA_LOAD_FAIL`,
`UnsatisfiedLinkError`, or `dlopen failed` in the debug logs.

```bash
adb shell 'find /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/debug -maxdepth 1 -type f -print'
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/debug/service_boot_debug.log | tail -60

# Compare the three copies: source -> APK -> installed
sha256sum android/app/src/main/jniLibs/arm64-v8a/libfastdeploy_ppocr.so
unzip -p android/dist/maa-phone-m3.2-debug.apk lib/arm64-v8a/libfastdeploy_ppocr.so | sha256sum
adb shell 'P=$(pm path com.aliothmoon.maafw.maaphone | sed "s/package://"); D=$(dirname "$P"); ls -l "$D/lib/arm64"; sha256sum "$D"/lib/arm64/libfastdeploy_ppocr.so'
```

The known root cause was Gradle/NDK `llvm-strip` corrupting an
already-stripped prebuilt `.so` (`empty/missing DT_HASH/DT_GNU_HASH`). Fix:
`packaging.jniLibs.keepDebugSymbols` for the MaaFW prebuilts; then compare
the APK entry byte-for-byte with the source. A tiny on-device `dlopen` test
is the fastest confirmation before reinstalling.

### 3.4 Display mode: main display vs virtual display

MaaFwApp background mode intentionally runs the target app on a
`MaaFwVirtualDisplay` (usually display 2, 1280×720). "Settings opened but I
can't see it" is usually **not a failure**; the task ran on the virtual
display.

```bash
adb shell dumpsys display | grep -iE 'DisplayDeviceInfo\{|mDisplayId|uniqueId' | head -40
adb shell dumpsys activity activities | grep -iE 'displayId|mResumedActivity|settings/' | head -60
adb shell dumpsys window displays | grep -E 'Display: mDisplayId|displayId|init=|cur=' | head -40
```

To watch behaviour on the real screen, switch **前台模式 (foreground)**. To
watch background behaviour, use the Assistant's live preview
(`PreviewPort` + `MaaPreviewSurface`). Coordinates learned on one display
profile are not valid on another; record the profile with point locators.

## 4. Layer 3 — MaaFramework recognition/action

### 4.1 Authored native pipeline

Use the Maa way, not a coordinate guess:

- observe first (`screencap`, `ocr`, boxes);
- derive clicks from recognition (`target` defaults to the recognized box
  centre);
- constrain with `roi`/`roi_offset`; use `order_by`/`index` for lists;
- prefer OCR for stable text, TemplateMatch for stable icons, ColorMatch
  for stable state colours, `And`/`Or` to combine;
- scale screenshots/templates/ROI to the 720×1280 base;
- recognize → act → recognize; do not hide problems with long blind waits.

If a node does not match, inspect the debug bundle's screenshots/draws, not
the model's explanation. `benchmark_node` (MaaMCP) is the cheapest way to
tune `threshold`/`roi`/`expected` before importing.

### 4.2 Proto debug bundles

Every prototype run writes `proto/data/runs/<id>/`:

```
events.jsonl     chronological brain/runner events
manifest.json    run metadata, coordinates, model answers
screenshots/     per-step captures
draws/           MaaFW draw images (when enabled)
maafw.log        native engine log
```

```bash
.venv/bin/python -m maa_phone trace <id>
.venv/bin/python -m maa_phone export <id>     # zip it
.venv/bin/python -m maa_phone debug full      # raise capture level before reproducing
```

If the debug bundle itself is empty, check that `debug` setting is not
`off`/`metadata` first.

### 4.3 ROI/target/coordinate hygiene

- `roi` is the recognition range, not a click coordinate.
- `target: true` clicks the recognized box; fixed `[x,y]` is a last resort
  and must be marked as such.
- Controller/raw pixels differ from the model's 720p image. Check the
  scale conversion (`ImageTools.scaleFactor`, `pointToRaw`) when a tap lands
  in the wrong place.
- The Everything-Maa coordinate-hygiene reference in `docs/maa-skills/` is
  the rulebook; see `AUTHORING.md` for the flow.

## 5. Layer 4 — The AI bootstrap / model loop

The on-device Android loop and `proto/agent_maa.py` are **bootstrap only**.
When they fail, do not conclude the project cannot do the task — first
decide whether the task should be authored natively instead.

### 5.1 No action / empty response

Symptoms: `model returned no action`, empty content, DSML/JSON parse
failure.

- Check Settings: `deepseek_api_key`, `deepseek_base`, `deepseek_model`;
  press **Test** in the Assistant.
- The bootstrap now tolerates up to `agent_repeat_limit` consecutive empty
  model responses, logging each one with its screenshot in `runs.steps_json`;
  it exits early after that instead of burning the whole step budget.
- `DeepSeekClient.nextAction` already retries once with a repair turn/JSON
  mode. If it still fails, inspect the HTTP failure in `runs.error` or
  logcat (`DeepSeek HTTP …`).
- `deepseek-v4-flash` supports native tool calling; some thinking modes
  mapped `response_format: json_object` onto internal DSML syntax. Native
  `phone_action` tool calling is the primary path.

### 5.2 Looping taps / wrong coordinate

Symptom from run #1 on device: repeated taps at y≈27 (status bar) until
max steps.

- The bootstrap now has a configurable early-exit guard
  (`agent_repeat_limit`, default 5): it counts consecutive empty/error
  results and repeated target-identical actions, and stops before spending
  the whole `agent_max_steps` budget (default 300). If it exits with
  `early exit: repeated action …`, the action loop is the problem — inspect
  `runs.steps_json` and the last screenshots, do not just raise the limit.
- Near-identical `tap`/`locate` points still have the dedicated three-tap
  guard with search-field auto-recovery.
- A precise point is not a reusable pipeline. Either convert it to a native
  recognition node (preferred) or at minimum capture a template crop.
- Check the raw screenshot at the exact turn; do not reason from the point
  list alone.

### 5.3 "It said done but nothing happened"

This is the core safety failure. A model `done` is only accepted after the
independent final vision check, and even then it is weaker than a
postcondition. Run a one-shot check:

```bash
cd proto
.venv/bin/python -m maa_phone ask-vision "is the like button pink/liked?"
.venv/bin/python -m maa_phone tap-vision "the leftmost like button in the bottom action bar"
```

For deterministic evidence, measure pixels (`pixel` verifier) or file
counts. False successes must remain in the DB as negative evidence; never
approve their proposals.

### 5.4 The LLM verifier is not ground truth (and can be flaky)

On the installed M3.2 test device, the Bilibili run #2 **actually liked the
video**, while the final LLM verifier said the like button was gray. We
proved this with pixels, not opinions:

```bash
# Pull the before/after screenshots
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/brain/shots/run2_turn4.png > /tmp/before.png
adb exec-out cat /sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/brain/shots/run2_final.png  > /tmp/after.png

# The like bbox (328,570,62,52) before = gray #61666D (222 px)
# after = Bilibili pink #FF6699 (222 px) + #FFB2CC (13 px)
```

The exact `DeepSeekClient.verify` prompt, replayed five times against the
same `run2_final.png`, returned **3 true / 2 false** with contradictory
evidence text. That makes a single call unsuitable as a promotion gate.

Rules (ADR-025):

- **First-time bootstrap:** the LLM verifier is the only checker. Make it
  robust: wait for animation, capture fresh screenshots, retry up to a few
  times, use a focused crop for toggle/icon state, and record everything in
  `runs.verify_json`; the result is still a candidate requiring Review.
- **Consolidated/live pipeline:** use a deterministic postcondition
  (`pixel` colour, `file_count`, `element`, `screen_text`). Replay must not
  depend on the model verdict.
- A model `false` should not single-handedly reject an independently verified
  success; a model `true` must never override a failing deterministic check.
- If a task is inherently model-judged, run multiple independent checks
  (crop/zoom, repeated votes, different view) and record all of them.
- **Toggles are stateful:** check the current state before tapping. If it is
  already active, do not tap. Never tap the same toggle twice; the second tap
  toggles it back. A focused zoomed crop is much more reliable than the full
  screenshot for pink/gray icon state.
- Never weaken a verifier to make a demo pass. Fix the verifier primitive.

### 5.5 `InputText` silently did nothing (virtual-display input bug)

Symptom: the bootstrap reaches the search field, taps it, and then either
never calls `text` or calls it and the field does not change. Numbers and
Chinese both fail.

Root cause found in the MaaFwApp native bridge:

- `DispatchInputMessage` in `app/src/main/native/bridge_input.cpp` handled
  `START_GAME`, `TOUCH_*`, `KEY_*`, but **no `INPUT` case**.
- `AndroidNativeControlUnitMgr::input_text()` called
  `dispatch_input_message()`; the default branch returned `0`, which
  MaaFramework treats as success. `InputText` was therefore a silent no-op.
- The missing `INPUT` case affects any `InputText` action, including numbers
  and Chinese, regardless of the app being controlled.

Fix now in source/the v1 APK:

- native bridge: `INPUT` calls `DriverClass.inputText(String, displayId)`;
- `InputControlUtils.inputText()` uses
  `KeyCharacterMap.load(VIRTUAL_KEYBOARD).getEvents(...)` for ASCII
  digits/letters; for non-ASCII (Chinese) it sets the clipboard and injects
  `KEYCODE_PASTE`;
- it clears/selects the focused field first so the new query replaces the
  old one.

Verification:

- Run #3 logged `AI step: text -> typed 350234` and then continued to the
  like stage (input stage fixed).
- Run #4 logged `AI step: text -> typed 阿米诺斯`,
  `AI step: key -> key enter`, and completed with a pending proposal.

If you are debugging this again, check logcat for `DriverClass.inputText` /
`InputControlUtils` messages and inspect `runs.steps_json` for an actual
`"action":"text"` step. Do not assume a model that omits `text` means the
bridge is working; and do not assume a successful `InputText` result means
text was injected until the field/screenshot confirms it.

## 6. Layer 5 — The authoring bench (MaaMCP + Everything-Maa)

When the goal is a reusable workflow, debug at the authoring layer:

1. Verify the device connection and capture a fresh state:
   `screencap`/`ocr`.
2. Confirm each target with a region crop; `save_captured_image` to create a
   real template.
3. Draft the contract with Everything-Maa:
   `maa-workflow-build` → state machine; `maa-pipeline-guide` → fields/ROI;
   `maa-pipeline-generate` → sweep; `maa-pipeline-testing` → ladder.
4. Benchmark each recognition node with `benchmark_node` (hit rate, score
   range, latency) before wiring `next`/`on_error`.
5. Import into the app:
   push `pipeline.json` + templates to
   `/sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/brain/imports/`,
   use **Import MaaMCP pipeline.json**, inspect the generated proposal.
6. Approve only after a verified replay. If the import loses/changes a
   field, that is an importer bug — compare the source pipeline with
   `steps_json`.

MaaMCP is PC/dev-time; it is never embedded in the APK. Keep it out of the
hot loop.

## 7. Debugging the workflow as a whole

Use a testing ladder, cheapest first:

1. **Unit tests** — `proto/tests/test_core.py`; Android compile/tests.
2. **DB/compiler** — compile the row; inspect node JSON.
3. **Single node** — run/benchmark one recognition/action node in isolation.
4. **Short chain** — recognize→act→recognize for one transition.
5. **Replay-K** — `evaluate <pipeline> --times K`; use the same start state.
6. **On-device smoke** — install the APK, run the real goal, inspect DB +
   logs + screenshots.
7. **Promotion** — only after verification; then check repeated replay
   trust.

Save the failing run; a bug report here should be:
`goal + pipeline/version + plan + node + screenshot + maafw.log + run id`.

## 8. Known traps and gotchas

- **Machine clock jumps.** During the original OpenCode session the clock
  moved backwards; file mtimes and `ORDER BY time_created` lied. Use SQLite
  `rowid`/insertion order when reconstructing chronology.
- **Two app icons confuse testers.** `Maa-phone` is the stock PI library
  (demo tasks); `Assistant` is our brain. Most "open settings failed" reports
  were tests run from the wrong icon.
- **MIUI install restrictions.** `INSTALL_FAILED_USER_RESTRICTED` means
  enable *Install via USB* or install from the phone's file manager.
- **Binder size.** Screenshots always go through files
  (`saveCachedImage(path)`), never Parcelables.
- **Privileged CWD is not writable.** Pass explicit writable paths.
- **Shizuku after reboot.** Authorization must be re-granted (root avoids
  it).
- **Dynamic screens.** Bilibili can resume in different states; a pipeline that
  replayed 6/6 can fail on the next launch. Do not generalize from one
  successful replay; add asserts/retries/fallbacks and keep verifying.
- **Toggle idempotency.** Like/follow are state toggles. The agent must read
  the current state first; replay assumes a known start state.
- **`verified=0` is not `failed`.** Old/no-postcondition pipelines still run,
  but they are only "completed", not proven. Conversely, a model verifier
  `false` is not proof the action failed — inspect deterministic evidence
  (§5.4).
- **Android 11+ package visibility hides installed apps.** If
  `queryIntentActivities(MAIN/LAUNCHER)` returns far fewer apps than
  `pm list packages -3`, add a `<queries><intent><action MAIN/><category
  LAUNCHER/></intent></queries>` declaration. This is what made the finance
  manager invisible to our app catalog (21 apps visible -> 70 after the
  declaration; `com.anonymous.financemanager` appeared).
- **Keyboard can cover the goal field.** The Assistant uses `imePadding()` and
  a scrollable content column; if the field is still obscured, check
  `windowSoftInputMode` and the Compose insets.
- **HOME is global and does not render on the virtual display.** Run #10
  showed all-black `1280x720` screenshots after `key home`. Do not use HOME
  as a normalization step in background mode. MaaFwApp/MAA-Meow resolve a
  package with `PackageManager.getLaunchIntentForPackage` and launch it with
  `StartApp`; they do not drive the drawer. The Android brain now syncs
  launchable apps into the `apps` registry so the AI can find the finance app
  by label and `launch` its package directly. HOME/drawer navigation is an
  optional foreground-only test.
- **Android bootstrap run history.** `runs.ai_cost` is currently 0 on
  Android because token accounting is not wired; do not use it for cost
  evidence there. Use the PC prototype for cost measurements.

## 9. When you fix a bug, leave a trail

The project's debugging value comes from the artifacts it leaves behind:

1. Add a DB row/column if the failure had a data shape (no hidden state).
2. Add a test in `proto/tests/` or a regression note in `PROTOTYPE.md`.
3. If it is a build/device issue, add it to `STATUS.md` or the Android
   "known pitfalls" section.
4. If it changes a primitive, add one ADR line in `DECISIONS.md` and one
   vocabulary line in `WORKING_STYLE.md`.
5. Re-run the relevant acceptance test at the highest layer you can afford
   (unit → benchmark → on-device smoke → replay).

The goal is not "no bugs"; it is that the next person (or AI) finds the
evidence for a bug in one place and knows which layer to inspect.
