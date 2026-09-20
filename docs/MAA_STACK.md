# MAA stack integration

This document records **exactly which MAA-series project does what**, which
reference revision is being used, how it is integrated today, and what
remains. It is the answer to "we integrated MaaMCP, MaaFramework, MaaFwApp,
Everything-Maa … how, and where in the code?"

> The short version: **MaaFramework is the engine, MaaFwApp is the Android
> shell, MaaMCP is the authoring tool bench, Everything-Maa provides the
> authoring skills for the AI agent, and maa-phone owns the pipeline/state-
> machine DB, resolver, compiler, verification, learning loop and IO.**
>
> Convention: **pipeline/workflow = runtime state-machine row; skill =
> Everything-Maa authoring instruction; tool = MaaMCP procedure.** See
> [`CONVENTIONS.md`](CONVENTIONS.md).
>
> Shorthand used in notes/logs: **maafw** = MaaFramework, **maafwapp** =
> MaaFwApp, **maamcp** = MaaMCP, **everything-maa** = Everything-Maa.

## 1. Roles and pins

Reference checkouts live in `../../00ref/` (workspace-relative) and were
snapshotted on 2026-09-19.

| Project | Reference pin | License | Role | State in maa-phone |
|---|---|---|---|---|
| **MaaFramework** (`MaaFW`) | `b849283` (native release `v5.13.1`) | LGPL-3.0 | recognition/action engine: OCR, TemplateMatch, FeatureMatch, ColorMatch, NN, ROI/target/order_by, actions, controllers | **Integrated.** Python binding used by `proto/`; Android APK ships `v5.13.1` `.so`; compiler stores native protocol fields 1:1; `MaaTaskerPostTask` runner contract verified. No core fork. |
| **MaaFwApp** | `70cd377` | AGPL-3.0 | Android privileged shell: Shizuku/root runner, native controller, virtual display, overlay, notifications, scheduling, logs, agent host | **Forked and extended** at `android/`. The stock shell is reused; `brain/` + Assistant + importer are our delta. M0/M1/M3.2 APKs built. |
| **MaaMCP** | `ff2d2b5` (`v1.2.3`) | AGPL-3.0 | PC MCP server: screencap/OCR exploration, control, `save_captured_image`, `save_pipeline`, `run_pipeline`, `benchmark_node` | **Mapped and bridged, not embedded.** Tool surface documented in `INTEGRATION.md`; pipeline import/export implemented in `proto/pipeline_io.py`, and Android currently implements the import side (`Importer.kt`); the actual MaaMCP authoring pass for Bilibili is still a next action. |
| **Everything-Maa** | `8985260` | MIT | authoring skills: workflow contract, field reference, coordinate hygiene, pipeline generation/testing, option/protocol, diagnosis | **Vendored as docs.** Eight relevant skills copied to `docs/maa-skills/`; [`AUTHORING.md`](AUTHORING.md) makes them the workflow-authoring rules. No code dependency. |
| **M9A** (sample reference, not a dependency) | `4fa65aa` (2026-09-18) | AGPL-3.0 | mature MaaFW project structure: PI tasks/options, per-server resource overlays, recognition-gated pipeline graphs, AgentServer custom actions/recognitions | **Studied as the structural sample** in [`M9A_REFERENCE.md`](M9A_REFERENCE.md). Copy architecture/rules, never game content. |
| **chatbot-ai** (workspace reference, not MAA) | `9a41c46` | pre-existing reference | the working method: FAQ/rules/flows/tasks/extractions + generic engine + review + maintenance + cache | **Lineage and proof of the method.** Mapped table-by-table in [`WORKING_STYLE.md`](WORKING_STYLE.md) §5; behavior ported conceptually, not literally. |

Pins are recorded because upstream moves. Re-check and update this table when
refreshing a checkout.

## 2. Where each integration lives

```
                    PC / dev-time bench                         on-device product
┌─────────────────────────────────────────┐   import    ┌─────────────────────────────────────┐
│ MaaMCP + Everything-Maa                 │────────────►│ maa-phone android/                  │
│  screencap/OCR exploration              │ pipeline.json│  Assistant + Review + brain DB      │
│  ROI/template/color sweeps              │  + templates │  resolver/compiler/learner          │
│  save_captured_image / save_pipeline    │             │  Importer.kt                        │
│  benchmark_node                         │◄────────────│  export live pipeline -> pipeline.json │
└─────────────────────────────────────────┘             └───────────────┬─────────────────────┘
                                                                        │ RunPlanPayload / AIDL
                                                                        ▼
                                                       ┌─────────────────────────────────────┐
                                                       │ MaaFwApp fork (android/)            │
                                                       │  app process      privileged proc   │
                                                       │  brain + UI  ───► MaaRunner          │
                                                       │                    │                │
                                                       │                    ▼                │
                                                       │         MaaFramework .so            │
                                                       │  OCR / TemplateMatch / actions       │
                                                       │  AndroidNativeControlUnit            │
                                                       └─────────────────────────────────────┘
```

Detailed paths:

- **MaaFramework**
  - `proto/maa_phone/maa_exec.py` — Python binding wrapper: controller,
    resource bundle, tasker, `override_pipeline`, `post_recognition`,
    `post_image`, log/draw directories.
  - `proto/maa_phone/compiler.py` + `pipeline_model.py` — emit/validate
    native MaaFW node JSON; keep `expected`, `roi`, `roi_offset`,
    `threshold`, `method`, `order_by`, `index`, `target`, `next`, ….
  - `android/app/src/main/jniLibs/arm64-v8a/` — prebuilt release `.so`
    (deployed by `android/scripts/setup_maa_framework.py`, pinned by
    `android/.maafwversion`).
  - `android/.../BrainRunner.kt`, `AgentTools.kt`, `Compiler.kt` — the same
    engine on-device through MaaFwApp's runner.
- **MaaFwApp**
  - `android/` is the fork. Upstream remains in `00ref/MaaFwApp`.
  - Our delta is documented in [`FORK_DELTA.md`](FORK_DELTA.md):
    `brain/` (DB, resolver, compiler, runner binding, AI bootstrap,
    learner), Assistant UI, importer, `packaging.jniLibs.keepDebugSymbols`
    fix, build overlays.
  - The crucial contract is unchanged upstream AIDL:
    `RemoteService.startRun(String runPlanJson) = 51`,
    `RunPlanPayload { resourcePaths, screenWidth/Height, displayMode,
    tasks: [{ taskName, entry, pipelineOverrides }] }`, and
    `MaaRunner` passing each task's overrides to `MaaTaskerPostTask`.
    No AIDL change was needed for M0–M3.
- **MaaMCP**
  - `00ref/MaaMCP` — reference source and tool surface.
  - `proto/pipeline_io.py` imports standard MaaFW pipeline JSON as a
    `pipeline_new` proposal and exports a live pipeline back to pipeline JSON.
  - `android/.../Importer.kt` imports pipeline JSON, copies referenced
    template images into a versioned MaaFW bundle, and creates a Review
    proposal; Assistant exposes the import action. Android export is still
    pending (use the prototype exporter for now).
  - Not embedded in the APK. Not in the hot loop. This is an intentional
    license/architecture boundary (ADR-021).
- **Everything-Maa**
  - `docs/maa-skills/` contains `maa-workflow-build`, `maa-pipeline-guide`
    (field reference + coordinate hygiene), `maa-pipeline-generate`,
    `maa-pipeline-testing`, `maa-cli-operate`, `maa-wiki`, and
    `maa-diagnose`, copied from the pinned upstream.
  - [`AUTHORING.md`](AUTHORING.md) is the operational flow; the skills are
    the field-level rulebook.
- **M9A** (sample reference)
  - `00ref/M9A` is the structural sample. See
    [`M9A_REFERENCE.md`](M9A_REFERENCE.md) for the concrete patterns we
    adopt (recognition-gated pipeline graphs, `next` recovery,
    `pre/post_wait_freezes`, resource overlays, AgentServer custom code as
    an extension point) and the Bilibili mapping to our pipeline importer.
- **chatbot-ai**
  - `00ref/chatbot-ai` is the method proof. The mapping table in
    `WORKING_STYLE.md` §5 is the contract for what we port (uniform
    knowledge surfaces, matching/action as data, propose→Review, nightly
    maintenance, cache, eval) and what we defer.

## 3. Data flows that cross a project boundary

### 3.1 Authoring → runtime (the intended reusable workflow path)

1. MaaMCP observes the real device (`screencap`, `ocr`) and captures stable
   regions/templates (`save_captured_image`).
2. Everything-Maa skills drive the contract: recognition→action→recognition,
   ROI, `order_by`/`index`, coordinate hygiene, no blind `DirectHit` clicks.
3. MaaMCP writes standard `pipeline.json` plus template images.
4. Push both into
   `/sdcard/Android/data/com.aliothmoon.maafw.maaphone/files/brain/imports/`.
5. Assistant → **Import MaaMCP pipeline.json** → a pending Review proposal.
6. Approve → live `pipelines` rows with native pipeline graphs; templates
   are copied into a versioned bundle (`files/pi/brain/res_N/image/`).
7. Replay through the MaaFwApp runner: 0 AI tokens; `runs` stores evidence.
8. Failures export/benchmark through MaaMCP and return as healing
   proposals.

### 3.2 Bootstrap → evidence (temporary path)

The on-device DeepSeek loop (`AgentRunner.kt`, `proto/agent_maa.py`) exists
only to get first contact with an unknown screen. It captures screenshots,
calls `phone_action`, performs one native node per action, records the
trajectory, and asks the model for final verification. It must not be the
source of final point-coordinate pipelines. Convert the observed transitions
into native recognition nodes via §3.1.

### 3.3 Postcondition verifiers

`file_count`, `element`, `screen_text` and `pixel` are data in
`pipelines.postcondition_json` and are implemented cheaply in the PC prototype
(`verifiers.py`). On Android the intended landing is MaaFW `Custom`
recognition/action through the existing Agent host, not raw ADB.

## 4. License and boundary rules

- **MaaFramework** is LGPL-3.0. It is linked as a library, not modified.
- **MaaFwApp** and **MaaMCP** are AGPL-3.0. A distributed MaaFwApp fork
  stays AGPL. MaaMCP stays an arm's-length tool (process boundary), never
  linked into the APK.
- **Everything-Maa** skills are MIT; their files keep upstream provenance
  and are re-copied, not edited in place.
- **chatbot-ai** is a design/reference checkout, not a runtime dependency.

## 5. What is integrated now vs pending

| Capability | State |
|---|---|
| Store MaaFW native recognition/action fields 1:1 | ✅ prototype + Android compiler |
| Fresh v1 schema: `pipelines`, `pipeline_versions`, `missions`, `pipeline_id` | ✅ source `schema.sql` + Android asset; `maa-phone-v1-debug.apk` built (clean install; no migration) |
| Compile DB rows to native MaaFW nodes | ✅ prototype + Android `Compiler.kt` |
| Run through MaaFwApp runner with no AIDL change | ✅ M0/M1 proven for flat step tasks |
| Execute a stored workflow as a real MaaFW graph (`next`/`on_error`/anchors) | ⏳ **not yet**: importer/compiler/runner flatten and rename nodes (ADR-027) |
| Import standard MaaFW pipeline JSON as a proposal | ✅ `proto/pipeline_io.py` + Android `Importer.kt` (stores node JSON; graph execution still pending) |
| Export a live pipeline to MaaFW pipeline JSON | ✅ prototype (`export-pipeline`); Android export pending |
| Vendor + follow Everything-Maa authoring rules | ✅ `docs/maa-skills/` + `AUTHORING.md` |
| Run an actual MaaMCP authoring + benchmark pass | ⏳ next (Bilibili first) |
| Import a real MaaMCP pipeline and replay it verified | ⏳ next |
| On-device `Custom` verifier nodes (`pixel`/`file_count`/`element`) | ⏳ M3+/M4 |
| MaaFW Record/Replay/Dbg or MaaMCP `benchmark_node` as promotion gate | ⏳ v0.1 |
| Automatic element healing from debug evidence | ⏳ v0.1 |

## 6. Reference commands

```bash
# run from the workspace root (test_maa/)
# refresh / inspect reference checkouts
cd 00ref/MaaFramework  && git log -1 --oneline
cd 00ref/MaaFwApp      && git log -1 --oneline
cd 00ref/MaaMCP        && git log -1 --oneline
cd 00ref/Everything-Maa && git log -1 --oneline

# Android: deploy the pinned MaaFramework release
cd .
python3 scripts/setup_maa_framework.py --abi arm64-v8a

# PC prototype: native MaaFW pipeline import/export
cd proto
.venv/bin/python -m maa_phone export-pipeline 2
.venv/bin/python -m maa_phone import-pipeline pipeline.json "goal text"
```

For failure triage across these boundaries, see
[`DEBUGGING.md`](DEBUGGING.md). For the exact runner/pipeline contract, see
[`INTEGRATION.md`](INTEGRATION.md).
