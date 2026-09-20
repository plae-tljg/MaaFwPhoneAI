# 外部依赖策略：为什么不用 git submodule？

> 结论：**本产品仓库不把 MAA 系列仓库作为 git submodule 嵌入。**  
> 外部仓库用“固定 revision + 下载脚本 + 文档记录”的方式接入；只有 Everything-Maa skills 作为 MIT 文档 vendored。

## 1. 为什么不全部 submodule

1. **MaaFwApp 是源码级 fork，不是依赖**  
   `./` 已经是 MaaFwApp `70cd377` 的 fork，里面有大量本项目的修改。如果再把上游 MaaFwApp 做成 submodule，会出现“fork 里套上游”的双层结构，维护者容易改错仓库。
   - 正确关系：`MaaFwApp` 是 **fork base / provenance**；当前仓库保存 fork 后的完整代码。
   - 上游只用于 diff/比对，不作为首次 clone 的必需品。

2. **MaaFramework 是二进制/包依赖，不是源码依赖**  
   - PC 侧：`pip install maafw`（PyPI）。
   - Android 侧：`scripts/setup_maa_framework.py` 下载固定 release 的 `.so`，由 `.maafwversion` 锁定；`.so` 有 200MB+，不应进 git。
   - 让每个 clone 都 submodule MaaFramework 会显著拖慢仓库，而且用户通常不需要编译引擎。

3. **MaaMCP 是外部 PC 工具，不应进入 APK**
   - AGPL、Python/FastMCP 依赖栈、面向 PC 的 ADB/Windows 控制。
   - 手机端 AI 工具层已经在 Kotlin 中实现（`recognize` / `benchmark` / `propose_pipeline`），不需要在手机上运行 MCP server。
   - 如果要完整性，用户可在 PC 侧单独 clone MaaMCP。

4. **M9A / MAA-Meow / chatbot-ai 是参考，不是运行时依赖**
   - M9A 是游戏项目，包含大量游戏资源；本仓库只参考结构。
   - chatbot-ai 是个人方法参考，也不适合作为运行时依赖。

5. **Everything-Maa 是 MIT 文档，适合 vendored**
   - 只有 372KB 左右，直接放进 `docs/maa-skills/` 最稳定；不会因为上游更新导致手机端行为漂移。
   - 保留 `source` 和 revision（`8985260`）。

## 2. 当前依赖清单

| 项目 | 用途 | Pin / 来源 | 接入方式 | 许可证 |
|---|---|---|---|---|
| MaaFramework | 识别/动作/控制器 | `b849283`，Android release `v5.13.1` | Python `maafw`；Android `setup_maa_framework.py` 下载 `.so` | LGPL-3.0 |
| MaaFwApp | Android 特权外壳 | `70cd377` | 源码 fork 到 `./` | AGPL-3.0 |
| MaaMCP | PC MCP 作者工具台 | `ff2d2b5` / `v1.2.3` | 不嵌入；参考其工具流程并在 Kotlin 重写 | AGPL-3.0 |
| Everything-Maa | AI 作者 skill 文档 | `8985260` | vendored 到 `docs/maa-skills/` | MIT |
| M9A | pipeline 图结构参考 | `4fa65aa` | 仅文档 `M9A_REFERENCE.md` | AGPL-3.0 |
| chatbot-ai | 方法论参考 | `9a41c46` | 仅文档映射 | 个人参考 |
| MAA-Meow | Android 运行实践参考 | 未固定 | 设计研究 | 按其仓库许可证 |

## 3. 如果你想获得完整参考资料树

本仓库不包含 `00ref/`。需要时可单独拉取：

```bash
mkdir -p 00ref && cd 00ref
git clone https://github.com/MaaXYZ/MaaFramework.git
git clone https://github.com/Aliothmoon/MaaFwApp.git
git clone https://github.com/MAA-AI/MaaMCP.git
git clone https://github.com/KhazixW2/Everything-Maa.git
git clone https://github.com/MAA1999/M9A.git
```

然后按 `docs/MAA_STACK.md` / `docs/dependencies.md` 的 pin 切 revision：
MaaFramework `b849283`、MaaFwApp `70cd377`、MaaMCP `ff2d2b5`、Everything-Maa `8985260`、M9A `4fa65aa`。

## 4. 什么时候才考虑 submodule

只有以下情况才建议加 submodule：

- 你需要在本仓库里同时开发上游 MaaFramework/MaaFwApp，并希望日常 diff/提交上游；
- 或者你要做一个“全生态研究仓库”，明确不是给普通用户 clone 的产品仓库。

对于当前产品目标，**固定 pin + 文档 + 下载脚本**比 submodule 更简单、更快、更少法律/版本漂移问题。
