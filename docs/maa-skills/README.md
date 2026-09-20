# Vendored Maa authoring skills

> **Load these lazily.** Start with [`index.json`](index.json): pick the
> family that matches the current authoring/debug task, then load only that
> family's `SKILL.md` and the references it points to. Do not load every
> skill at once. See [`../SKILL_LOADING.md`](../SKILL_LOADING.md).
>
> In our vocabulary, these files are **skills** (instructions for the AI
> agent); the runtime state-machine rows they help author are
> **pipelines/workflows**. See [`../CONVENTIONS.md`](../CONVENTIONS.md).

These are the MaaFramework pipeline-authoring skills from
[Everything-Maa](https://github.com/KhazixW2/Everything-Maa) (MIT),
vendored so this repo's AI maintainer has the authoritative
field/ROI/coordinate guidance next to the code.

- Upstream reference pin used for the copy: `8985260` (2026-09-08).
- Upstream is the source of truth; re-copy these files rather than editing
  them in place so the provenance stays clear.

Included: `maa-workflow-build`, `maa-pipeline-guide` (including the field
reference and coordinate hygiene), `maa-pipeline-generate` (OCR/ROI/template
sweeps), `maa-pipeline-testing`, `maa-pipeline-option`, `maa-cli-operate`, `maa-wiki`,
`maa-diagnose`.

These skills are the rulebook for [`../AUTHORING.md`](../AUTHORING.md):
MaaMCP observe/extract/benchmark → native pipeline JSON → import → Review →
verified replay. They are documentation, not a runtime/code dependency.
