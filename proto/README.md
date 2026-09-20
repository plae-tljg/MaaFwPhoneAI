# maa-phone prototype (`proto/`)

> **Convention note:** in this document, "pipeline"/"workflow" is the runtime
> MaaFW state machine; "skill" is an Everything-Maa authoring instruction.
> The fresh v1 schema/CLI use `pipelines`; see
> [`../docs/SCHEMA.md`](../docs/SCHEMA.md).

PC-side **reference implementation of the brain** (ADR-004's behavioral
spec) that drives a real phone over ADB. It uses the same `../schema.sql`
and the same resolver / compiler / learning contracts as the Android fork,
so the fork can port it 1:1. See [`../docs/PROTOTYPE.md`](../docs/PROTOTYPE.md)
for test results and limitations, [`../docs/STATUS.md`](../docs/STATUS.md)
for current overall state, and
[`../docs/DEBUGGING.md`](../docs/DEBUGGING.md) for the failure playbook.

> **Current state:** `34` unit tests pass. Camera and Bilibili 114514 → like
> are proven live with postconditions (AI once, then verified 0-token
> replays). This prototype implements the full method; the Android fork is
> a partial port and the Android flywheel is not closed yet. The prototype
> is the behavioural spec, not the product runtime.

## What it does

```
goal ──► resolver (DB aliases) ──hit──► compiler ──► MaaFW runner (0 AI cost)
                  │ miss                                   │
                  ▼                                        ▼
          AI vision fallback (DeepSeek, native tools)   postcondition verifier
                  │                                        │
                  └──► run recorded ──► proposal ◄─── verified=1 + evidence
                                             │ approve
                                             ▼
                            pipeline live ──► next time deterministic + verified
```

Recognizers, from cheapest to most expensive:

| Recognizer | Engine | How | Cost |
|---|---|---|---|
| `ocr` | maa/adb | MaaFW PP-OCRv5 (default) or UIAutomator text (`--engine adb`) | free |
| `template` | maa/adb | MaaFW TemplateMatch (default) or local NCC (scipy) | free |
| `point` | both | learned raw device coordinates (fallback) | free but fragile |
| AI vision | both | DeepSeek `deepseek-flash`, native tool calling | tokens |

Postconditions (`pipelines.postcondition_json`) are checked after the steps and
decide `runs.verified`. Verifier types: `file_count`, `element`,
`screen_text`, `pixel` (see `maa_phone/verifiers.py`). `{}` skips
verification, so rows without a postcondition keep working but are not claimed as verified.

## Setup

```bash
cd proto
python3 -m venv --system-site-packages .venv
.venv/bin/pip install -r requirements.txt      # Pillow, pytest
.venv/bin/pip install maafw                    # MaaFramework Python binding
python tools/setup_maa.py                      # OCR models + bundle/ layout
cp .env.example .env                           # then put your API key in it
adb devices                                    # one device authorized
```

`.env` (gitignored):

```
DEEPSEEK_API_KEY=sk-...
DEEPSEEK_MODEL=deepseek-flash
```

## Usage

```bash
.venv/bin/python -m maa_phone reset                       # fresh DB + run bundles
.venv/bin/python -m maa_phone apps                        # known packages registry
.venv/bin/python -m maa_phone app com.android.camera 相机 camera
.venv/bin/python -m maa_phone hint tv.danmaku.bili "ui tips for the agent"
.venv/bin/python -m maa_phone goal "open the camera app and take a photo"
.venv/bin/python -m maa_phone goal "..." --engine adb      # Phase A raw-ADB engine
.venv/bin/python -m maa_phone goal "..." --verify '{"type":"pixel","point":[130,1200],
    "rgb":[255,102,153],"tolerance":60,"radius":7}'       # verify an AI run too
.venv/bin/python -m maa_phone proposals                   # review queue
.venv/bin/python -m maa_phone approve 1                   # make it live
.venv/bin/python -m maa_phone goal "..."                  # replay, 0 tokens, verified
.venv/bin/python -m maa_phone runs                        # success / verified / cost
.venv/bin/python -m maa_phone pipelines / elements
.venv/bin/python -m maa_phone versions --pipeline 1
.venv/bin/python -m maa_phone mission-create "morning"
.venv/bin/python -m maa_phone mission-add 1 --pipeline 1 --goal "open camera"
.venv/bin/python -m maa_phone missions
.venv/bin/python -m maa_phone mission-items 1
.venv/bin/python -m maa_phone pipeline-postcondition 1 '{"type":"file_count",
    "path":"/sdcard/DCIM/Camera","delta_min":1,"timeout":10}'
.venv/bin/python -m maa_phone debug full                  # off|metadata|screens|full
.venv/bin/python -m maa_phone trace 2                     # run timeline + events
.venv/bin/python -m maa_phone thread                      # IO message stream
.venv/bin/python -m maa_phone answer 5 "WhatsApp"         # answer a needs_input question
.venv/bin/python -m maa_phone export 2                    # zip debug bundle
.venv/bin/python -m maa_phone import-pipeline p.json "open settings" --package com.android.settings
.venv/bin/python -m maa_phone export-pipeline 2           # live pipeline -> MaaFW pipeline JSON
.venv/bin/python -m maa_phone policies                    # unknown-goal safety gates
.venv/bin/python -m maa_phone evaluate 1 --times 3 --yes  # replay-K trust evidence
.venv/bin/python -m maa_phone evals                       # past evaluations
.venv/bin/python -m maa_phone cache                       # decision-cache stats / --clear
.venv/bin/python -m maa_phone learn                       # nightly sweep
.venv/bin/python -m maa_phone resume 3 --yes              # resume a paused run
.venv/bin/python -m maa_phone process-queue --yes         # drain queued goals
.venv/bin/python -m maa_phone tap-vision "the like button"  # one-shot vision locator
.venv/bin/python -m maa_phone ask-vision "is it liked?"     # one-shot vision check
.venv/bin/python -m pytest tests/ -q
```

`goal`/`resume`/`process-queue` require `--yes` for pipelines with
`autonomy='confirm'` (or a matching `policies` row) when stdin is not a
terminal; `autonomy='never'` and deny policies always refuse.

Interaction states from the design are implemented at the brain level:
`runs.state` (`queued|running|paused|needs_input|done|failed|cancelled`),
`--pause-after N`, `resume`, and the `--interrupt ask|queue|now|cancel`
protocol. The Android surfaces (tile/notification/float ball) call the same
`enqueue_goal` logic in the fork.

## Modules

| File | Role |
|---|---|
| `db.py` | SQLite helpers over `../schema.sql` + forward-only migrations; messages/cache |
| `pipeline_model.py` | native MaaFW field normalization/validation and node building |
| `pipeline_io.py` | MaaFW pipeline JSON <-> proposals/rows (MaaMCP bridge) |
| `policies.py` | unknown-goal safety gates as data (`match_json`/`action_json`) |
| `safety.py` | autonomy and policy enforcement (one decision point) |
| `evaluate.py` | replay-K evaluation + `eval_runs`/`eval_items` evidence |
| `maa_exec.py` | MaaFramework controller/resource/tasker wrapper: screenshot, OCR, TemplateMatch, actions |
| `runner.py` | deterministic pipeline runner: compile -> one node at a time -> postcondition -> run row |
| `verifiers.py` | postcondition verifiers (`file_count`, `element`, `screen_text`, `pixel`) |
| `debug.py` | per-run bundles, events, screenshots/draws, trace, export |
| `agent_maa.py` | AI fallback on MaaFW primitives with native tool calling |
| `device.py` | Phase A ADB adapter: screencap, tap/swipe/text/key, UIAutomator, apps |
| `uiauto.py` | XML parse/search/summarize |
| `matching.py` | local NCC template matching (scipy FFT) |
| `resolver.py` | alias scoring with confidence gate + canonical goal |
| `compiler.py` | steps + element refs -> executable steps |
| `executor.py` | step runner with waits/retries; pause between steps |
| `vision.py` | DeepSeek chat + image encoding |
| `agent.py` | AI vision fallback loop; records trajectory + screenshots |
| `learn.py` | trajectories -> proposals; approve/reject; nightly sweep |
| `cli.py` | commands above |

## Known limitations

- The Android fork is a partial port (M1/M3.2); this PC prototype is still
  the only place where the full flywheel has worked end-to-end.
- Dynamic screens still break replay: run #9 replayed a Bilibili pipeline 6/6,
  but run #10 failed on the next launch because the app resumed in a
  different state. Per-step `assert`/retry/fallback is the next gap.
- Replay-K evaluation exists as a manual `evaluate` command; wiring it as a
  hard promotion gate (and using MaaFW Record/Replay/Dbg or MaaMCP
  `benchmark_node` for cheaper passes) is the next step.
- Automatic element healing (turning failed `element` recognition into
  `element_fix` proposals with new ROI/template/benchmark evidence) is not
  automated yet.
- `verified=0` pipelines still replay but are not proven; only postconditioned
  pipelines can fail loudly.
- Toggle actions are not idempotent: the agent is prompted to check state
  first, but a pipeline learned from a "liked" screenshot must start unliked.
- `file_count`/`pixel` verifiers are prototype device checks; the Android
  fork maps them to MaaFW `Custom`/Agent nodes.
- ADB `input text` is ASCII-only; use ADBKeyboard for CJK text.
- This is a test harness, not the product: the on-device Android fork
  replaces the device adapter with MaaFramework's runner.
