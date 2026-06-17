# PocketAgent 项目设计文档（端云协同 Android Agent · 端侧轨道收官蓝图）

## 0. 这份文档怎么用

这是 [端侧 AI 学习轨道](on-device-ai-track.md) 的**收官项目**，地位等同于主线的 [AgentOps Mini Platform](agentops-mini-platform-design.md)，但目标不同：

- **AgentOps Mini Platform**（主线收官）：一个**云端** Agent 运维平台，证明你"会做 Agent 后端 / 平台"。
- **PocketAgent**（本文，端侧收官）：一个**端云协同的 Android Agent**，证明你"会把 Agent 跑在端上、并和云端协同"——这是端侧 JD 真正稀缺、也最吃你 Android 背景的能力。

**两段式，不是从零另起炉灶**：

> **云半部** = 直接复用一个**精简版 AgentOps**（FastAPI + agent loop + trace），证明后端/Agent 能力。
> **端半部** = 一个 Android App，端侧跑量化小模型 + 工具调用 + 本地 RAG，并用 router 决定"本地干还是甩云端"。**护城河在这半。**

**怎么用**：照 **Part 7 的里程碑 M0–M5** 一步步做，每步能跑能演示。里程碑刻意和端侧轨道的 **M0–M4**、C++ 补齐计划的 **C1–C3** 对齐——做这一个项目，三条线一起推进。

> 全文 `[第N篇]` 链到主线文档，`[端侧:xx]` 链到端侧轨道/ C++ 篇，动手时回去查。

---

## 1. 这个项目是什么

**PocketAgent** = 一个能在手机上**离线**跑起来的 Agent：

> 你问它一句话 → 它先判断"这事本地小模型能不能搞定" → 能就**端侧推理 + 调本地工具**（离线、隐私、快）→ 搞不定就**降级到云端大模型**（精简版 AgentOps）→ 全程把每一步连同**设备指标**（延迟/内存/功耗/走了哪条路）记成 trace → 最后能跑一个 eval 对比"不同量化档 / 不同后端 / 不同路由策略"谁更好。

一句话价值：它把端侧轨道学的**推理/量化/NPU/JNI** 和主线学的 **agent loop / 工具 / trace / eval**，拼成一个**有真机、有数据、能演示**的作品，并且每个面试关键词都能展开（Part 9）。

---

## 2. 整体架构

```
┌──────────────────────── Android App（端半部）─────────────────────────┐
│  Compose UI ── 用户输入                                                 │
│        │                                                                │
│  ┌─────▼──────────┐   ① 路由判断（难度/隐私/网络/电量）                  │
│  │ Agent 编排层    │───────────────┬───────────────────────────┐       │
│  │ (Kotlin)        │               │ 本地                       │ 云端  │
│  │ agentic loop    │        ┌──────▼───────┐            ┌───────▼─────┐ │
│  └─────┬───────────┘        │ 端侧推理      │            │ Retrofit    │ │
│        │ 每步埋 trace        │ JNI→llama.cpp │            │  ──HTTP──┐  │ │
│  ┌─────▼───────────┐        │ (量化小模型)  │            └──────────┼──┘ │
│  │ 端上 Trace +     │        └──────┬───────┘                       │    │
│  │ 设备指标 (Room)  │        ┌──────▼───────┐  ┌─────────────────┐  │    │
│  └─────────────────┘        │ 本地工具      │  │ 本地 RAG         │  │    │
│  ┌─────────────────┐        │ device/notes  │  │ embed+sqlite-vec │  │    │
│  │ Eval Runner      │        └───────────────┘  └─────────────────┘  │    │
│  └─────────────────┘                                                 │    │
└──────────────────────────────────────────────────────────────────────┼────┘
                                                                         │ 复杂任务/大模型/重工具
                                              ┌──────────────────────────▼──────────┐
                                              │  云半部：精简版 AgentOps (FastAPI)    │
                                              │  agent loop → 云端大模型 → 工具       │
                                              │  trace 落库（[第3篇]）                │
                                              └───────────────────────────────────────┘
```

**一次请求的数据流**：

1. 用户输入 → **router** 判断走本地还是云（Part 5.1）。
2. 走本地：Kotlin agent loop 调 **JNI→llama.cpp** 端侧推理，按需调**本地工具**或查**本地 RAG**。【[端侧:Part5.6](on-device-ai-track.md) + [C++:Part5](cpp-for-on-device-ai.md)】
3. 走云端：Retrofit 调**精简版 AgentOps** 服务，云端跑 agent loop + 大模型。【复用 [AgentOps](agentops-mini-platform-design.md)】
4. 每一步埋 **trace**，且端侧步骤额外记**设备指标**（首 token 延迟/吞吐/峰值内存/功耗/后端/路由）。【[第3篇] + [端侧:Part5.5](on-device-ai-track.md)】
5. **Eval Runner** 拿一个任务集，跑不同配置（量化档/后端/路由），出**质量 + 设备指标**对比报告。【[第4篇]】

---

## 3. 技术栈（MVP 用什么 vs 后续）

| 组件 | MVP 选型 | 后续升级 | 来自 |
|---|---|---|---|
| App / UI | **Kotlin + Compose** | — | 你已会 |
| 端侧推理 | **llama.cpp + JNI**（GGUF 量化模型）| MNN-LLM / ExecuTorch；NPU(QNN) | [端侧:5.3/5.4] + [C++ 篇] |
| Agent 编排 | **Kotlin 自研 agent loop** | — | [第2篇] 同构 |
| 本地工具 | 2–3 个（device_status / search_notes）| 更多端侧工具 | [第2篇] |
| 本地 RAG | **端上 embedding + sqlite-vec** | ObjectBox 向量 | [端侧:5.6] |
| 端云路由 | **规则 router**（难度/隐私/网络）| 小模型分类器路由 | [端侧:5.7] |
| 云半部 | **精简版 AgentOps**（FastAPI 自研 loop + trace）| 全量 AgentOps | [AgentOps 蓝图] |
| Trace 存储 | **Room / SQLite**（端）+ Postgres（云）| OTel | [第3篇] + [端侧:5.5] |
| Eval | **自研**（质量 + 设备指标）| promptfoo | [第4篇] |

**原则**：端半部是主角、是护城河，先把它打磨到能演示；云半部**够用就行**（一个能响应的 agent 接口即可，别把 AgentOps 全量重做）。

---

## 4. 关键数据结构：带设备指标的端上 Trace ★

这是 PocketAgent 区别于普通 Agent demo 的**核心资产**——别人 trace 只有 token/cost，你的 trace 有**真机设备指标**。

**端上 Span（一步执行）** —— 在 [第3篇] 的 Span 基础上，加端侧专属字段：

| 字段 | 说明 | 来源 |
|---|---|---|
| `step_id` / `trace_id` / `parent` | 串起一次执行 | [第3篇] |
| `type` | `llm_local` / `llm_cloud` / `tool` / `route` | — |
| `route` | `local` / `cloud` + `reason`（为什么这么判）| [端侧:5.7] |
| `backend` | `cpu` / `gpu` / `npu`（端侧步骤）| [端侧:5.4] |
| `model` / `quant` | 如 `Qwen2.5-1.5B` / `Q4_K_M` | [端侧:5.2] |
| `first_token_ms` / `total_ms` / `tok_per_s` | 延迟与吞吐 | [端侧:5.5] |
| `peak_mem_mb` / `temp_c` / `energy_proxy` | 内存 / 发热 / 功耗代理 | [端侧:5.5] |
| `tool_name` / `args` / `result` / `error` | 工具调用 | [第2篇] |
| `tokens_in/out` / `cost` | 云端步骤计费 | [第3篇] |

> **关键直觉**：这张表就是你简历最硬的料。"我能拿出一条 trace，说清这次请求走了本地还是云、用的哪个量化档、首 token 多少毫秒、峰值内存多少、烫不烫"——这是同时懂模型和懂手机的人才给得出的东西。

---

## 5. 核心模块设计

### 5.1 端云 router（项目的灵魂）★

router 决定每个请求走本地还是云。MVP 用**规则**即可，但要能讲清判据：

```
走本地，当：任务简单（短问答/格式化/本地数据相关）
          或 涉及隐私（本地文件/聊天）
          或 离线 / 弱网
走云端，当：任务复杂（多步推理/长文/需要大模型能力）
          或 本地模型置信度低 / 本地工具搞不定
降级：     云不可用 → 回退本地兜底；本地失败 → 升级云端
```

> 面试高频追问就是"你怎么决定端 vs 云"。把这套判据 + 降级策略讲明白，是端侧 Agent 岗的核心设计题（[端侧:5.7]）。

### 5.2 端侧 agent loop + 本地工具

Kotlin 侧实现一个最小 agentic loop（和 [第2篇] 的循环同构，只是"调模型"走 JNI 而非 HTTP）：

```
loop：本地模型输出 → 解析是否要调工具 → 调本地工具 → 结果回填 → 再推理 → 直到完成
```

- **小模型工具调用要加约束**：小模型乱填参数，用结构化输出 / 语法约束兜（[端侧:5.6]）。
- 起步两个本地工具就够：`get_device_status`（演示端侧特性）、`search_notes`（接本地 RAG）。

### 5.3 本地 RAG

端上 embedding 模型 + `sqlite-vec` 存向量，`search_notes` 工具做离线检索本地文档/笔记，把命中片段塞回 prompt。全程不联网。

### 5.4 云半部（精简版 AgentOps）

**别重做整个 AgentOps**。一个 FastAPI 服务，提供一个 `POST /agent/run`：收到任务 → 跑云端 agent loop（大模型 + 工具）→ 返回结果 + trace 摘要。复用 [AgentOps 蓝图](agentops-mini-platform-design.md) 的 agent/trace 代码，砍掉异步队列/前端等重组件。

---

## 6. 目录结构（骨架）

```
pocketagent/
├── app/                         # Android App（端半部）
│   └── src/main/
│       ├── java/.../
│       │   ├── agent/Loop.kt            # 端侧 agentic loop        [第2篇]同构
│       │   ├── agent/Router.kt          # 端云路由                 [端侧:5.7]
│       │   ├── agent/Tools.kt           # 本地工具 dispatcher      [第2篇]
│       │   ├── infer/LlamaBridge.kt     # external 声明 + loadLibrary [C++:5]
│       │   ├── rag/LocalRag.kt          # embedding + sqlite-vec   [端侧:5.6]
│       │   ├── trace/SpanDao.kt         # Room：带设备指标的 trace [第3篇]+[端侧:5.5]
│       │   ├── eval/EvalRunner.kt       # 端上评测                 [第4篇]
│       │   └── net/CloudApi.kt          # Retrofit → 云半部
│       └── cpp/
│           ├── native-lib.cpp           # JNI 实现                 [C++:5]
│           └── CMakeLists.txt           # 链 llama.cpp             [C++:6]
└── cloud/                       # 云半部（精简 AgentOps）
    ├── main.py                  # FastAPI: POST /agent/run        [第1篇]
    ├── agent/loop.py            # 云端 agent loop                 [第2篇]
    ├── agent/trace.py           # trace 埋点                      [第3篇]
    ├── Dockerfile               #                                 [第5篇]
    └── docker-compose.yml       # 一键起云半部                    [第5篇]
```

---

## 7. 分阶段里程碑（怎么一步步做出来）★★

**最重要的部分。** 每个里程碑能跑能演示，做完再下一个。括号里标了和端侧轨道 / C++ 篇的对应，三线同步推进。

### M0 · 端侧出 token（壳）
- **做什么**：Android 工程接 llama.cpp（JNI + CMake），加载一个 GGUF 量化模型，最朴素的本地 chat。
- **起点**：用现成脚手架 [`pocketagent/`](../pocketagent/)（CMake + JNI + llama.cpp + 流式桥接已接好），照它的 README 走——clone llama.cpp + 塞模型即可跑。
- **验收**：飞行模式下 App 里蹦出第一个 token。
- **对应**：[端侧 M0/M2] + [C++ C1]

### M1 · 端侧 Agent（本地 loop + 工具）
- **做什么**：Kotlin 写 agentic loop；加 `get_device_status` + `search_notes` 两个本地工具；小模型工具调用加结构化约束。
- **验收**：离线问"我手机现在内存占用多少"，模型正确调本地工具并作答。
- **对应**：[第2篇] + [端侧:5.6] + [C++ C2]

### M2 · 端上 Trace + 设备指标
- **做什么**：每步埋 trace 存 Room；端侧步骤记 Part 4 那套设备指标；UI 看一次执行的 timeline。
- **验收**：跑完能查到：走了哪条路、哪个后端、首 token 延迟、峰值内存、token/s。
- **对应**：[第3篇] + [端侧:5.5]

### M3 · 端云路由（接云半部）
- **做什么**：起精简版 AgentOps（`POST /agent/run`，Docker 一键）；Retrofit 接上；实现 `Router`（规则判据 + 降级）。
- **验收**：简单问题本地答、复杂问题自动转云端；拔网时云端请求优雅降级回本地。
- **对应**：[端侧:5.7] + 复用 [AgentOps] + [第5篇]

### M4 · 本地 RAG
- **做什么**：端上 embedding + sqlite-vec；`search_notes` 真正检索一批本地文档；命中片段进 prompt。
- **验收**：离线问一个只有本地文档里才有的事实，模型能检索到并答对。
- **对应**：[端侧:5.6]

### M5 · Eval + 多配置对比（出报告）
- **做什么**：建一个 15–20 条任务集；EvalRunner 跑不同配置——**量化档**（Q4 vs Q8）、**后端**（CPU vs GPU/NPU）、**路由策略**（纯本地 vs 端云 vs 纯云）；统计质量 + 设备指标。
- **验收**：一张对比表：每种配置的成功率、工具准确率、首 token 延迟、峰值内存、本地命中率、云端成本；得出"哪种配置在质量/延迟/功耗上最优"的结论。
- **对应**：[第4篇] + [端侧:5.5]

> 做完 M0–M5，你就有了一个**真机可演示、有设备指标、有对比报告**的端云协同 Agent。这正是端侧轨道 Part 2 的"最小胜任标准"的超额完成版。

---

## 8. 关键设计决策 & 取舍（FAQ）

- **为什么不把整个 AgentOps 搬到云半部？** 云半部只是"协同对象"，不是主角。一个能响应的 `POST /agent/run` 足够证明你会后端/Agent，精力要花在端半部的护城河上。
- **端侧选哪个模型 / 量化档？** 起步用 1.5B~3B + `Q4_K_M`（[端侧:3.3] 那张内存表）。M5 再系统对比不同档。
- **端侧 agent loop 自研还是找库？** 自研——和 [第2篇] 同构、简单可控、好埋 trace，也最能讲清"端侧 Agent 怎么转"。
- **router 用规则还是模型？** MVP 规则就够，讲得清判据最重要；后续可升级成"用本地小模型做意图分类再路由"，那本身又是个亮点。
- **怎么防止做成无底洞？** 严格 M0–M5，每步能演示。端侧一切**以真机为准**，别在模拟器上耗。

---

## 9. 怎么在简历 / 面试里讲它

不要说"我做了个端侧 chat demo"，而是：

> 我做了一个**端云协同的 Android Agent（PocketAgent）**：端侧用 llama.cpp + JNI 跑量化小模型（Q4），实现本地 agentic loop、工具调用和基于 sqlite-vec 的本地 RAG；设计了端云 router，按任务难度/隐私/网络决定本地推理还是降级到云端（精简版 FastAPI Agent 服务）；每步 trace 都带**设备指标**（首 token 延迟、峰值内存、后端、路由）；并用 eval 对比了不同量化档、CPU/NPU 后端、路由策略在质量与功耗上的取舍。

能展开的关键词（每个都对应文档某节，追问答得出）：

| 关键词 | 你能讲的点 |
|---|---|
| 端侧推理 | JNI→llama.cpp、量化档选择、加载/上下文/采样流程（[C++:4]）|
| 端云路由 | 判据、降级、本地命中率、成本权衡（[端侧:5.7]）|
| 设备指标 trace | 首 token 延迟/内存/功耗怎么测、怎么埋（[端侧:5.5]）|
| 端侧 Agent | 小模型工具调用的约束解码、端上 RAG（[端侧:5.6]）|
| Eval | 多配置对比、质量 vs 延迟 vs 功耗的取舍（[第4篇]）|
| JNI / NDK | native 集成、流式回传、崩溃定位（[C++:5/7]）|

---

## 10. 总验收清单 & 下一步

对照端侧轨道"最小胜任标准"逐项打勾——做完应能勾满：

- [ ] 真机离线跑起量化模型（端侧推理）
- [ ] 端侧 agent loop + 本地工具调用
- [ ] 端上 trace 带设备指标（延迟/内存/功耗/后端/路由）
- [ ] 端云 router + 降级
- [ ] 本地 RAG（离线检索）
- [ ] 多配置 eval 对比报告（量化/后端/路由）
- [ ] CPU/GPU/NPU 至少两种后端的对比数据

**下一步扩展**（做完 MVP 再挑着加）：

- **NPU 后端**：接高通 QNN / MTK NeuroPilot，补齐三后端对比（[端侧:5.4]）。
- **落地现职**：把其中一个能力（如端侧总结 / 本地搜索）推进你真实的 App（端侧轨道 Part 7 的"把现职变成作品集"）——**这一步价值最高**。
- **路由升级**：规则 router → 小模型意图分类路由。

---

## 参考（回到各篇）

**端侧轨道**
1. [端侧 AI 学习轨道](on-device-ai-track.md) —— 推理/量化/NPU/profiling/端云协同
2. [C++ 补齐计划](cpp-for-on-device-ai.md) —— JNI、llama.cpp 集成

**主线（云半部 + Agent 基础）**
3. [LLM API 与 Tool Calling](llm-api-and-tool-calling.md) —— agent loop、工具调用
4. [Agent Trace 与可观测性](agent-trace-and-observability.md) —— trace/span 数据结构
5. [Agent Eval：评测与回归](agent-eval-and-metrics.md) —— 评测集、指标、对比
6. [Docker 部署 Agent 服务](docker-deploy-agent-service.md) —— 云半部容器化
7. [AgentOps Mini Platform](agentops-mini-platform-design.md) —— 云半部直接复用它的精简版

> **一句话收尾**：AgentOps 让你证明"会做 Agent"，PocketAgent 让你证明"会做**别人做不了的**端云协同 Agent"。把 M0–M5 走完，端侧轨道就有了一个压得住场的收官作品。
