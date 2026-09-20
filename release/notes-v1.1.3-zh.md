# MaaFwPhoneAI v1.1.3

针对“Review 批准后管线找不到/不能复用”、“实时预览第一格黑屏”和“主任务页
看不到 AI 生成的管线”的修复与产品补齐。

## 已发布内容

- **批准的管线可再次找到并运行**
  - Assistant 新增 **Pipelines** 标签：列出 live/draft/broken 管线、当前版本、
    source、postcondition 和最近一次 run 证据。
  - `Run replay` 直接执行该行的已存图形/版本，`Add to mission` 可直接创建
    或选择 mission。
  - Review 的批准按钮改为 **Approve & open**，批准后直接落到管线库；
    已批准卡片也有 **Open in pipeline library**。
  - Home 新增 **MaaFwPhoneAI Brain → 管线库**，Tasks 页新增 **AI 管线库** 入口。

- **AI 管线进入原 MaaFwApp 的任务配置**
  - `BrainProjectRepository` 把每个 `pipelines.status='live'` 行投影成
    `brain_pipeline_<id>` 合成任务，放在 Add tasks 的 **AI Pipelines** 分组。
  - 任务 override 使用数据库里的扁平 MaaFW graph/entry，并把
    `brain_ocr`、`brain/res_N` 学习资源路径追加到当前 PI resource。
  - 因此 **Tasks → 添加任务 → AI Pipelines → 开始任务** 走的是正常
    MaaFW runner，不再回退到 AI 规划循环。
  - 回到主界面时自动刷新 catalog；新批准/导入的管线无需重装即可出现。

- **实时预览黑屏修复**
  - Tasks 与 Assistant 共用一个 `PreviewPort`；旧 Activity 延迟到达的
    `surfaceDestroyed` 会把新 Activity 刚接上的 Surface 清掉，导致第一格
    实时预览黑屏，而第二格文件截图仍正常。
  - 现在 Surface 携带 per-host owner token 与 Surface identity；只接受当前
    host/当前 Surface 的销毁回调。真机 logcat 验证旧 detach 被忽略，AI run
    时实时格与文件截图格显示相同内容。

- **应用改名**
  - 启动器/应用标签改为 **MaaFwPhoneAI**（package id、签名、DB 均不变）。

## 验证

- Android 单测：`466 passed, 0 failed, 2 skipped`。
- release 构建通过，APK 标签 `MaaFwPhoneAI`，`versionCode=298`。
- 真机验证：
  - 已批准的 Bilibili 管线在 Pipelines 与 Tasks 的 AI Pipelines 分组中可见；
  - 在原 Tasks 页把该任务加入空配置并 `开始任务`，虚拟屏 Bilibili 正常
    运行，没有 AI 规划请求；
  - 实时预览黑屏问题不再出现，stale preview detach 被拒绝。
- Release APK 不内置 DeepSeek key；首次使用在 Assistant → Settings 填入
  官方 key。

## 已知限制

- 本版示例 Bilibili 管线来自 bootstrap 轨迹，`postcondition_json={}`，
  识别节点能否命中仍取决于当前模板/UI；后续需要用 MaaMCP + deterministic
  postcondition 补齐。
