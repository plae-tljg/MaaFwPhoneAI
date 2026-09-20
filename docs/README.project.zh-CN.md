# maa-phone（MaaFwPhoneAI 工程目录）

这是 **MaaFwPhoneAI** 的核心工程：一个基于 **MaaFwApp** fork 的 Android 端 AI 维护自动化助手。

> 产品目标：让手机自动化的“知识”成为数据库里 AI 可维护的数据；确定性 MaaFramework 回放优先；视觉模型只处理未知；人工 Review 是唯一上线闸门；重复成本趋近 0。

## 当前能力

- PC 原型 `proto/`：34 个测试通过，相机/Bilibili 有 AI-once → 0-token replay 证据。
- Android `android/`：
  - MaaFwApp fork + Shizuku/root + 虚拟屏 + 预览；
  - `brain/`：SQLite、Resolver、Compiler、Runner、Learner、Verifier；
  - Assistant UI：Run / Logs / Runs / Review / Data / Settings；
  - Stop、Import pipeline、Replay + improve、Review Test replay；
  - 原生识别桥 `recognitionDirect`；
  - AI 工具 `recognize` / `benchmark` / `propose_pipeline`；
  - `definition_json` 存 MaaFW 原生 graph；DB v2 迁移旧格式。

## 常用命令

```bash
# PC 原型
cd proto && .venv/bin/python -m pytest tests/ -q

# Android debug（本地开发；会注入 .env key，勿分发）
cd android
python3 scripts/setup_maa_framework.py --abi arm64-v8a
./gradlew :app:assembleDebug

# Android release（无内置 key；需自备 keystore）
KEYSTORE_PATH=/path/release.jks KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... \
  ./gradlew :app:assembleRelease
```

## 文档

- 根目录 `docs/journal.md`：三段对话、阶段、误解修正。
- 根目录 `docs/credits.md`：MAA 系列致谢与集成。
- 根目录 `docs/dependencies.md`：依赖 pin 与 submodule 决策。
- `docs/GOAL.md`：目标与中心思想。
- `docs/WORKING_STYLE.md`：AI 维护知识库的方法。
- `docs/STATUS.md`：当前状态与下一步。
- `docs/DEBUGGING.md`：证据优先调试方法。

英文版：`README.md`。
