# Prototype status and test results

The PC-side prototype (`proto/`) is the behavioral reference for the brain:
same schema, resolver, compiler, learning loop and interaction states. It now
has two engines: **`maafw`** (default, real MaaFramework pipeline JSON + MaaFW
OCR/TemplateMatch over ADB) and **`adb`** (the original raw-ADB Phase A
harness). See `proto/README.md` for usage.

> **Status note:** this document records the prototype (PC + USB ADB). It is
> the proven behavioral reference, not the product runtime. The Android
> fork has a partial port (M1/M3.2) and the on-device flywheel is not closed
> yet. See [`STATUS.md`](STATUS.md) and [`DEBUGGING.md`](DEBUGGING.md).


## Phase B — MaaFW-native runner (2026-09-19/20)

After the ADB prototype proved the flywheel, the execution substrate was
replaced with **MaaFramework** (the whole point of the project): pipelines
compile to real MaaFW pipeline JSON, recognition uses MaaFW OCR /
TemplateMatch (PP-OCRv5 + ONNX), and actions go through the native
controller. `--engine maa` is the default; `--engine adb` keeps the Phase A
harness.

### Pipeline

```
goal ─► resolver ─► compiler (MaaFW nodes) ─► Runner (one node per pause boundary)
                                      │               │
                                      │        MaaFW Resource/Tasker/AdbController
                                      │               │
                                      └──── postcondition verifier ◄── screenshot/OCR/pixels
                                                      │
                                          runs.verified = 0|1 + evidence
```

`compiler.compile_maa_steps()` turns each DB step into a node:

| DB step | MaaFW node |
|---|---|
| `launch` | `DirectHit` + `StartApp` |
| `tap` + `element(ocr)` | `OCR` (`expected` regex) + `Click` |
| `tap` + `element(template)` | `TemplateMatch` + `Click` |
| `tap` + point | `DirectHit` + `Click` at raw device coords |
| `text` / `key` / `swipe` / `wait` | `InputText` / `ClickKey` / `Swipe` / `DoNothing` |
| `element` reference | resolved from `elements.locator_json` at run time |

Every run writes a debug bundle (`data/runs/<id>/`): `events.jsonl`,
`manifest.json`, `screenshots/`, MaaFW `maafw.log`, and optional
`draws/`. `trace <id>` prints the timeline; `export <id>` produces a zip.

### Postconditions (this is what makes replay trustworthy)

MaaFW matching alone cannot tell "tapped the shutter" from "took a photo".
A pipeline now carries `postcondition_json`, checked after its steps; failure
fails the run, so a replay can never silently "succeed".

| Verifier | Meaning | Cost |
|---|---|---|
| `file_count` | a device directory grew by `delta_min` (camera photo) | free |
| `element` | a shared OCR/TemplateMatch locator matches | free |
| `screen_text` | OCR finds specific text | free |
| `pixel` | mean colour around a raw point matches (like toggle pink) | free |

AI runs can be held to the same bar with
`goal ... --verify '{"type":"pixel",...}'`; a verified AI run's evidence is
carried into its proposal so replay uses the same check.

### Results (verified end-to-end on the Redmi / Android 15)

| Scenario | AI run | Deterministic replay |
|---|---|---|
| Camera "take a photo" | 5,107 tokens, 2 steps, 1 photo | run #2: 0 tokens, 8.9s, `verified=1` (49→50); run #3: 0 tokens, 9.0s, `verified=1` (50→51) |
| Bilibili 114514 → first video → like | run #8: 33,558 tokens, 9 steps, `verified=1` (pixel distance 3/255) | run #9: 0 tokens, 30.6s, all 6 nodes matched, `verified=1` (pixel distance 3) |

Negative evidence retained in the DB: run #5 claimed success but vision +
pixels showed the like never happened (`success=0`, proposal rejected);
run #7 toggled an already-liked video and failed the postcondition; run #10
failed replay at the OCR search-history step because Bilibili's UI state
differed on a second launch. This is the failure evidence the healing loop
needs next.

### Bugs found and fixed in Phase B

1. **JSON-mode DSML leak** — `deepseek-v4-flash` sometimes returned
   unparseable `DSML` tool syntax in `content` (run #4). Fixed by using
   **native tool calling** (`phone_action`) with JSON-mode fallback and
   strict action validation.
2. **Learned step 0 could bounce back to the home screen** — OCR-tapping a
   home-screen app label does not always launch it. Fixed by seeding the
   `apps` registry / known-app list so the agent prefers `launch package`.
3. **Repeated jittery taps defeated repeat detection** — 30/50px buckets
   split nearby points; replaced with a <=60px point comparison. The prompt
   now also forbids tapping a toggle twice.
4. **Point taps became bogus OCR targets** — a point inside a large OCR box
   was recorded as tapping that text (`NLG`, `1`). A point now binds to OCR
   only when it hits the centre of a reasonably sized box; otherwise it
   stays a point + screenshot and learns a template.
5. **Decorative glyphs learned as elements** — OCR targets like `●` or lone
   digits are dropped from trajectories.
6. **Verified-run evidence was overridden** — an AI-proposed postcondition
   could replace the pixel check that actually passed; the run's own
   verified evidence now wins.


## Phase C — data-contract work (2026-09-19/20)

After the integration model was written down (`WORKING_STYLE.md`, `IO.md`,
`INTEGRATION.md`), the prototype was widened from "MaaFW runs rows" to the
full method:

- **Native MaaFW fields** — `pipeline_model.py` normalizes legacy shorthand
  and validates/passes through `roi`, `roi_offset`, `order_by`, `index`,
  `replace`, `method`, `green_mask`, `target`/`target_offset`, node attrs and
  even full native node fragments. The compiler resolves element refs and
  defaults only (ADR-020).
- **MaaMCP bridge** — `pipeline_io.py` + `import-pipeline`/`export-pipeline`
  convert standard MaaFW pipeline JSON to `pipeline_new` proposals and live
  pipelines back to pipeline JSON for benchmarking/sharing (ADR-021).
- **Free knowledge maintenance** — proposal publishing is a kind→publisher
  registry; `hint` and `policy` rows are now AI-proposable and human-reviewed
  (ADR-017/018). `{app}` alias patterns expand against the `apps` registry.
- **Safety as data** — `pipelines.autonomy` is enforced; `policies` gate goals
  with no pipeline (payment/send/delete) through `match_json`/`action_json`.
- **IO stream** — `messages` rows record goals, questions, answers, results;
  the agent can `ask`, the run becomes `needs_input`, and `answer` resumes it
  from persisted `progress_json` (ADR-019).
- **Cost** — `decision_cache` stores an action keyed by goal + app +
  perceptual screenshot hash, so an unknown screen solved once is not paid
  for again (`cache --clear` to invalidate).
- **Trust** — `evaluate <pipeline> --times K` replays through the normal runner
  and records `eval_runs`/`eval_items` (success + postcondition per attempt),
  so promotion can require evidence instead of a single lucky run.

Current status: 34 unit tests pass; the live camera and Bilibili running
evidence from Phase B is unchanged.

## Phase A environment

- Redmi device, Android 15, connected via USB ADB (`adb devices` verified).
- Model: `deepseek-flash` (vision), API cost tracked per run in
  `runs.ai_cost`.
- Local recognizers: UIAutomator (free) and NCC template matching via
  scipy FFT (free).

## Results (2026-09-19/20)

### Scenario 1 — "open the camera app and take a photo"

| Phase | Path | Tokens | Time | Result |
|---|---|---|---|---|
| First time (AI vision) | `ai` | 5,918 | 34s | photo taken in DCIM/Camera |
| Learned proposal approved | — | — | — | pipeline live with 2 elements |
| Second time (deterministic) | `pipeline` | **0** | 13s | photo taken again |

The learned pipeline contains one `uiauto` element (`相机` by text) and two
`template` elements (shutter crops). Replay uses zero AI and does not call
the model at all.

### Scenario 2 — "open bilibili, search 114514, open the first video, tap like"

| Phase | Path | Tokens | Time | Result |
|---|---|---|---|---|
| First time (AI vision) | `ai` | 35,824 | 173s | video page opened, like verified `liked:true` by an independent vision check |
| Replay | — | — | — | not run in Phase A; **passed in Phase B** (run #9, verified) |

Bilibili **blocks UIAutomator** (`null root node returned by
UiTestAutomationBridge`), so this run had to navigate with point taps. That
is exactly the case template learning was built for, but the screenshots
needed were only added after this run.

### Flywheel mechanics verified

- AI success -> instant `pipeline_new` proposal (ADR-014).
- `learn` promotes repeated AI successes and flags failing pipelines.
- Approving materializes shared `elements` (uiauto + template) and a live
  `pipelines` row.
- Deterministic replay bumps `pipelines.success` and writes `runs.path='pipeline'`
  with `ai_cost=0`.

## Phase A bugs found and fixed

1. **Stale UIAutomator dumps** — when the camera app was foreground, the
   dump command failed but the old XML stayed on disk, so the model read
   home-screen nodes while looking at camera screenshots (trajectory got a
   bogus "Firefox" target). Fix: delete the dump file first and reject
   output without `<hierarchy>`.
2. **Replay too fast** — deterministic steps ran 0.4s apart while the AI run
   had multi-second model latency between actions; replay tapped the home
   screen instead of the just-opened camera. Fix: record a per-step
   `delay` (2s for taps/text/keys) and add load-wait retries in the executor.
3. **Featureless templates** — cropping 110px around the shutter landed
   inside a pure-white circle, producing a template that matches any white
   area. Fix: 220px crops.
4. **Loading-screen captures** — the turn-start screenshot was taken while
   the camera was still launching, so the learned crop was the loading
   screen. Fix: re-capture the screenshot at tap time for point taps.
5. **Empty model replies on complex screens** — `deepseek-flash` reasoning
   consumed the 2,000-token budget, leaving no JSON. Fix: 4,000-token
   budget plus one JSON-repair retry.

## Phase A — what is proven

- Deterministic-first: known goals never reach the model.
- AI-propose / human-approve / system-execute with shared, healable
  elements.
- Promotion flywheel: AI cost per task trends to zero; camera went from
  5,918 tokens to 0 on the second run.
- Local recognizers make learned pipelines replayable without AI, including
  apps that block accessibility dumps (via templates).

## Next steps

The prototype's open gaps are now cross-referenced with the Android plan in
[`STATUS.md`](STATUS.md):

1. **Dynamic-screen replay robustness** (biggest prototype gap): run #9
   replayed 6/6 nodes but run #10 failed because the second launch showed a
   different Bilibili state. Needs per-step `assert`/retry, a search-history
   fallback (type the query), and branching/overrides before dynamic pipelines
   can be trusted.
2. **Element healing loop**: turn failed `element` recognition into
   `element_fix` proposals automatically from the debug bundle (the schema,
   hits/misses counters and Review queue already exist).
3. **Decision cache** is already implemented; the remaining work is to use
   it in the Android bootstrap and measure hit rate.
4. **Android fork**: M0/M1/M3.2 are built; next is the MaaMCP +
   Everything-Maa authoring pass, native import + verified replay, then M2
   queue/pause and on-device verifiers (see `FORK_DELTA.md`, `STATUS.md`).
5. Run the nightly `learn` sweep over verified runs and auto-reject
   unverified AI runs (negative evidence) so the Review queue only shows
   proposals with real evidence.
6. Use [`DEBUGGING.md`](DEBUGGING.md) as the runbook when a live replay
   fails; preserve the bundle and turn it into a healing proposal.
