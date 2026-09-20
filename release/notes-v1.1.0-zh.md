# Maa-phone v1.1.0

这是 MaaFwPhoneAI 从 v1.0.0 打包版继续完成核心闭环后的版本。

## 新增

- **确定性回放**：Android 支持 `pixel` / `element` / `screen_text` /
  MaaFW recognition / `file_count` 后置条件；Review Test replay 会用真实
  MaaFW 识别证据判定 `verified`，不再只依赖 LLM 单帧判断。
- **OCR 模型随包**：PP-OCR det/rec/keys 打包进 `PI/brain_ocr`。v1.0.0
  之前设备上的 OCR 实际没有模型，日志只返回空结果；本版本已修复并真机
  验证识别到 `闹钟`。
- **MaaMCP / Everything-Maa 作者流程**：导入支持
  `{pipeline, entry, postcondition}` 包装，生成候选 `pipeline_versions`；
  `proto/pipelines/clock_app_maamcp.json` 已导入并 Test replay
  `verified=1`。
- **Review 证据面板**：显示候选图 goal/entry/节点/后置条件与关联 replay
  runs。
- **Agent IO**：AI 可调用 `ask`，运行进入 `needs_input`，Assistant 弹出
  选项 + 自由输入对话框；问题会持久化，App 重启后可恢复并继续原 run。
- **Chat 记录页**：按用户/助手气泡展示消息、问题、选项和处理状态。
- **Mission UI + 队列**：创建/固定/运行 mission item；支持队列
  Run all / Pause / Resume / Cancel / Clear，以及 App 存活期间的定时
  run-all。DB v4 新增 `mission_queue`，旧数据库原地升级。
- **Token 统计**：`runs.ai_cost` 记录 provider usage，Runs 页显示
  `cost=<n>tok`。
- **UI 修复**：Import pipeline 按钮改为自适应换行，不再一个字一行。
- 修复 fresh install 缺少 `assets/brain/schema.sql` 的问题；根
  `schema.sql` 现在会在构建期同步进 APK。

## 真机证据

- run #42：proposal #12 Test replay，OCR 命中 `闹钟`，`verified=1`。
- run #44 / #52：导入的 MaaMCP clock pipeline Test replay，
  `verified=1`，版本 #13 有 replay 关联。
- run #45：approve 后的 pipeline 作为 mission item 运行，
  `path='pipeline'`, `mission_item_id=2`, `verified=1`。
- run #46–#49：mission queue / in-app schedule 顺序执行完成。
- Android 单测：466 通过（2 skipped）。

## 安装说明

- APK：`MaaFwPhoneAI-v1.1.0-arm64-v8a.apk`，arm64-v8a。
- 从 v1.0.0 直接覆盖安装；DB v2/v3 会在首次打开 Assistant 时迁移到
  v4，旧 proposals/graph 会做一次性修复。
- Release APK 不内置 DeepSeek key；首次使用请在 Assistant → Settings
  填入 API key / base / model。
- MIUI 若不允许 adb 安装，可先把 APK 推到 `/sdcard/Download/` 再手动
  安装。
