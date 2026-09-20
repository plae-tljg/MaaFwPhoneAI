# AGENTS.md — instructions for AI maintainers

This repository is an AI-maintained phone-automation system. The point of the
project is the **method**: a small stable interpreter vocabulary + a
free-structured DB the AI maintains + human Review + verified deterministic
replay. Read these first, in order:

1. [`docs/GOAL.md`](docs/GOAL.md) — goal and central idea.
2. [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md) — **vocabulary contract**:
   pipelines are runtime state machines; skills are authoring instructions.
3. [`docs/WORKING_STYLE.md`](docs/WORKING_STYLE.md) — the method and the
   extension rule.
4. [`docs/STATUS.md`](docs/STATUS.md) — what is built/proven/broken now.
5. [`docs/MAA_STACK.md`](docs/MAA_STACK.md) — roles/pins of the MAA repos.
6. [`docs/DEBUGGING.md`](docs/DEBUGGING.md) — how to investigate failures.
7. [`docs/INTEGRATION.md`](docs/INTEGRATION.md) and
   [`docs/AUTHORING.md`](docs/AUTHORING.md) — the technical contracts.
8. [`docs/M9A_REFERENCE.md`](docs/M9A_REFERENCE.md) — the mature Maa
   project pattern to imitate for pipeline graphs.
9. [`docs/SKILL_LOADING.md`](docs/SKILL_LOADING.md) — read the skill family
   index first; load only the matching family.
10. [`docs/SCHEMA.md`](docs/SCHEMA.md) — fresh v1 schema: pipelines,
    versions, missions and run evidence.
11. [`docs/TODO.md`](docs/TODO.md) — current status ledger and priorities.

## Vocabulary rules

- A **pipeline/workflow** is the runtime MaaFW state-machine row.
- A **skill** is an authoring/debugging instruction for the AI agent
  (Everything-Maa docs). Do not confuse the two.
- Read `docs/maa-skills/index.json` first and load only the matching family;
  never load all skills by default.
- Follow [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md); the fresh schema uses
  `pipelines`, `pipeline_versions`, `missions` and `pipeline_id` names.

## Hard rules

- Do **not** hardcode new behavior in Kotlin/Python if it can be a row of the
  existing vocabulary (`schema.sql`). A new primitive is a complete slice:
  table/column + interpreter branch + proposal publisher + validation + test
  + forward-only migration + vocabulary line in `WORKING_STYLE.md`.
- Do **not** let AI-authored data run live. Proposal → human Review is the
  publish path.
- Do **not** fork/patch MaaFramework. Do **not** embed MaaMCP in the APK.
  MaaMCP + Everything-Maa are the PC authoring bench; the app imports native
  MaaFW pipeline JSON.
- Do **not** treat the on-device DeepSeek bootstrap as the final workflow
  author. Point trajectories are evidence for authoring, not production
  skills.
- A run is successful only when its real-world postcondition is observed
  with a deterministic local check. "All nodes matched", the model saying
  `done`, or a single LLM verifier verdict are not enough. Preserve
  false-success evidence; never weaken a verifier to make a demo pass.
- Keep docs truthful. When behavior changes, update `STATUS.md`,
  `WORKING_STYLE.md`/`DECISIONS.md` (if a primitive changed), and the
  relevant integration/authoring/debugging docs.

## Quick verification

```bash
# PC prototype: the behavioral reference
cd proto && .venv/bin/python -m pytest tests/ -q

# Android: compile/build (host-specific env in android/MAA_PHONE.md)
cd android && ./gradlew :app:compileDebugKotlin
```

Android runtime evidence and commands are in
[`android/MAA_PHONE.md`](android/MAA_PHONE.md) and
[`docs/DEBUGGING.md`](docs/DEBUGGING.md). Never distribute a debug APK that
contains an embedded API key.
