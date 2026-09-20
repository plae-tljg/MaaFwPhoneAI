# MaaFwPhoneAI v1.0.0 — 首个单仓库 Release

基于 **MaaFwApp** fork 开发的 Android 端 AI 维护自动化助手。  
本次 release 的重点不是“让 AI 多点几次手机”，而是把方法闭环和原生契约落地：

## 本版特性

- **原生 MaaFW 图契约**：`pipelines.definition_json` / `pipeline_versions.definition_json` 直接存 `{Node: {recognition/action/next/on_error/...}}`，`entry` 显式；DB v2 一次性迁移旧线性数据。
- **Graph-native 执行**：整个 pipeline 图作为一个 MaaFW task 提交，`next` / `on_error` / anchors 由 MaaFW 真正执行。
- **原生识别桥**：`RemoteService.recognitionDirect` 返回 OCR / TemplateMatch / ColorMatch 的 `hit` / `box` / `detail`。
- **AI 工具层**（Kotlin，不依赖 Termux/Python）：
  - `recognize`：按需调用原生识别；
  - `benchmark`：重复 N 次识别，返回命中率/延迟；
  - `propose_pipeline`：AI 直接提交原生 MaaFW 图，进入 Review。
- **Replay + improve**：先回放 live pipeline；失败时 AI 读取旧定义、观察当前 UI、生成 `pipeline_fix` 候选。
- **Review Test replay**：候选先真实回放并链接 evidence，再人工 Approve。
- **pixel 后置条件**：toggle 类目标可在本地做像素/颜色变化验证，而不是只信 LLM verifier。
- **单启动图标**：去掉重复的 “Assistant (M1)” launcher 图标；Assistant 从主应用 Settings 进入。
- **Release 不内置 API Key**：release 包 `DEEPSEEK_API_KEY` 为空；Debug 包才会从本地 `.env` 注入。
- **PC 原型**：34 个测试通过；相机/Bilibili 有 AI-once → 0-token replay 证据。

## 安装

1. 下载 `MaaFwPhoneAI-v1.0.0-arm64-v8a.apk`。
2. 安装到 arm64-v8a Android 设备（Android 9+）。
3. 打开 **Maa-phone**。
4. 在 Settings 配置 DeepSeek API key（release 不包含 key）。
5. 按引导授予 Shizuku/root、通知、悬浮窗、电池白名单等权限。

## 校验

见 `SHA256SUMS`。签名信息（本 release 使用本地个人 release keystore）：

- Subject: `CN=MaaPhoneAI, OU=Personal, O=MaaPhoneAI, L=HongKong, ST=HongKong, C=HK`
- 后续升级必须使用同一 keystore。

## 已知限制

- 首次 AI 探索仍是 bootstrap；复杂/动态 UI 需要 AI 生成新图并经 Review。
- `pixel` 后置条件已接入；`element` / `screen_text` / `file_count` 仍未完全 wired。
- 部分旧 pending proposal 可能是契约变更前的格式，Test replay/Approve 会拒绝；请忽略并重新生成。
- 没有内置模型 key；没有 key 时只能跑确定性 pipeline，不能 bootstrap 未知目标。
- Debug APK 会从本地 `.env` 注入 key，请不要分发 debug 包。

## 致谢

见仓库 `docs/credits.md`。特别感谢 MaaFramework、MaaFwApp、MaaMCP、Everything-Maa、M9A 及所有 MAA 社区贡献者。
