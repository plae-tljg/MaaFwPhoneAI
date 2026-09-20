# Goal and central idea

## 1. One sentence

> **Build an on-device Android assistant whose automation knowledge is a
> small database the AI maintains: deterministic verified replay first, the
> vision model only for the unknown, a human on the publish gate, and every
> run turned into evidence for the next maintenance pass.**

`maa-phone` is not "an AI that clicks the phone every time". It is a
knowledge system that absorbs what the AI discovers. The MO is:

```
unknown goal ──► AI solves it once (screenshots + model)
              ──► run recorded and verified
              ──► AI proposes a reusable pipeline / locator / hint / policy
              ──► human approves in Review
              ──► next time: deterministic MaaFramework replay, 0 AI tokens
              ──► failures become healing evidence, not mysteries
```

## 2. The problem being solved

Phone automation today is usually one of:

- **Hardcoded scripts/pipelines** — fast and free, but every UI change needs
  a developer, and there is no daily maintenance loop.
- **LLM agents** — flexible, but expensive, slow and non-deterministic on
  every single run; "it clicked" is not the same as "the goal happened".
- **Mature MAA workflows** — excellent recognition/action substrate and
  authoring tooling, but static files and no self-maintaining knowledge.

The user's own framing (from the `chatbot-ai` experience):

> *let hardcoding parts be data and really let AI be the maintainer … only
> daily maintain [it] by changing tables … reduce the number of times we
> fall back to real AI … providing the best freedom for AI to maintain on.*

## 3. Central idea — the two-part contract

The project does **not** put arbitrary logic in a vague "AI memory". It uses
a deliberately split contract:

1. **A small, stable engine vocabulary** (run states, step/native node
   format, locator kinds, verifier kinds, proposal kinds, message kinds,
   sources, autonomy, policies). Code only *interprets* these.
2. **Free-structured rows with JSON payloads** for everything the product
   *knows or does* (`apps`, `pipelines`, `elements`, `runs`, `proposals`,
   `messages`, `settings`, `decision_cache`, `policies`, `eval_*`).

New behaviour = a row of the existing vocabulary first. A genuinely new
primitive is a complete slice: table/column + interpreter branch + proposal
publisher + validation + test + forward-only migration + one vocabulary
line. See [`WORKING_STYLE.md`](WORKING_STYLE.md).

### The five pillars

| Pillar | Meaning in maa-phone |
|---|---|
| **Knowledge is data** | A workflow is a **native MaaFW pipeline graph** (recognition/action/`next`/`on_error`) stored in a row; locators, app aliases, hints, safety gates and thresholds are likewise rows the AI can propose and edit. |
| **Deterministic-first** | Exact/substring/fuzzy alias resolution, then `{app}` patterns, then replay. Weak matches fall through; no guessing. |
| **AI proposes, human approves** | The AI lands changes in `proposals`; Review publishes them. Nothing AI-authored is live first. |
| **Verification and evidence** | Every run stores state, trajectory, postcondition result and `verify_json`; "all nodes matched" ≠ "the goal happened". First-time bootstrap runs use the LLM verifier with retries/fresh screenshots; once consolidated, a pipeline must use a deterministic local check. |
| **IO is one message stream** | Goals, questions, answers, status cards, proposals and results are `messages` rows linked to runs. Queue / Do now / Cancel / pause are mechanics of that stream. |

## 4. What we do (scope)

| Area | We own | Mature tool we do **not** replace |
|---|---|---|
| Knowledge DB | schema, migrations, AI-readable rows, provenance, stats | — |
| Deterministic brain | resolver, compiler, step/pause model, learning loop, Review publishers | — |
| Verification | postconditions (`pixel`, `element`, `screen_text`, `file_count`), evidence, debug bundles | MaaFW `Custom`/Agent callbacks for on-device verifiers |
| IO | message stream, queue, questions, approvals, Assistant UI | MaaFwApp notification/overlay primitives |
| Workflow authoring | import/export, Review, replay and provenance | **MaaMCP + Everything-Maa** (observation, OCR/ROI/template sweeps, benchmark, coordinate hygiene) |
| Recognition/action | store native MaaFW fields 1:1; never reimplement OCR/matching/ROI math | **MaaFramework** |
| Android privileged runtime | thin binding through the existing runner contract | **MaaFwApp fork** (Shizuku/root, native controller, virtual display, overlay, logs) |
| Bootstrap exploration | an on-device screenshot/model loop for first contact only | — (explicitly not the final workflow author) |

## 5. What we deliberately do **not** do

- Do not fork or patch **MaaFramework**; use its extension points and native
  field protocol.
- Do not embed **MaaMCP** in the APK or run it in the recognition hot loop;
  it stays a PC/dev-time authoring bench behind a process/tool boundary.
- Do not build a second MCP tool surface or a coordinate-clicking agent as
  the product's workflow builder. The on-device DeepSeek loop is a
  bootstrap; point trajectories are not the intended final artifact.
- Do not let AI write live rows. Proposal → Review is the only publish
  path (low-risk auto-approval is a later opt-in per kind).
- Do not hide behaviour in code the AI cannot reach. If the AI needs to
  maintain it, it must be a row of a documented vocabulary.

## 6. MAA stack at a glance

| Project | Role | How maa-phone uses it |
|---|---|---|
| **MaaFramework** (MaaFW) | mature recognition/action engine | Python binding in `proto/`; prebuilt `.so` in the Android fork; native pipeline fields stored 1:1; compiler only resolves refs/defaults/validation |
| **MaaFwApp** | Android privileged shell | forked into `android/`; reuses Shizuku/root runner, native controller, virtual display, overlay, logs; adds the `brain/` and Assistant |
| **MaaMCP** | PC authoring / pattern-extraction / benchmark bench | reference + pipeline import/export contract; not in the APK; the actual Bilibili authoring pass is the next integration step |
| **Everything-Maa** | veteran Maa authoring skills | 8 relevant skills vendored under `docs/maa-skills/` (MIT); drives `AUTHORING.md` |

See [`MAA_STACK.md`](MAA_STACK.md) for pins, exact integration state and
license boundaries.

## 7. Success criteria for v0

A "done" demo is not "the model tapped a button". It is:

1. A goal unknown to the DB is solved by the AI **or authored with MaaMCP**.
2. The result is **verified**: first-time AI runs are model-verified with
   retries and evidence; after consolidation the pipeline uses a
   deterministic check (pixel/element/file/screen). A model verdict alone is
   never the long-term replay gate (ADR-025).
3. The AI files a **proposal** with the native workflow or reusable rows.
4. A human approves it in Review.
5. The same goal later runs **deterministically on MaaFramework**, with
   `path='pipeline'`, `ai_cost=0`, and the postcondition passing.
6. When the app changes, failed runs/debug evidence drive a **healing**
   proposal instead of a hidden regression.
7. The whole loop runs **on the phone** (PC is only the authoring/build
   bench).

## 8. One concrete example

Goal: *open Bilibili, search 114514, open the first video, tap like.*

- **PC prototype (proven):** AI run 33.6k tokens → pixel-verified liked →
  approved pipeline → replay 6/6 native nodes, 0 tokens, pixel-verified.
- **Android (not yet successful):** the bootstrap run looped on repeated
  coordinate taps in one run; in the next it actually liked the video but
  was false-negatived by the LLM verifier, so no proposal was saved. The
  correct next moves are a deterministic local postcondition for toggles and
  authoring the workflow the Maa way with MaaMCP + Everything-Maa (OCR
  search box, OCR search text, template video card, template/color like icon
  with ROI and a postcondition), then import, approve and replay.

## 9. Where to go next

- Current state and exact gaps: [`STATUS.md`](STATUS.md).
- The method: [`WORKING_STYLE.md`](WORKING_STYLE.md).
- The human interface: [`IO.md`](IO.md).
- Integration: [`INTEGRATION.md`](INTEGRATION.md) and
  [`MAA_STACK.md`](MAA_STACK.md).
- Authoring: [`AUTHORING.md`](AUTHORING.md).
- Debugging: [`DEBUGGING.md`](DEBUGGING.md).
