# Maa-phone v1.1.2

针对“对话像拼在一起、每步很慢、首次 AI run 后没生成 pipeline”的调查与修复。

## 调查结论

- **AI 上下文不是一整份拼接 JSON**：每次规划请求只有 system + 当前截图
  user 消息；历史只是 user 文本里的最近 8 条动作。旧 Chat UI 展示的是全局
  `messages` 流，所以看起来像把所有对话拼在一起。现在 Chat 页按 `run_id`
  分线程，并提供最近 run 选择。
- **每步变慢的来源**：native OCR 又跑了一次 Screencap、常见屏幕上额外跑了
  一次 vision screen-observer、动作后固定等待 1200ms。现在复用当前帧、
  只在 OCR 无文本时才 observer、等待降到 250ms、降低 max-token 上限，
  并在状态里记录每步耗时。mock 真机两步 run：10.4s → 6.8s；真实模型
  延迟另计。
- **首跑没 pipeline**：两个原因都要防：官方 `api.deepseek.com` 配
  `deepseek-v4-flash` 会模型名无效并反复失败；最终 LLM verify 的假阴性会
  把成功轨迹整条丢掉。现在官方 base 默认 `deepseek-chat`（Settings 有
  提示）；verify 失败仍生成带
  `vision_verified=false` / `proposed_from_failed_verification=true` 的
  候选 proposal，供 Review/Test replay。转换异常会显示在 run 状态里。

## 验证

- mock provider 真机证据：run #3 10.4s、run #4 6.8s、run #5 verify 失败但
  proposal #3 正确生成。
- Android 单测：466 通过，0 失败，2 skipped；release 构建通过。
- Release APK 不内置 DeepSeek key；首次使用在 Assistant → Settings 填入
  官方 key，并保持 model 为 `deepseek-chat`（官方 API）或你的自定义网关名。
