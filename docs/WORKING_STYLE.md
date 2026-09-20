# The working style — AI-maintained knowledge in a free-structured DB

Read [`GOAL.md`](GOAL.md) first for the one-page goal and
[`CONVENTIONS.md`](CONVENTIONS.md) for the vocabulary contract. Short
version: **pipelines/workflows are the runtime state machines stored as
data; skills are authoring instructions for the AI agent**. This document
uses the target names (`pipelines`); see
[`SCHEMA.md`](SCHEMA.md) for the fresh schema (no legacy aliases). Implementation state lives in [`STATUS.md`](STATUS.md);
layer-by-layer failure triage lives in [`DEBUGGING.md`](DEBUGGING.md).

## 0. The bet

> **Behaviour should be rows the AI can author, review and maintain — not
> code, and not a prompt paid for on every run.**

The engine is a small, stable **interpreter**. The AI's job is to maintain a
**knowledge base**: goals, aliases, workflows, screen locators, app notes,
safety gates, answers to clarify. A human approves. Deterministic execution
serves the traffic; the LLM is only a fallback for the unknown and a
maintainer for the ambiguous. Each unique task gets solved by AI once, then
replayed for free forever.

This is the method ported from `../../00ref/chatbot-ai`:

> *"hardcoded behavior becomes rows; AI maintains the rows; deterministic
> lookup answers so the LLM is rarely called."* — the v0 design conversation

The user's own framing:

> *"let hardcoding parts be data and really let ai be the maintainer … only
> daily maintain [the product] by changing table … reduce number of times
> fallback to real ai that then cut cost … providing best freedom for ai to
> maintain on."*

## 1. The contract: stable vocabulary, free data

The trick is not "put everything in a DB" (that becomes EAV chaos or 30
tables nobody maintains). It is a two-part contract:

### 1a. The engine vocabulary is small, fixed and versioned

Code only knows how to *interpret*. New vocabulary is rare and deliberate.

| Vocabulary | Values | Where |
|---|---|---|
| run states | `queued \| running \| paused \| needs_input \| done \| failed \| cancelled` | `runs.state` |
| message kinds (IO) | `goal \| question \| answer \| status \| card \| proposal \| result` | message stream |
| pipeline format | **native MaaFW pipeline graph** (nodes with recognition/action/`next` etc.) stored as data; legacy action steps remain as a compatibility/bootstrap form | `pipelines.definition_json` (+ `entry`) |
| locator kinds | native MaaFW recognition (`OCR \| TemplateMatch \| FeatureMatch \| ColorMatch \| NeuralNetwork… \| DirectHit`) or legacy shorthand `ocr \| template \| point` | `elements.locator_json` |
| verifier kinds | `file_count \| element \| screen_text \| pixel \| none` | `pipelines.postcondition_json` |
| resolver modes | exact > substring > fuzzy, confidence gate | `resolver.py` + settings |
| proposal kinds | `pipeline_new \| pipeline_fix \| element_fix \| alias \| hint \| policy` (+ future `pattern`, `flow`) | `proposals.kind` |
| run sources | `in_app \| tile \| notification \| float_ball \| share \| schedule` | `runs.source` |
| autonomy gates | `auto \| confirm \| never` | `pipelines.autonomy`, policies |
| safety gates | allow / confirm / deny for unknown and risky goals | policies (v0.1) |

### 1b. The data is free-form rows with small JSON payloads

The AI composes vocabulary into behaviour without touching code:

| Knowledge | Row + payload |
|---|---|
| apps + name aliases | `apps.aliases` |
| goal patterns with slots | `pipelines.aliases` (`open {app}`, `{app}里打开{setting}`) |
| a workflow | `pipelines.definition_json` + `pipelines.entry` — a native MaaFW pipeline graph (nodes with recognition/action/`next`/`on_error`) or, for legacy/bootstrap rows, ordered action fragments |
| a screen locator | `elements.locator_json` (native MaaFW fields: OCR `expected`/`roi`/`replace`, TemplateMatch `template`/`threshold`/`method`, `order_by`/`index`, …) |
| app-specific notes / UI layout | hints rows (today `settings.hint:<pkg>`; AI-proposable via `proposals.kind='hint'`) |
| how to know it really worked | `pipelines.postcondition_json` |
| who may run what unattended | `pipelines.autonomy` + policies |
| thresholds, model, retention | `settings` |
| AI proposals awaiting a human | `proposals.candidate_json` |
| everything the system did | `runs` + debug bundle + evidence |
| the IO stream | `messages` (goal/question/answer/result) |
| unknown-goal safety gates | `policies.match_json` -> allow/confirm/deny |
| an action already paid for | `decision_cache` (goal + app + screenshot phash) |
| replay trust evidence | `eval_runs` + `eval_items` |

### 1c. The extension rule

> **New behaviour = a row of an existing vocabulary first. New code is the
> last resort, only when a genuinely new primitive is required.**

When a new primitive *is* required, it ships as a complete slice:

1. one table (or one column on an existing table) with a JSON payload;
2. one interpreter branch (e.g. a new step action or verifier type);
3. one proposal kind + one Review publisher (so the AI can author it);
4. validation + a test + a forward-only migration;
5. one line in this document's vocabulary table.

If a change cannot name which vocabulary item it extends, it does not belong
in the system yet.

## 2. The maintenance loop is the point

```
        real usage
            │
            ▼
   runs + events + evidence          ── the learning dataset
            │
   ┌────────┴─────────┐
   │ instant          │ nightly sweep
   │ "save as pipeline?" │ promote / heal / alias / policy / hint
   └────────┬─────────┘
            ▼
      proposals (pending)  ── one Review queue, human-gated
            │ approve / reject / edit
            ▼
   rows live in the DB
            │
      deterministic replay ──► verified run (0 AI tokens)
            │
        stats / misses ──► next proposal (healing)
```

Nothing AI-authored is live before approval (except opt-in low-risk kinds).
Every live row keeps provenance: which run produced it, when it was
reviewed, how often it succeeded, what it cost.

The AI is the **frontier**: it solves the new task. The system absorbs what
is proven: replay, patterns, policies, hints. The frontier advances; the
cost per task falls.

## 3. Cost is an architectural constraint

Ordered by impact (all ported from chatbot-ai):

1. **Promotion** — a solved task becomes a deterministic row; repeat AI cost
   is zero.
2. **Deterministic-first** — exact/pattern matching before any model call;
   weak matches fall through, never guess.
3. **Local recognizers** — OCR/template/pixel checks run on-device; the
   model is never in the hot recognition loop.
4. **Decision cache** (v0.1) — perceptual screenshot hash + prompt hash →
   previous action; the `answer_cache` analogue.
5. **Evaluation before promotion** — replay a proposed pipeline K times; only
   trustworthy rows go live, so bad rows don't cause repeated AI recovery.

Telemetry is part of the design: `runs.ai_cost`, `duration_ms`, `path`,
`success`, `verified`, `pipelines.success/fail`, `elements.hits/misses`.

## 4. Trust is part of the method

An AI-maintained automation system is only as good as the evidence for its
rows:

- **Postconditions** (`runs.verified` + `verify_json`): all recognizers
  matching is not success. For the first bootstrap run the LLM verifier is
  the temporary checker (retry with fresh screenshots and record evidence);
  once consolidated, the pipeline must use a deterministic local check
  (ADR-025).
- **Debug bundles**: every run keeps events, screenshots/draws and the
  MaaFW log, so a failure is a learning input, not a mystery.
- **Review**: proposals show the human what will become live, in human
  terms, with the evidence attached.
- **Audit**: runs, sources and approvals are rows; nothing silently
  self-modifies.

## 5. Mapping from chatbot-ai (the proof of the method)

| chatbot-ai | Role in the method | maa-phone analogue |
|---|---|---|
| `faq` (plain + slot patterns in one table) | uniform single-turn knowledge surface | `pipelines` + `aliases`; patterns/slots still to finish |
| `rules` (`match_json`/`action_json` DSL) | routing and action as data | resolver modes (today code) + policies (v0.1) |
| `flows` + `flow_states` | multi-turn clarification as data | `needs_input` + question/answer; flows v0.1 |
| `products.attrs_json` | per-domain flexibility without EAV | `elements.locator_json`, app hints |
| `extractions` + ReviewService | AI proposes, human publishes | `proposals` + `learn.approve` (publishers should become a registry) |
| `maintenance.propose` | nightly fallback → knowledge | `learn.nightly` |
| `answer_cache` | LLM cost control | decision cache (v0.1) |
| `tasks` / `task_events` / `policies` / `formulas` | generic process primitives | pipelines/runs/autonomy; policies worth adding; formulas not needed on a phone |
| `conversations` / `messages` | IO and session memory | `runs` + CLI today; a message stream is the next IO primitive |
| `eval_runs` / evaluator | trust and regression | postconditions + manual replay-K (`evaluate`); promotion gate next |
| Django console (Review/Data/Test) | the human IO of maintenance | Android Review/Assistant screens; CLI today |

## 5b. Concrete DB tactics borrowed from chatbot-ai

These are the mechanics that make the contract real rather than aspirational:

1. **One uniform surface per knowledge type.** chatbot-ai keeps plain FAQ
   and slot patterns in the same `faq` table (slotless = plain), so there is
   one AI/human-editable surface. maa-phone should do the same: aliases and
   `{app}` patterns in `pipelines`, not a second "patterns" table.
2. **Typed columns for queries/constraints, JSON for domain flexibility.**
   `products.attrs_json` avoids EAV while allowing per-category fields;
   `faq.action_json` routes (answer/escalate/agent) as data. In maa-phone:
   `locator_json`, `steps_json`, `postcondition_json`, `progress_json`.
3. **A deliberately tiny DSL per primitive.** chatbot-ai rules use
   `match_json`/`action_json` with a handful of recognised keys ("a rule
   whose match has no recognised key must never match"). maa-phone's step
   actions, locator kinds and verifier kinds are the same idea. The DSL is
   the *vocabulary* the AI is allowed to compose.
4. **One Review queue with kind→publisher.** `extractions` rows have a
   `kind`, and `ReviewService` publishes each kind transactionally,
   reporting duplicates as `skipped`, never 500. maa-phone's
   `proposals.kind` + `learn.approve` is a smaller version of this; the
   hardcoded `if/elif` should become a registry so a new knowledge kind is
   one publisher, not a new code path.
5. **AI prompts prefer patterns over enumeration and forbid invention.**
   chatbot-ai's extractor says "prefer a PATTERN row with slots over one
   entry per product" and "NEVER invent prices". The phone's learning prompt
   should say the same: prefer `{app}`/`{setting}` patterns over one pipeline
   per phrase, never invent locator coordinates or postconditions.
6. **Soft delete + provenance + stats.** Every knowledge row has `active`,
   `created_at`/`updated_at`, and live counters (`hits/misses`,
   `success/fail`). This is what makes maintenance decisions possible.
7. **Forward-only migrations on a fresh-DB baseline.** `schema.sql` +
   numbered idempotent migrations + `settings.schema_version`; live data is
   never deleted to add a column. maa-phone already follows this in
   `db._ensure_columns` and should keep the discipline as the vocabulary
   grows.
8. **Cache and evaluate.** `answer_cache` (24h) is the direct ancestor of
   the phone's decision cache; `eval_runs`/`eval_items` are the ancestor of
   replay-K promotion tests. Both are cost/trust mechanisms, not extras.
9. **Scoping with scoped uniqueness.** chatbot-ai moved from a global SKU
   unique index to `UNIQUE(bot_id, sku)` after real bugs. maa-phone's
   analogues are `UNIQUE(app_id, name)` on elements and per-app hints; keep
   constraints scoped, not global.
10. **Scenarios as executable spec.** chatbot-ai's YAML scenarios + AI
    evaluator test whole workflows, not just units. maa-phone's demo should
    grow the same: a scenario file per user story (camera, Bilibili, dark
    mode), runnable against the phone.

## 6. Where maa-phone stands against the method

**Honours the method now (PC prototype in full; Android partial as noted in
[`STATUS.md`](STATUS.md))**

- deterministic-first resolver + zero-AI replay, proven live on the PC
  prototype (camera and Bilibili);
- AI proposes / human approves / system executes;
- pipelines, elements, aliases, steps, postconditions, autonomy, hints, native
  MaaFW fields and pipeline import/export are rows/JSON;
- `{app}` alias patterns resolve against the `apps` registry;
- `pipelines.autonomy` is enforced, and `policies` gate unknown risky goals;
- proposal publishing is a kind→publisher registry (`pipeline_new`, `alias`,
  `element_fix`, `pipeline_fix`, `hint`, `policy`);
- IO is a message stream: goals, questions, answers, results; `ask` ->
  `needs_input` -> `answer` survives restarts;
- a decision cache avoids paying twice for the same screenshot+goal;
- replay-K evaluation (`eval_runs`/`eval_items`) records trust evidence per
  attempt;
- verified AI-run evidence is reused as the proposed pipeline's postcondition;
- runs + evidence + debug bundles are the learning dataset;
- nightly promote/heal/alias loop exists (`learn.py`).

**Still code or manual (the gap the AI cannot freely maintain yet)**

- the Android build is through M1/M3.2: deterministic `open settings` works,
  the Assistant/import/settings UI exists, but the on-device AI bootstrap
  has not yet produced a verified reusable Bilibili workflow — the current
  plan is to author it natively with MaaMCP + Everything-Maa rather than
  trust point trajectories;
- Android does not yet execute step-wise for pause/queue, does not yet check
  postconditions locally, and does not yet record `ai_cost`; these are
  prototype-only or partial today;
- deterministic multi-turn `flows` (beyond one ask/answer) are not a
  primitive yet;
- replay-K evaluation exists but is not yet a hard promotion gate, and does
  not yet use MaaFW Record/Replay/Dbg or MaaMCP `benchmark_node`;
- automatic element healing (failed recognition -> `element_fix` with new
  ROI/template/benchmark evidence) is not automatic;
- dynamic-screen fallbacks (run #9 vs #10) need per-step alternatives;
- the Android brain is built through the M1/M3.2 bootstrap; reusable workflow
  authoring moves to MaaMCP + Everything-Maa (`AUTHORING.md`) and native
  pipeline import; the on-device DeepSeek loop stays a bootstrap, not the
  learning artifact. The actual authoring pass and on-device verified replay
  are the immediate next step (`STATUS.md`).

**Deliberately deferred** (ADR-010): formulas, embeddings, multi-device,
desktop console. The method says add them only when a real task forces it —
but when added, they must arrive as data-shaped primitives, not new bespoke
code paths.

## 7. The one-sentence version

**Build a small interpreter with a fixed vocabulary; let the AI own the
free-structured rows that compose it; keep a human on the publish gate;
make deterministic replay the default and the model the exception; treat
every run as evidence for the next maintenance pass.**
