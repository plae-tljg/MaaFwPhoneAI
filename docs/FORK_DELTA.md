# MaaFwApp fork delta

What we reuse, add, and change when forking
[`Aliothmoon/MaaFwApp`](https://github.com/Aliothmoon/MaaFwApp) (AGPL-3.0)
as the shell. Reference: its `RemoteService.aidl`, `remote/MaaRunner.kt`,
`runner/RunPlan*`, `privileged/`, `overlay/`, `schedule/`.

## 1. Reuse as-is (do not touch unless needed)

| Area | Paths | Why it matters |
|---|---|---|
| Privileged runtime | `privileged/ProcessSpawner.kt`, `ShizukuSpawner.kt`, `SuSpawner.kt`, `RemoteServiceManager.kt`, `remote/RemoteServiceImpl.kt` | Shizuku/root process lifecycle, watchdog, heartbeat |
| MaaFW runner | `remote/MaaRunner.kt`, `runner/MaaFrameworkRunnerPort.kt`, `runner/RunLauncher.kt`, `runner/RunPlan*.kt` | Resource/Tasker/Controller, run plans, callbacks |
| Display control | `remote/internal/VirtualDisplayManager.kt`, `DisplayHelper.kt`, `ScreenManager.kt`, `PrimaryDisplayManager.kt` | background virtual display, forced size |
| Capture / preview | `remote/internal/FrameCaptureHelper.kt`, `runner/PreviewPort.kt`, `ui/components/MaaPreviewSurface.kt`, `native/bridge_capture*` | screenshots for AI + UI preview |
| Foreground UX | `overlay/` (FloatBall, OverlayPanel), `service/RunForegroundService.kt` | float ball, run panel, keep-alive |
| Scheduling | `schedule/` | nightly triggers (also usable for the learn job) |
| Notifications | `notification/` | run results, approval reminders |
| Logs | `log/`, `runner/RunLog*.kt`, `ui/logs/` | audit trail, debugging |
| Permissions | `privileged/PermissionManager.kt`, `PermissionGateway.kt` | bootstrap without manual system pages |
| Build plumbing | `build-logic/`, `scripts/setup_maa_framework.py`, `hidden-api/`, `ksp-processor/` | MaaFW `.so` deployment, prefs, JNI |

## 2. Add: `brain/` package (app process)

```
brain/
  db/           SQLiteOpenHelper over assets/brain/schema.sql (core tables
                + messages/decision_cache/policies/eval primitives)
  resolver/     alias matching with exact > substring > fuzzy + confidence gate
  compiler/     pipelines.definition_json -> MaaFW pipeline JSON (resolve element refs)
  bridge/       talk to the privileged runner (run plans; direct control if added)
  agent/        AI fallback loop: screenshot/OCR -> model -> act -> record run
  ai/           HTTP client for the model provider; API key from settings
  learn/        WorkManager worker: runs -> proposals (promote/heal/alias)
  review/       approve/reject proposals -> upsert pipelines/elements/apps.aliases
```

UI additions:
- **Assistant screen** — prompt input, run history, queue, paused runs.
- **Proposals screen** — approve/reject, bulk approve, instant "Save as
  pipeline?" cards from successful AI runs.
- **Prompt sheet** — shared by the Quick Settings tile, notification
  actions, and float ball; one `enqueue_goal` call underneath.
- **Queue/ask** — when a goal arrives during a run: Queue / Do now /
  Cancel (Queue is the 10s default; Do now pauses current and auto-resumes
  it after).
- **Run controls** — notification and float ball expose Stop (instant) and
  Pause (finish current step, persist `runs.progress_json`); Resume from
  the queue.

Keep the existing PI screens as the "Library" tab.

## 3. Change

> The field-level contract and source facts are in
> [`INTEGRATION.md`](INTEGRATION.md) §2. Summary:

1. **Binding to the runner — no AIDL change needed for pipelines.**
   `RemoteService.startRun(String runPlanJson)` (transaction 51) takes a
   `RunPlanPayload` whose `tasks[].entry` + ordered
   `tasks[].pipelineOverrides: List<JsonObject>` are passed straight to
   `MaaTaskerPostTask(tasker, entry, overrides)` by `MaaRunner`. Build the
   run plan with our generated entry node + compiled nodes as overrides.
   Add a new AIDL method only if a direct-control group is needed later;
   transaction ids grow, never reorder (the file's rule).
   **Current state:** M1/M3.2 send all compiled steps in one plan.
   **Target (M2):** submit one step (one-node task) per startRun so pause
   can land between steps; resume sends the remaining steps.
2. **Surfaces.** Quick Settings tile (`TileService`; request-add on
   Android 13+), run-service notification actions, and the existing float
   ball. All call the brain's single `enqueue_goal`. **Current state:**
   in-app Assistant input exists; the other surfaces are still pending.
3. **Direct control for the AI loop.** The bootstrap currently needs
   screenshot/click/swipe/text/key, and each is a **one-node run plan**
   (no AIDL change), as implemented in `AgentTools.kt`. OCR observation
   is not yet exposed. Add a small AIDL direct-control group only if
   latency demands it. Screenshots go to files via
   `saveCachedImage(path)` (binder transactions cap at ~1MB). Postcondition
   verifiers (`pixel`, `file_count`, `element`, `screen_text`) become MaaFW
   `Custom`/Agent nodes, not raw ADB calls.
4. **Navigation.** `ui/navigation/Routes.kt` / `ui/AppRoot.kt`: Assistant
   becomes the home tab; PI tasks move to Library.
5. **PI stays optional.** We don't need a build-time PI package to run
   learned pipelines. Keep PI for OCR models / curated bundles only.
6. **Persistence.** The fork uses DataStore for app settings. The brain DB
   is a `SQLiteOpenHelper` over `assets/brain/schema.sql` with the same
   forward-only discipline as the prototype (the current v0 uses the
   fresh-DB baseline; a `settings.schema_version` migration path is the
   next hardening step). The DB is the source of truth; learned templates
   are written to a versioned resource dir (`files/pi/brain/res_N/image/`)
   and registered through the MaaFW resource path; OCR models come from the
   bundle/model/ocr path in `RunPlanPayload.resourcePaths`. PI remains
   optional/curated, not build-time required for learned pipelines.
7. **Settings.** Model provider, API key, base URL and model are already in
   the Assistant Settings tab; confidence threshold, `learn_min_runs`,
   auto-approve flags and autonomy default remain.

## 4. Constraints and gotchas

- **AGPL-3.0** — a distributed fork must remain AGPL and source-available.
- **AIDL versioning** — the app may outlive an old privileged process after
  an update; transaction ids are frozen, never reordered/retyped.
- **Binder size** — images always via files, never Parcelables/byte arrays.
- **Privileged storage** — the runner's CWD is not writable; pass explicit
  writable paths (as `setup(piRoot, logDir, ...)` already does). The brain's
  DB lives in the app process only.
- **Shizuku after reboot** — needs re-authorization; root avoids it. Plan
  an onboarding screen.
- **Virtual display quirks** — some apps behave differently in background
  mode; a pipeline can record which mode it was learned in.
- **Model latency** — never in the recognition loop; see DESIGN section 8.

## 4b. MaaMCP (dev-time, not in the APK)

MaaMCP is the authoring/benchmark bench (screencap region, OCR, control,
`save_captured_image`, `save_pipeline`, `run_pipeline`, `benchmark_node`).
It produces standard MaaFW pipeline JSON; our importer turns that into a
pending Review proposal (approval publishes `elements`/`pipelines` rows), and
the prototype exporter emits live pipelines back for benchmarking or sharing. It is never embedded in the app and never
in the recognition hot loop. See `INTEGRATION.md` §3 and ADR-021.

## 5. Milestones

| # | Goal | Acceptance |
|---|---|---|
| M0 | Fork builds and runs an existing PI task on-device | ✅ **done**: `dist/maa-phone-m0-debug.apk`, MaaFramework 5.13.1 demo PI; run `COMPLETED`; native `.so` strip corruption fixed |
| M1 | DB + resolver + compiler run one seeded pipeline | ✅ **done for one pipeline**: `open settings` through DB → compiler → `RunPlanPayload`; Assistant shows the run. Graph-native execution, pause and local postcondition are not yet wired |
| M2 | Interaction surfaces + queue/ask/pause | ⏳ **not done**: Assistant input/thread/Review exist; tile/notification/float-ball, queue/break-in, step-wise pause/resume are pending |
| M3 | AI fallback loop | ⚠️ **built; action succeeded but verification is not trustworthy yet**: M3.2 has screenshot/vision/one-node actions, trajectory, strict final verification, settings and proposal filing. Run #1 looped on direct taps; run #2 actually liked the video but the LLM verifier false-negatived it (3 true / 2 false on the same screenshot). The corrected path is deterministic postconditions + native authoring/import (ADR-022/025) |
| M4 | The flywheel on Android | ⏳ **not done**: no verified on-device proposal has been approved and replayed with `path='pipeline'`, `ai_cost=0` |
| M5 | Healing + safety | ⏳ **not done** on Android; prototype has the proposal/counter machinery and policies |

M0–M1 prove the substrate. The prototype proves M3–M5 **conceptually and
live on a PC**, but the Android flywheel is not closed yet. The immediate
path is authoring a native pipeline with MaaMCP + Everything-Maa, importing
it, and replaying it verified. See [`STATUS.md`](STATUS.md) for the ordered
plan.

## 5b. Fork location and current status (2026-09-19 late)

The fork lives at `./` (copied from the reference checkout
`70cd377`, AGPL-3.0). The build succeeds with MaaFramework 5.13.1
`arm64-v8a` native libraries, our minimal PI (`android/pi/`) and profile
(`android/pi-profile.yaml`). M0, M1 and M3.2 APKs are built; the current
brain package contains `BrainDb`, `Resolver`, `Compiler`, `BrainRunner`,
`AgentRunner`, `AgentTools`, `DeepSeekClient`, `Learner`, `Importer` and
`AssistantActivity`. Host-specific build overrides (NDK 27.1, compileSdk 37
via the writable SDK overlay at `test_maa/android-sdk`) and manual test
steps are documented in [`android/MAA_PHONE.md`](../android/MAA_PHONE.md).
The exact runner contract is in `INTEGRATION.md` §2; current gaps and the
next authoring step are in `STATUS.md`.

## 6. Prototype (`proto/`)

`proto/` is a PC-side Python implementation of the brain for fast
validation over ADB. It now uses **MaaFramework** by default (`--engine
maa`): the compiler emits real pipeline JSON, recognition is MaaFW
OCR/TemplateMatch, actions go through `AdbController`, and every run has a
debug bundle + postcondition (`runs.verified`). The raw-ADB/UIAutomator
engine remains as `--engine adb`. It uses the same `schema.sql` and the same
resolver/compiler/learning contracts. The Android fork later replaces the
device adapter with the privileged MaaFW runner and re-implements the brain
in Kotlin (ADR-004); the prototype is the behavioral reference and test
harness. Phase B results are in `PROTOTYPE.md`.
