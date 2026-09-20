# Skill loading — load one family, not everything

The authoring agent must **not** load all Everything-Maa skills at once.
Load the small family index first, choose the family that matches the task,
then load only that family's files.

## 1. Procedure

1. Read [`maa-skills/index.json`](maa-skills/index.json). It is short and
   lists skill families, their purpose, and when to load them.
2. Match the current task to one family (or at most two):
   - designing a new workflow → `workflow-design`
   - writing/porting pipeline JSON → `pipeline-authoring`
   - testing/benchmarking a node → `pipeline-testing`
   - investigating a failing run → `diagnosis`
   - protocol/field lookup → `reference`
3. Read the selected family's `SKILL.md` and only the references it points to
   for this task.
4. Use the described tools from **MaaMCP** to perform the procedures
   (screencap, OCR, ROI/crop, `save_captured_image`, pipeline creation,
   `benchmark_node`).
5. Record the result as pipeline data or a proposal, never as a new prompt
   branch.

## 2. Why family-based loading

- context budget: the full skill set is large and mostly irrelevant to the
  current task;
- correctness: the right authoring rules for the phase reduce field/ROI
  mistakes;
- reviewability: the loaded family and its output can be recorded with the
  proposal;
- consistency with the project method: skills are instructions, pipelines
  are data, tools are external procedures.

## 3. Families vs. Everything-Maa repo

`index.json` groups the vendored skills; it does not replace upstream. The
upstream repository (pin recorded in [`MAA_STACK.md`](MAA_STACK.md)) remains
the source of truth. Re-vendor files instead of editing them.

## 4. Agent rule of thumb

> Read the index. Pick the family. Load only that family. Use MaaMCP tools to
> produce pipeline JSON/assets. Submit the result through Review.
