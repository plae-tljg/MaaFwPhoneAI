# IO mode — how the human and the assistant share the phone

The IO is not a UI detail on top of the brain. It is the second half of the
method: goals, questions, approvals and evidence are **messages in one
stream**, and every execution is attached to it. The "message panel" idea
from the original conversation is the product surface; queues and run
states are its mechanics.

> **Implementation status:** the PC prototype implements the stream and its
> states (`messages`, `needs_input`, `answer`, queue, pause/resume,
> Review). The Android Assistant currently implements the message/DB
> screens, live preview, Review and Settings; tile / notification / float
> ball entry points and Android step-wise queue/pause are still M2 work.
> See [`STATUS.md`](STATUS.md) §3.

## 0. The mental model

> The user talks to a **task queue that looks like a message thread**.
> Each goal is a message. The assistant may answer with a question, a
> status, a result card or a "save this as a pipeline?" card. Runs are the
> execution attached to those messages.

This is the phone translation of chatbot-ai's
`conversations` + `messages` + `flow_states` + the operator Review console:
one place where the human interacts, one place where the AI-maintained
knowledge becomes visible and approvable.

The user's example, made concrete:

1. A game is being automated in background mode (virtual display).
2. The user swipes the shade, taps **Maa-phone**, and a prompt sheet opens
   over the game.
3. They type *"send a message to Anna saying I'm late"*.
4. The message enters the thread as `role=user, kind=goal`.
5. The resolver checks live pipelines. On a miss the sheet answers:
   `No pipeline yet — let the AI do it? [Queue] [Do now] [Cancel]`.
6. **Queue** (default after 10s) keeps the game running; **Do now** pauses
   it after the current step, starts the message, and auto-resumes the game
   afterwards; **Cancel** dismisses.
7. The AI drives the phone, asking a `question` message if it needs
   clarification ("which messaging app?"). The run waits in `needs_input`.
8. On success the thread gets a **result card** with the evidence, and a
   **"Save as pipeline? Review"** card files a pending proposal.
9. Approving in Review makes the next identical goal deterministic and free.

## 1. One entry point, many surfaces

Every surface calls exactly one function:

```
enqueue_goal(text, source, context) -> message_id, run_id?
```

| Surface | When it appears | Notes |
|---|---|---|
| Quick Settings tile | anywhere, even over a game | opens the prompt sheet; low cost |
| Persistent notification | while a run is active | New task / Pause / Stop / Resume |
| Float ball / overlay panel | over any app | reuses MaaFwApp overlay |
| In-app Assistant screen | onboarding, history, queue, Review | the full console |
| Share sheet | share text/link/image into a goal | v0.1 |
| Voice mic | dictate the goal | v0.1 |
| Scheduled goal | cron-like triggers | `source=schedule` |

No surface has private logic. `runs.source` records which door was used;
the message thread records what was said.

## 2. The message stream

The stream is the human side of the DB. A minimal row shape:

```sql
messages(
  id, run_id NULL, role,        -- user | assistant | system
  kind,                         -- goal | question | answer | status | card | proposal | result
  content,                      -- human-readable text
  payload_json,                 -- structured data (options, proposal id, evidence, run state)
  state,                        -- pending | sent | answered | dismissed | expired
  source,                       -- in_app | tile | notification | float_ball | schedule
  created_at, answered_at
)
```

Rules:

- Every `enqueue_goal` creates a `goal` message and (unless it is queued
  behind another) a `run` linked to it.
- Assistant output is messages too: `question`, `status`, `result`, `card`.
- The AI never edits the stream directly; the **runner** and **reviewer**
  append to it from data (`runs.state`, `proposals`, verifier evidence).
- `needs_input` is persisted here, not in memory: the question message +
  `runs.progress_json.question` + `runs.state='needs_input'` survive process
  death and reboot.
- The Android Assistant screen is a rendering of this stream; the CLI can
  render it as `maa_phone thread`.

This is the direct analogue of chatbot-ai `messages` and the operator
console's Chat history + Review pages, unified into one thread per device.

## 3. Run lifecycle (the execution attached to a message)

```
queued ──► running ──► done | failed
             │  ▲
             ▼  │ answer
          needs_input (question message)
             │
           paused (between steps; progress_json)
             │
         cancelled (Stop / Cancel)
```

- **One active run per device**; everything else queues (`state='queued'`,
  ordered by id).
- **Queue is the safe default** when a new goal arrives during a run; never
  silently drop or reorder the running work.
- **Do now** = pause the active run *after its current step* → start the new
  goal → auto-resume the paused run when it finishes.
- **Pause** finishes the current compiled step and persists
  `runs.progress_json` (`{step_index, vars, question}`).
- **Stop** cancels immediately; queued and paused runs are visible and
  resumable.
- Pipelines execute in controlled graph segments because that is
  the only clean pause boundary MaaFramework permits.

## 4. Questions (`needs_input`) and clarifications

The AI fallback and deterministic pipelines may need information:

- The agent step `{"action":"ask","question":"...","options":[...]}` (v0.1)
  creates a `question` message, sets `runs.state='needs_input'` and
  `progress_json.question`.
- The user's answer creates an `answer` message, fills the pending slot and
  resumes the run (`resume`), or is routed as a new goal if it is unrelated.
- This is the phone analogue of chatbot-ai's `flows` + `flow_states`: the
  question/answer shape is data; a later `flows` primitive can compose
  several of them deterministically, and the AI can propose flows through
  Review.

Until v0.1, the prototype uses `--pause-after` + `resume` and in-memory
prompts; the Android fork should ship the persisted form directly.

### 4.1 Chat/modal UI (opencode/Codex-like)

The human surface should feel like a chat/agent room, not a form:

- the assistant can post a **question card** with options and a free-text
  field, e.g.
  `I found 3 finance apps. Which one? [Finance Manager] [Budget] [Other…]`;
- choosing an option or typing an answer writes an `answer` message and
  resumes the run from `needs_input`;
- plan/approval cards show the proposed steps and offer **Approve / Edit /
  Cancel**;
- run traces, evidence and skill-loading notes appear in the same thread;
- the user can always interject a new goal, which follows the
  queue/do-now/cancel rules.

This is the UI implementation of the `messages` table; it is tracked as
P1-8 in [`TODO.md`](TODO.md).

## 5. Learning at the surface

The maintenance loop must be visible where the user already is:

| Card / screen | Trigger | Action |
|---|---|---|
| **Save as pipeline? Review** | AI run succeeded (verified or human-confirmed) | creates a `pipeline_new` proposal; opens Review |
| **Pipeline failed** | run failed or postcondition failed | shows evidence; offers "let AI recover" / "fix element" |
| **Review queue** | pending proposals | approve / reject / edit; bulk approve for low-risk kinds |
| **Run history** | any run | trace, screenshots, evidence, cost, replay |
| **Learn summary** | nightly sweep | what was proposed / healed / aliased |

Nothing AI-authored runs live before this gate. Review is the human IO of
the learning loop, exactly as `extractions`/ReviewService is in chatbot-ai.

## 6. First run (empty-first)

1. Permission wizard: notifications → overlay → battery whitelist →
   Shizuku/root → model key.
2. Empty thread with example goals ("turn on dark mode", "open camera and
   take a photo", "message Anna on WhatsApp").
3. An optional demo pipeline loaded through the **real** proposal/review flow,
   so the user sees the loop on day one.
4. No hidden onboarding logic: the wizard writes settings rows.

## 7. Multiple tasks and compound goals

- v0: one goal per prompt; several prompts queue; the thread shows what is
  next; reorder/cancel is allowed.
- v0.1: the AI may reply with a clarification question before acting
  (needs_input) — this covers most "multiple tasks" cases.
- Later: a deterministic splitter/flow (chatbot-ai's message decomposition)
  can turn "message Anna **and** take a photo" into two goals; until then
  it is two messages, and that is honest.

## 8. Safety IO

- **Confirm**: risky goals (`pipelines.autonomy='confirm'`, or a matching
  policy for unknown goals) produce a plan card ("I will open Settings →
  Display → Dark mode") with Approve / Cancel.
- **Never**: `autonomy='never'` and deny policies refuse and explain.
- **Audit**: every run, question, answer, approval and source is a row;
  notification actions map to state transitions, not hidden side effects.
- The phone analogue of chatbot-ai's `policies` + `capabilities` is a small
  `policies(match_json, action_json)` table (see `WORKING_STYLE.md` §6),
  because unknown goals have no `pipelines.autonomy` row to consult.

## 9. Current implementation map

| IO piece | Prototype (`proto/`) | Android fork |
|---|---|---|
| `enqueue_goal` | `maa_phone goal` + `process-queue`; goal message row | Assistant in-app input **works**; tile / notification / float ball are **planned (M2)** |
| queue / interrupt | `--interrupt ask\|queue\|now\|cancel` **works** | prompt sheet is **planned (M2)** |
| pause / resume / answer | `--pause-after`, `resume`, `answer`, `progress_json` **works** | Assistant exposes runs/messages; **step-wise pause + resume are not wired yet** |
| needs_input | `ask` action -> `runs.state='needs_input'` + persisted `progress_json`/`messages` **works** | persisted question/answer messages exist; UI flow is **planned** |
| thread | `messages` table + `maa_phone thread` **works** | Assistant thread/message view **works** (read-oriented) |
| review cards | `proposals`, `approve`, `reject` **works** | Review tab with Approve/Reject **works** |
| learning cards | `learned: proposal #…` | instant AI-success proposal **works** when the bootstrap verifies |
| onboarding | `.env` + CLI | permission prompts + Settings tab; full wizard **planned** |
| safety confirm | `safety.check`/`check_goal` + `policies` rows + `--yes` **works** | policy/autonomy prompt is **planned** |

The gaps in this table are the IO work items, and they are exactly the
places where the AI-maintained DB needs a human-facing message row rather
than a hidden CLI state. The Android Assistant is already a rendering of the
same data (messages, runs, proposals, settings), so the remaining work is
surfaces and execution semantics, not a new data model.
