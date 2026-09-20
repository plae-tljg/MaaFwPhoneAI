# 项目日志：三段对话、阶段与误解修正

> 这份文档给继续维护的人看。它记录我们怎么一步步走到今天、哪些判断被推翻、哪些坑已经踩过。
> 时间线受宿主机时钟跳变影响，以会话顺序和 SQLite `rowid` / 文件证据为准。

## 0. 讨论来源

| 段落 | 来源 | 内容 |
|---|---|---|
| 第一次 | OpenCode 会话 `ses_f453f2f26ffepIPk86HirhYG6F`，主题 “MaaMCP phone automation DB structure research” | 调研 chatbot-ai / MaaMCP / MaaFramework / MaaFwApp / Everything-Maa / M9A，形成最初目标、方法论和数据库设计 |
| 第二次 | DeepSeek Harness 长会话 `session-17bc26a2-cfff-4e8c-ac13-0bdcb58bda2e` 及 compact fork `session-f099d998-67bb-4b84-8e3e-e64f63272531` | 写文档、建 PC 原型、实现 Android M0/M1/M3/M3.2；最后在 1M context 处 `CONTEXT_WINDOW_EXCEEDED` 中断 |
| 第三次 | DeepSeek Harness 续接 `session-bb2cf4c6-9ecb-4dd1-af24-ead5632b1c09`、`session-982bf8a0-b28e-4d53-b886-ced7ba892a7e` | 状态收尾、修 UI/停止/导入/max steps、加屏幕观察、原生识别桥、原生 graph 契约、Kotlin MaaMCP 工具层和 release 构建 |

原始 OpenCode 记录在开发机 `00ref/opencode_history/`，不纳入仓库（体积和会话隐私）；DeepSeek Harness 会话同理只保留本回顾。

## 1. 第一次讨论：先想清楚“这是什么”

最初的直觉很容易被误解成“用 MCP 驱动手机”。讨论后收敛为：

> 手机本地有一个 SQLite 知识库；MaaFramework 做确定性回放；AI 只负责未知任务和把成功经验写成提议；人通过 Review 决定是否上线。

关键结论：

1. 不让 MaaFramework/MaaFwApp 重写，只把它们当执行底座。
2. workflow 是数据（MaaFW pipeline 图），不是 Kotlin/ Python 里的隐式逻辑。
3. 借鉴 `chatbot-ai` 的方法：小稳定解释器 + 自由结构 JSON 行 + 提议/审核 + 验证 + 维护。
4. 学习成本必须随时间下降：第一次 AI 付出，第二次 0 token。

## 2. 第二次讨论：从设计到可运行

这一阶段做了：

- PC 原型 Phase A：ADB 直控验证目标可行。
- PC 原型 MaaFW-native：`maa_exec.py` / `compiler.py` / `runner.py` / `agent_maa.py`。
- 相机任务：AI 5,107 tokens → 后置条件 `file_count` 验证 → 两次 0-token 回放。
- Bilibili `114514 → first video → like`：AI 33,558 tokens → pixel 验证 → 0-token 6/6 节点回放。
- Android M0：MaaFwApp fork 可构建；修复 `llvm-strip` 损坏 `libfastdeploy_ppocr.so`。
- Android M1：DB → Resolver → Compiler → `RunnerPort`，`open settings` 确定性跑通。
- Android M3/M3.2：Assistant UI、DeepSeek bootstrap、native pipeline importer。

失败与教训：

- `key home` 在虚拟屏上会把主屏切走，后台截图变黑；不能把 HOME 当初始化。
- 单帧 LLM verifier 不可靠：同一个 like 成功截图的 5 次调用得到 3 true / 2 false。
- bootstrap 的坐标点击会漂移，不能当作最终 workflow。

## 3. 第三次讨论：收尾、修 bug、转向原生契约

这一阶段先修表观问题：

- Stop 按钮：取消 AI loop / 停止 runner / run 置 `cancelled`。
- Import pipeline：从固定路径改为系统文件选择器；澄清“AI run 成功会自动生成 candidate + proposal，Import 只导入外部 MaaFW JSON”。
- Max steps：默认 15 → 300（5–1000），增加 early-exit streak（默认 5，2–10），空响应/失败/重复动作会提前退出。

随后做了关键增强：

- 屏幕观察：每帧变化时生成可见元素/文本/点位，喂给规划模型。
- 原生识别桥：新增 `RemoteService.recognitionDirect`，App 可直接拿到 MaaFW 的 OCR/TemplateMatch/ColorMatch `hit/box/detail`。
- Kotlin 工具层：`recognize`、`benchmark`、`propose_pipeline`，不再需要手机跑 Python / Termux。
- 原生 graph 契约：`definition_json` 直接存 MaaFW 节点图 `{Node: {recognition/action/next/...}}`，`entry` 显式；DB v2 一次性迁移旧线性数据；嵌套 `{action:{type,param}}` 被拒绝而不是运行时归一化。
- AI 第一次真正生成原生图：run #39 → proposal #12，含 `next` / `on_error`、OCR、ColorMatch、ClickKey、StopTask 等 7 个节点。

## 4. 阶段里程碑

| 阶段 | 内容 | 结果 |
|---|---|---|
| Phase A | raw ADB + 规则原型 | 验证手机自动化可行，保留为行为参考 |
| Proto | MaaFW-native PC brain | 34 tests；相机/Bilibili 有 AI-once + 0-token replay 证据 |
| M0 | MaaFwApp fork 可构建可运行 | 修复 native lib strip；虚拟屏/控制/预览 |
| M1 | DB → Resolver → Compiler → Runner | `open settings` 确定性跑通 |
| M3/M3.2 | Assistant + bootstrap + importer | UI/DeepSeek/严格验证/候选 proposal |
| v1 数据契约 | pipelines / pipeline_versions / missions | 候选版本、Review、mission 后端 |
| Native v2 | 原生 graph 契约 + DB 迁移 | 旧行一次性迁移；AI 直接 author 图 |
| AI 工具层 | recognize / benchmark / propose_pipeline | 原生识别 + skill 指导 + proposal |
| Release | 单图标、无内置 key、签名 APK | GitHub Releases |

## 5. 重要误解与修正

1. **“skill 就是 pipeline”**  
   修正：pipeline 是运行状态机；skill 是给 AI 的作者/调试说明（Everything-Maa）。两者不能混用命名。

2. **“AI 应该跑 MaaMCP”**  
   修正：MaaMCP 是 PC 侧 MCP 服务器，不嵌入手机；手机里的 AI 通过 Kotlin 工具 + MaaFW 原生识别桥获得等价能力。

3. **“bootstrap 成功后它就是产品 workflow”**  
   修正：bootstrap 只是证据。真正可复用 workflow 必须来自 native graph / MaaMCP 产出，经过 Review 和回放验证。

4. **“max steps 调大就行”**  
   修正：调大默认值只解决预算问题；空响应、重复动作和错误识别需要 early-exit + 更强观察。后来加了屏幕观察和原生识别桥。

5. **“Import pipeline 应该能创建当前 run 的 pipeline”**  
   修正：Import 只导入外部 MaaFW JSON；AI run 成功会自动生成 candidate version + proposal。

6. **“LLM verifier 失败就是动作失败”**  
   修正：同一截图 3 true / 2 false；toggle 需要确定性后置条件（pixel/本地检查）。已接入 `pixel`，其它 verifier 后续补。

7. **“HOME 是可靠的归一化”**  
   修正：虚拟屏下 HOME 全局且 launcher 可能黑屏；改为 `PackageManager` 查 launchable apps + `StartApp` 直接启动。

8. **“条件图 operator 应该硬编码”**  
   修正：条件逻辑应由 AI 依据 Everything-Maa 技能 + 真实识别结果自行生成 native graph；程序只提供识别、benchmark、存储、编译、回放能力。

9. **“可以先加 normalization 层，运行时兼容多种格式”**  
   修正：数据库应直接遵守 MaaFW 协议。改为原生 graph 存储 + 一次性 DB 迁移；AI 写错格式直接报错并让它修正。

10. **“手机装 Termux Python 就能跑 MaaMCP”**  
    修正：Termux 是另一个 UID/进程，拿不到 MaaFwApp 特权进程里的 Tasker/Controller；还会要求自连 ADB。正确做法是把 MaaMCP 过程层 Kotlin 化，直接复用现有 MaaFW 运行时。

## 6. 给后续维护者

- 读文档顺序：`GOAL.md` → `WORKING_STYLE.md` → `CONVENTIONS.md` → `STATUS.md` → `INTEGRATION.md` → `AUTHORING.md` → `DEBUGGING.md`。
- 修改前先看 `AGENTS.md` 的硬规则；不要绕过 Review 让 AI 数据直接 live。
- 不要提交 `.env`、keystore、SDK、`00ref/`、APK（release 走 GitHub Releases）。
- 所有 AI 生成的图都必须能在 Review 里 Test replay，再人工 Approve。
- 历史 run/proposal 是证据，不要为了“好看”清库。

## 7. 续接：确定性回放、OCR 模型与第三次讨论后的修正

继续 DeepSeek Harness 的 `MaaMCP app bugs and agent IO` 会话之后，先用
`adb` 重新核对了真机 DB、APK 和 MaaFW 日志，发现并修了三件事：

1. **`native OCR recognition: 185 chars` 不代表 OCR 成功。**
   `pi.zip` 里只有 demo pipeline，没有 PP-OCR 模型；MaaFW 每次都记
   `OCRResMgr: Failed to load det or rec: [name=] [det=0x0] [rec=0x0]`，
   返回的 JSON 只是空 `all/best`。现已把 `proto/bundle/model` 打包到
   `PI/brain_ocr/model/ocr`，`BrainResources.resourcePaths` 先加载
   `brain_ocr`。run #42 的识别证据里才第一次出现真实命中：
   `闹钟 score=0.998008, box=[388,641,70,40]`。

2. **裸 MaaFW recognition postcondition 的判断顺序写错了。**
   `Verifier.verify` 先把 `type` 为空当 `none` 跳过，导致 AI 写的
   `{node,recognition,expected,...}` 永远不执行。run #41 节点全过、
   `verified=0`，而 `verify_json` 是 `{"type":"none","skipped":true}`；
   调整分支后 run #42 `verified=1`。这说明“动作跑通”和“后置条件真的
   检查了”必须分开记。

3. **旧 proposal 需要在迁移里一次性修形，而不是运行时 normalization。**
   第三次讨论前生成的 proposal #11 还带
   `{"action":{"type":...,"param":{...}}}`。v2 迁移原样跳过，Test replay
   一直失败。现在 `PipelineGraph.migrateDefinition` 在迁移时拍平，DB
   v3 对全部 rows/proposals 跑一次；编译器仍只接受 native graph。

Review 页现在显示候选图的 goal/entry/节点/后置条件/关联 run 证据，Human
Review 可以直接看 run #42 的 `verified=1` 再决定是否 approve proposal #12。
Android 单测恢复到 `463 tests, 0 failed, 2 skipped`：补了 `VerifierTest`、
`PipelineGraphLegacyMigrationTest`，并修正了缺少 AIDL `recognitionDirect`
的 `FakePrivilegedService` 与 Assistant 直用 M3 `Button` 的旧问题。

下一步仍然是导入真实 MaaMCP/M9A workflow、Mission UI、agent-IO
`needs_input` 和 replay-K/healing；不要在设备测试里再用“日志出现某几个
字”替代真实后置条件证据。

## 8. Agent IO 与 Mission MVP（2026-09-20 下午续接）

上一轮修完 OCR/确定性后置条件后，继续实现计划里的两个用户可见闭环：

### Agent IO（`ask` / `needs_input`）

- 工具 schema 增加 `ask`：
  `{"action":"ask","question":"...","options":[...],"allow_free_text":true}`。
- `AgentRunner` 收到后写一条 assistant `messages(kind='question')`，把
  `runs.state` 置为 `needs_input`，通过 `CompletableDeferred` 挂起同一条
  协程；Assistant 用 AlertDialog 显示选项按钮 + 自由输入 + Cancel。
- 用户选择/输入后：问题消息置 `answered`、补一条 `user/answer`、把答案
  写进 history/trajectory，然后**继续原 run**；Cancel 则走取消路径。
- 真机验证用了临时 debug hook 直接打开 modal（uiautomator 确认
  title/question/options/free text/Send/Cancel 都在，Cancel 能关闭）。测试
  完把 hook 和 debug-only exported manifest 都删掉了，产品代码不暴露该入口。

### Mission MVP

- Assistant 新增 **Missions** tab：建/删 mission、添加 AI goal item 或固定
  一条 live pipeline、enable/disable/delete item、Run item。
- `BrainRunner.runMissionItem(itemId)`：有 pipeline 时按 pinned version（没有
  则 current live definition）走同一 `executePipeline`；没有 pipeline 时走
  AI fallback；run 会写 `mission_item_id`、`pipeline_id`、
  `pipeline_version_id`，并执行同一个确定性 postcondition。
- `BrainDb` 增加 missions list/items 查询，并在 `onConfigure` 打开 SQLite
  foreign keys，让 mission 删除能级联 item。
- 证据：run #43 = mission item #1（pin `open_settings`），
  `path='pipeline'`, `success=1`, `mission_item_id=1`；该老 pipeline 没有
  postcondition，所以 `verified=0`，符合“没检查就明确 skipped/0”的规则。

仍未做：Activity 重建后自动恢复未回答的 question、完整 chat transcript、
mission 队列/定时、带确定性 postcondition 的 imported candidate 跑 mission。

补记：这一轮同时接上了 token accounting。`DeepSeekClient` 累计 provider
`usage.total_tokens`（没有则 prompt+completion），`AgentRunner` 在
`finishRun` 时 drain 到 `runs.ai_cost`，Runs 页显示 `cost=<n>tok`；新增
`DeepSeekUsageTest` 覆盖两种 usage 形状。Android 单测变为 466。
