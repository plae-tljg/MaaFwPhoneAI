# Documentation index

Read in this order.

| Order | Document | Why |
|---|---|---|
| 1 | [`GOAL.md`](GOAL.md) | one-page goal, central idea, what we do and do not do |
| 2 | [`CONVENTIONS.md`](CONVENTIONS.md) | **vocabulary contract**: pipelines vs skills vs tools |
| 3 | [`WORKING_STYLE.md`](WORKING_STYLE.md) | the AI-maintained free-structured DB method and extension rule |
| 4 | [`IO.md`](IO.md) | the human interface: one message stream, queue, questions, Review |
| 5 | [`MAA_STACK.md`](MAA_STACK.md) | exact roles/pins of MaaFramework, MaaFwApp, MaaMCP, Everything-Maa |
| 6 | [`INTEGRATION.md`](INTEGRATION.md) | field-level integration contract and ownership matrix |
| 7 | [`AUTHORING.md`](AUTHORING.md) | the veteran Maa pipeline-authoring flow (MaaMCP tools + Everything-Maa skills) |
| 8 | [`DESIGN.md`](DESIGN.md) | the concrete v0 architecture (resolver, compiler, learning, safety) |
| 9 | [`STATUS.md`](STATUS.md) | current implementation state, evidence, APKs, known gaps |
| 10 | [`M9A_REFERENCE.md`](M9A_REFERENCE.md) | mature Maa project structure/patterns to imitate |
| 11 | [`DEBUGGING.md`](DEBUGGING.md) | evidence-first debugging playbook for every layer |
| 12 | [`PROTOTYPE.md`](PROTOTYPE.md) | PC prototype phases, live results, bugs fixed |
| 13 | [`HANDOFF.md`](HANDOFF.md) | how the work happened, session reconstruction, pitfalls |
| 14 | [`DECISIONS.md`](DECISIONS.md) | ADRs for every non-obvious design choice |
| 15 | [`FORK_DELTA.md`](FORK_DELTA.md) | what the MaaFwApp fork reuses/adds/changes, milestones |
| 16 | [`maa-skills/`](maa-skills/) | vendored Everything-Maa authoring skills (MIT) with `index.json` family lazy-loading |
| 17 | [`SKILL_LOADING.md`](SKILL_LOADING.md) | load one skill family, not all of Everything-Maa |
| 18 | [`SCHEMA.md`](SCHEMA.md) | fresh v1 schema: pipelines, versions, missions, run evidence |
| 19 | [`TODO.md`](TODO.md) | current status ledger and prioritised task list |

AI maintainers should also read [`../AGENTS.md`](../AGENTS.md) for the hard
rules, then this index. Companion code entry points:

- [`../schema.sql`](../schema.sql) — the data contract in concrete tables.
- [`../proto/README.md`](../proto/README.md) — PC reference brain.
- [`../android/MAA_PHONE.md`](../android/MAA_PHONE.md) — Android fork status and build.
