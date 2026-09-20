# 里程碑与提交阶段

> 这个文件解释仓库里的提交顺序应该怎么读。主分支按“可理解、可回滚”的阶段提交，而不是一次把整个历史压成一个 commit。

## Stage 0 — 上游 base

- 来源：MaaFwApp `70cd377`。
- 关系：MaaFwPhoneAI 是这个 Android 外壳的 fork；上游代码和 AGPL 许可必须保留。
- 对应提交：`stage 0: import MaaFwApp-based Android shell`。

## Stage 1 — 项目目标、方法与文档

- 内容：`GOAL.md`、`WORKING_STYLE.md`、`CONVENTIONS.md`、`DESIGN.md`、`DECISIONS.md`、schema、handoff 文档。
- 目标：先把“知识是数据、AI 提议、人类批准、验证优先”的方法写清楚。
- 对应提交：`stage 1: document the AI-maintained phone automation method`。

## Stage 2 — PC 原型

- 内容：`proto/`：MaaFW Python binding、compiler、runner、learner、verifiers、pipeline import/export。
- 证据：相机和 Bilibili AI-once → 0-token replay；34 tests。
- 对应提交：`stage 2: MaaFramework PC prototype (AI once, replay zero-token)`。

## Stage 3 — Android M0/M1

- 内容：MaaFwApp fork、native runtime 修复、`brain/` DB/Resolver/Compiler/Runner。
- 证据：`open settings` 确定性跑通。
- 对应提交：`stage 3: Android fork shell + deterministic brain (M0/M1)`。

## Stage 4 — Assistant、AI bootstrap、Importer

- 内容：Compose UI、Runs/Review/Data/Logs/Settings、DeepSeek loop、screen observation、Importer。
- 证据：搜索、like、finance app 等真机 runs；候选版本 + proposal。
- 对应提交：`stage 4: Assistant UI, bootstrap AI and pipeline importer`。

## Stage 5 — 稳定性与 UI 修复

- 内容：Stop、file picker 导入、300-step budget + early-exit、IME、HOME 虚拟屏问题、app catalog。
- 对应提交：`stage 5: stop/cancel, import picker, larger bootstrap budget and loop guards`。

## Stage 6 — Native 契约与 AI 工具层

- 内容：
  - `definition_json` 直接存 MaaFW 原生节点图 + `entry`；
  - DB v2 一次性迁移旧数据；
  - `recognitionDirect` 原生识别桥；
  - Kotlin `recognize` / `benchmark` / `propose_pipeline`；
  - Review Test replay + graph-native runner。
- 对应提交：`stage 6: native MaaFW graph contract, recognition bridge, Kotlin MaaMCP tools`。

## Stage 7 — 打包与 release

- 内容：单启动图标（去掉 M1 Assistant 图标）、release 构建不注入 API key、release notes、GitHub Release。
- 对应提交：`stage 7: single launcher, key-free release build and v1.0.0 packaging`。

## 如何继续加 stage

- 每个 stage 只做一类可解释的改动。
- commit message 用英文或中英混合均可，但正文必须说明“为什么”和“验证方式”。
- 文档、schema、迁移、代码、release artifact 的更新要互相对得上。
