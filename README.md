# MaaFwPhoneAI

> 一个**基于 MaaFwApp fork** 开发、运行在手机本地的 Android 自动化助手：自动化知识存进 SQLite 数据库，AI 负责维护；MaaFramework 负责确定性的识别与执行；只有在数据库里没有可复用流程时，AI 才接管未知任务；人工 Review 是唯一上线闸门；每次成功的 AI 运行都会变成下一次 0 Token 回放的证据。

![license](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)
![android](https://img.shields.io/badge/Android-arm64--v8a-green)
![status](https://img.shields.io/badge/status-v1.0.0_release-lightgrey)

## 1. 项目是什么

MaaFwPhoneAI（工程目录仍称为 **maa-phone**，代码命名空间保持 `com.aliothmoon.maafw.maaphone`）不是“每次都靠大模型点手机”的脚本，而是一套 **知识系统**：

```text
未知目标
  └─ AI 看屏幕（OCR / 视觉 / 真实 MaaFW 识别）解决一次
      └─ 记录轨迹 + 证据
          └─ 生成候选 pipeline / hint / policy / element fix
              └─ 人工 Review
                  └─ 下一次：MaaFramework 确定性回放，0 AI Token
                      └─ 失败再次变成维护证据
```

核心原则：

1. **数据即知识**：workflow 是数据库里的 MaaFW 原生 pipeline 图（`recognition` / `action` / `next` / `on_error`），不是藏起来的代码。
2. **确定性优先**：能命中数据库流程就绝不调模型。
3. **AI 提议、人工批准**：AI 只能写 `proposals`，Review 后才生效。
4. **验证与证据**：每个 run 保存后置条件、截图、日志；模型说“完成”不等于真完成。
5. **消息即 IO**：goal / question / answer / result / proposal 都是同一条消息流的行。

## 2. 架构概览

```text
Assistant / Review / Data / Logs / Settings        （我们的 UI；MaaFwApp fork）
        │
        ▼
Kotlin brain：SQLite + Resolver + Compiler + Learner + Verifier + Agent
        │
        ├── 0-Token pipeline ──► MaaFwApp privileged runner ──► MaaFramework
        │
        └── 未知目标 ──► 屏幕观察 / 原生识别桥 / AI 规划
                              │
                              └──► propose_pipeline（MaaFW 原生图）──► Review
```

依赖关系：

- **MaaFramework**：识别/动作引擎，提供 OCR / TemplateMatch / ColorMatch / ROI / 图执行。
- **MaaFwApp**：Android 特权外壳（Shizuku/root、虚拟屏、输入、日志、预览）；本项目在该 fork 上增加 `brain/` 和 Assistant。
- **Everything-Maa**：Maa 工作流编写技能文档（MIT，已按 family 懒加载内嵌）。
- **MaaMCP**：PC 侧 MCP 作者/调试工具；本项目的 AI 工具层在 Kotlin 中重新实现（`recognize` / `benchmark` / `propose_pipeline`），不要求手机装 Python 或 Termux。
- **M9A / MAA-Meow 等**：结构参考与 Android 后台运行验证，不是运行时依赖。

## 3. 当前状态

| 部分 | 状态 |
|---|---|
| PC 原型 `proto` | MaaFW-native；相机、Bilibili `114514 → like` 有 AI-once → 0-token replay 证据；34 个单测通过 |
| Android 特权外壳 | M0/M1 可用；虚拟屏、Shizuku/root、预览、日志 |
| Assistant UI | Run / Logs / Runs / Review / Data / Settings；Stop、Import pipeline、Replay + improve、Test replay |
| 数据库契约 | `definition_json` = MaaFW 原生节点图；`entry` 显式；DB v2 一次性迁移旧线性数据 |
| AI 工具 | `recognize`（OCR/TemplateMatch/ColorMatch + box/detail）、`benchmark`、`propose_pipeline` |
| 人类闸门 | `pipeline_fix` / `pipeline_new` 均为 pending proposal；Review Test replay 后才可 Approve |
| Release | 已构建无内置 DeepSeek Key 的 arm64-v8a release APK；见 GitHub Releases |

已知边界：

- AI 首次探索仍是 bootstrap，不是最终工作流来源；游戏/活动 UI 变化应由 AI 用 Everything-Maa 技能 + 原生识别生成新图，再由 Review Test replay。
- `pixel` 后置条件已接入；`element` / `screen_text` / `file_count` 仍需更多 Android 识别细节接线。
- 发布 APK 使用本地个人签名；升级必须使用同一 keystore。

## 4. 仓库结构

```text
README.md                  中文主 README（默认）
README.en.md               英文 README
AGENTS.md                  AI maintainer 规则
app/                       Android app（MaaFwApp fork 主体；brain/ 与 Assistant 在这里）
annotation-api/            DataStore schema 注解/代码生成
build-logic/               Gradle convention plugins（签名、native、PI assets）
hidden-api/                hidden API 兼容层
ksp-processor/             KSP 处理器
macrobenchmark/            baseline profile 采集
semi-icons/                Semi Design 图标模块
proto/                     MaaFramework PC 原型（行为参考）
docs/                      目标、方法、ADR、状态、调试、journal、credits、依赖策略
release/                   release notes 与校验和
schema.sql                 数据库契约
settings.gradle.kts        Android 工程入口
```

## 5. 快速开始

### 5.1 Android Release

1. 从 GitHub Releases 下载 `app-release.apk`。
2. 安装后打开 **Maa-phone**（只有一个启动图标；Assistant 从 Settings 进入）。
3. 在 Settings 填 DeepSeek API key（release 包不内置任何 key）。
4. 需要 Shizuku 或 root 权限；按应用引导完成授权。
5. Run 里输入目标，或使用 Review 批准 AI 生成的 pipeline。

### 5.2 Android 源码构建

```bash
# 在仓库根目录执行；拉取固定版本 MaaFramework Android 原生库
python3 scripts/setup_maa_framework.py --abi arm64-v8a

# Debug（本地开发；会从 .env 注入 key，不可分发）
./gradlew :app:assembleDebug

# Release（无内置 key；需要自己的 keystore）
KEYSTORE_PATH=/path/to/release.jks \
KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... \
./gradlew :app:assembleRelease
```

Release 构建不会读取 `.env` 里的 API key；release APK 中 `DEEPSEEK_API_KEY` 为空字符串。

### 5.3 PC 原型

```bash
cd proto
python3 -m venv --system-site-packages .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install maafw
python tools/setup_maa.py
.venv/bin/python -m pytest tests/ -q
```

## 6. 文档入口

- 目标与中心思想：`docs/GOAL.md`
- 工作方法与数据契约：`docs/WORKING_STYLE.md`、`schema.sql`
- 当前状态与下一步：`docs/STATUS.md`
- 三段讨论历史：`docs/journal.md`
- 致谢与集成：`docs/credits.md`
- 外部依赖策略：`docs/dependencies.md`
- 调试方法：`docs/DEBUGGING.md`

## 7. 发布

Release APK 见 GitHub Releases：

- `MaaFwPhoneAI-v1.0.0-arm64-v8a.apk`
- 校验和与说明见 `release/`。
- Release 包不内置 DeepSeek API key；在 Settings 中填写。

## 8. 许可证

- 本项目 Android fork 沿用 **AGPL-3.0-or-later**（上游 MaaFwApp 许可）。
- MaaFramework 为 LGPL-3.0；本仓库不修改其源码，只以预编译库或 Python 包方式使用。
- Everything-Maa skills 为 MIT（已内嵌）。
- MaaMCP 为 AGPL-3.0；本仓库借鉴其工具流程并以 Kotlin 重写，不嵌入其 Python 运行时。
- 完整说明见 `docs/credits.md` 与各目录 `LICENSE`。
