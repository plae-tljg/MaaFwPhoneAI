# 致谢与 MAA 系列集成说明

> 这个项目不是从零发明的。它建立在 MAA 生态和一批优秀开源项目之上。
> 这里说明每个项目帮了什么、我们怎么接入、许可证是什么。完整 pin 见 `docs/dependencies.md` 和 `docs/MAA_STACK.md`。

## 1. 最需要感谢的项目

### MaaFramework（MaaXYZ）

- **作用**：整个自动化引擎。OCR、TemplateMatch、FeatureMatch、ColorMatch、ROI、`target` / `order_by` / `index`、`next` / `on_error` / anchor、动作与 Android 控制单元都由它提供。
- **我们怎么用**：
  - PC 原型通过 `maafw` Python binding 使用（`proto/maa_phone/maa_exec.py`）。
  - Android 使用固定 release 的原生 `.so`，由 `scripts/setup_maa_framework.py` 拉取（native release `v5.13.1`，参考 checkout `b849283`）。
  - `Android` 的 `Compiler` 直接存/发 MaaFW 原生图字段；识别桥 `recognitionDirect` 调用 `MaaTaskerPostRecognition` + `MaaTaskerGetRecognitionDetail`。
  - 不 fork、不修改 MaaFramework 源码。
- **许可证**：LGPL-3.0。

### MaaFwApp（Aliothmoon）

- **作用**：Android 特权外壳的 fork 基础。它提供 Shizuku/root runner、Android native controller、虚拟屏、预览、悬浮窗、通知、调度、日志和 agent host。
- **我们怎么用**：
  - `./` 基于 MaaFwApp `70cd377`。
  - 保留上游 `RemoteService` / `RunPlanPayload` / `MaaRunner` 执行契约，不额外改 AIDL。
  - 我们的增量：`brain/`（SQLite、Resolver、Compiler、Runner 绑定、AI bootstrap、Learner、Recognition bridge）、Assistant/Review UI、Importer、native 库 strip 修复、构建覆盖层。
  - 上游 AGPL 传染性导致本项目 Android 部分保持 **AGPL-3.0-or-later**；这是合规要求，也是我们主动标注 fork 来源的原因。
- **许可证**：AGPL-3.0。

### MaaMCP（MistEO / MAA-AI）

- **作用**：PC 侧 MCP 作者/调试工具台。提供 `screencap`、`ocr`、设备控制、`save_pipeline` / `load_pipeline` / `run_pipeline`、`benchmark_node`、ROI/模板/颜色试验等流程。
- **我们怎么用**：
  - 作为流程参考和契约参考；`proto/pipeline_io.py` 实现 MaaMCP pipeline JSON 导入/导出；Android `Importer` 导入外部 MaaFW pipeline。
  - 在其思路基础上，用 Kotlin 重写手机内工具：`recognize`、`benchmark`、`propose_pipeline`。
  - **不嵌入 APK，不在运行时调用 Python MCP server**；这是 ADR-021 的架构/许可证边界。
- **许可证**：AGPL-3.0。

### Everything-Maa（KhazixW2）

- **作用**：MAA 工作流作者技能库，教 AI 什么时候用 OCR、TemplateMatch、ColorMatch、ROI、`order_by` / `index`，如何生成/测试 pipeline。
- **我们怎么用**：
  - 将 8 个相关 skill vendored 到 `docs/maa-skills/`（pin `8985260`）。
  - `SkillLoader.kt` 先读 `index.json` family，再只加载匹配的一个 family 注入模型 prompt。
  - 这是 AI authoring 的规则来源，不包含运行时代码。
- **许可证**：MIT（保留版权与许可文本）。

### M9A（MAA1999）

- **作用**：成熟的 MaaFW 项目结构参考：PI task/option、资源覆盖、按 recognition 门控的 pipeline 图、AgentServer 自定义识别/动作。
- **我们怎么用**：
  - 只作为结构参考和小段模式学习，见 `docs/M9A_REFERENCE.md`。
  - 不复制游戏内容、不复制具体任务/资源；本项目也不针对特定游戏硬编码。
- **许可证**：AGPL-3.0。

### MAA-Meow 与 MAA 社区项目

- **作用**：社区在 Android 上运行 MAA / MaaFramework 的实践参考，尤其是 Shizuku/root、native control unit、后台虚拟屏、悬浮窗与保活模式。我们研究了这类项目如何绕过 ADB，直接在手机上控制。
- **我们怎么用**：借鉴其 Android 集成与显示模式思路；没有复制其业务代码。感谢 MAA-Meow 作者和所有 MAA 社区贡献者。
- **说明**：具体 pin 没纳入本仓库；如果后续需要在 `docs/dependencies.md` 中补链接与 revision。

### chatbot-ai（方法来源）

- **作用**：证明“小稳定引擎 + 自由结构化知识行 + propose/Review + 维护”的方法。
- **我们怎么用**：概念和行为层移植，不复制代码；表级映射见 `maafw-phone/docs/WORKING_STYLE.md` §5。
- **说明**：这是 workspace 内的个人参考项目，不是 MAA 系列运行时依赖。

## 2. 集成关系速查

| 项目 | 角色 | 在 maa-phone 中的位置 | 运行时依赖？ |
|---|---|---|---|
| MaaFramework | 引擎 | proto Python binding；Android `.so` + Compiler + Runner；`recognitionDirect` 桥 | 是 |
| MaaFwApp | Android 外壳 | `./` fork 基础 | 是（源码级 fork） |
| MaaMCP | PC 作者工具台 | pipeline JSON 契约、proto import/export、Kotlin 工具思路来源 | 否 |
| Everything-Maa | AI 作者技能 | `docs/maa-skills/`，`SkillLoader.kt` 懒加载 | 否（仅 prompt 文档） |
| M9A | 结构参考 | `M9A_REFERENCE.md` | 否 |
| MAA-Meow | Android 实践参考 | 设计研究 | 否 |
| chatbot-ai | 方法论来源 | `WORKING_STYLE.md` 映射 | 否 |

## 3. 命名与品牌说明

- 本仓库正式名：**MaaFwPhoneAI**。
- 工程目录仍叫 `maa-phone`，代码命名空间保持 `com.aliothmoon.maafw.maaphone`，以尊重 fork 来源和已有数据。
- 我们**不是** MAA 官方项目，也不应被误认为 MAA 官方。所有上游项目版权归各自作者，许可证文本保留在对应位置。
- 本项目 Android 部分沿用 AGPL，任何分发/修改都需要遵守 AGPL；商业闭源分发需要先解决授权问题。

## 4. 如何继续表达感谢

- 向上游提 issue/PR 前先在对应仓库确认约定。
- 如果你的改动让 MaaFramework/MaaFwApp 更通用，优先反馈上游，而不是只留在本 fork。
- 使用 Everything-Maa 的 skill 文本时保留 MIT 许可和来源说明。
- 引用本项目时，请同时引用它依赖的 MAA 系列项目。
