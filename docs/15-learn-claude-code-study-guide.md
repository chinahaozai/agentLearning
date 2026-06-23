# learn-claude-code 学习梳理

## 这份资料适合学什么

`shareAI-lab/learn-claude-code` 的核心价值不是教你“写一个很会思考的模型”，而是教你做 **Agent Harness 工程**。

可以把它理解成：

```text
Agent 产品 = 模型 + Harness

Harness = 工具系统 + 上下文 + 权限 + 任务系统 + 记忆 + 调度 + 多 Agent 协作 + 外部协议
```

模型负责推理和决策，harness 负责给模型一套可以观察世界、调用工具、保存状态、控制风险、持续执行任务的运行环境。

对你当前从 Android / Java 转向 Agent 工程的路线来说，这个仓库最值得学的是：

- 怎么把 LLM tool calling 变成可持续运行的 agent loop
- 怎么设计 tool registry、权限、hook、trace、上下文压缩
- 怎么把一次性对话扩展成任务系统、后台任务、定时任务、多 agent 协作
- 怎么理解 Claude Code / Codex 这类 coding agent 背后的工程结构
- 怎么把“会调用大模型 API”升级成“能搭 agent runtime”

## 学习前提

建议先具备这些基础，再开始精读：

- Python 基础语法、函数、字典、列表、异常处理
- LLM API 基础：messages、system prompt、tool calling、streaming
- 终端和文件系统基础：bash、cwd、文件读写、环境变量
- 对 Agent 的基本直觉：模型不是直接执行操作，而是通过工具请求行动

如果这些还不稳，可以先读本仓库：

- [Python + FastAPI 阅读理解指南](09-python-fastapi-essentials.md)
- [LLM API 与 Tool Calling 阅读理解指南](10-llm-api-and-tool-calling.md)
- [Agent Trace 与可观测性：数据结构与回放](11-agent-trace-and-observability.md)

## 总体学习策略

不要把它当成“文章合集”读。更好的方式是：

1. 先读每章 README，画出这一章新增的 harness 机制。
2. 再读 `code.py`，只关注它相对上一章多了什么。
3. 跑一遍示例，用一个具体任务观察 agent 行为。
4. 把这一章机制复刻到自己的 mini agent 里。
5. 最后写一条学习笔记：输入是什么、状态是什么、输出是什么、失败会怎样。

每章都问自己五个问题：

- 这一章解决了 agent runtime 的哪个痛点？
- 它新增了什么数据结构？
- 它新增了什么工具或 hook？
- 它放在 agent loop 的哪个位置？
- 如果放到生产系统里，还缺哪些安全、评测、持久化和可观测性？

## 四阶段路线

### 阶段一：单 Agent 最小闭环

对应章节：`s01` 到 `s05`

目标：理解 coding agent 的最小运行骨架。

| 章节 | 主题 | 你要真正吃透的点 |
| --- | --- | --- |
| `s01_agent_loop` | Agent Loop | messages 如何在 LLM、tool use、tool result 之间循环 |
| `s02_tool_use` | Tool Registry | 新增工具时，不改主循环，只扩展工具定义和 handler |
| `s03_permission` | Permission | 工具执行前必须经过权限判断，尤其是 bash、写文件、删除操作 |
| `s04_hooks` | Hooks | 把日志、权限、拦截、提示等横切逻辑挂到循环上 |
| `s05_todo_write` | TodoWrite | 计划不是执行能力，但会显著改善长任务完成率 |

阶段一练习：

- 实现一个最小 `agent_loop(messages)`。
- 支持 `bash`、`read_file`、`write_file` 三个工具。
- 给 `bash` 加权限判断：读操作自动允许，写操作/删除操作需要人工确认。
- 给工具调用前后加 hook，记录 `tool_name`、`arguments`、`latency`、`result_size`。
- 加一个 `todo_write` 工具，让模型先列计划再执行。

验收标准：

- 能让 agent 读取一个小项目、运行测试、修改一个简单 bug。
- 每一次工具调用都有结构化记录。
- 危险命令不会被静默执行。

## 阶段二：上下文、技能、记忆和恢复

对应章节：`s06` 到 `s11`

目标：解决长任务中最常见的问题：上下文污染、上下文爆炸、知识加载、错误恢复。

| 章节 | 主题 | 你要真正吃透的点 |
| --- | --- | --- |
| `s06_subagent` | Subagent | 子 agent 用干净上下文处理局部任务，只把结果带回主上下文 |
| `s07_skill_loading` | Skill Loading | 技能目录常驻，技能内容按需加载，避免 prompt 一开始塞爆 |
| `s08_context_compact` | Context Compact | 压缩不是一种操作，而是一组分层策略 |
| `s09_memory` | Memory | 记忆要有筛选、提取、整理，不等于把所有历史都塞回 prompt |
| `s10_system_prompt` | System Prompt | system prompt 应该运行时组装，而不是一整块硬编码 |
| `s11_error_recovery` | Error Recovery | 截断、上下文超限、临时故障都应该进入恢复路径 |

阶段二练习：

- 实现一个 `spawn_subagent(task)`，让子 agent 单独读文件并返回摘要。
- 做一个 `skills/` 目录，每个 skill 有 `SKILL.md`，启动时只加载 name 和 description。
- 给大工具结果做落盘，只把路径和摘要放回上下文。
- 做一次简单 compact：保留最近 N 轮，把旧工具结果替换成摘要。
- 把 system prompt 拆成 sections，例如：identity、tool_rules、permission_rules、project_context。
- 处理三类错误：模型输出截断、上下文过长、工具执行失败。

验收标准：

- agent 能处理超过一次上下文压缩的长任务。
- 技能不会全部预加载，但 agent 能按需发现和加载。
- 工具失败后 agent 不会直接崩掉，而是能重试或换方案。

## 阶段三：任务系统、后台任务和调度

对应章节：`s12` 到 `s14`

目标：把“对话式 agent”推进到“可持续执行任务的 agent runtime”。

| 章节 | 主题 | 你要真正吃透的点 |
| --- | --- | --- |
| `s12_task_system` | Task System | 大目标要拆成可持久化、可依赖、可认领、可完成的小任务 |
| `s13_background_tasks` | Background Tasks | 慢操作不能阻塞 agent 思考，完成后要把通知重新注入上下文 |
| `s14_cron_scheduler` | Cron Scheduler | agent 可以由时间触发，而不只由用户 prompt 触发 |

阶段三练习：

- 设计 `Task` 数据结构：`id`、`title`、`status`、`dependencies`、`owner`、`result`。
- 实现 `create_task`、`claim_task`、`complete_task`、`get_task`。
- 把长命令放到后台线程或任务队列中执行。
- 后台任务完成后，把结果作为新的 user message 注入 agent loop。
- 实现一个最小 cron：每分钟检查一次待触发任务。

验收标准：

- agent 能把一个大需求拆成多个任务并按依赖执行。
- 长时间测试或构建不会卡死主循环。
- 定时任务能自动触发一次 agent 执行。

## 阶段四：多 Agent、隔离和外部工具协议

对应章节：`s15` 到 `s20`

目标：理解 Claude Code / Codex 这类 coding agent 为什么需要团队、协议、worktree、MCP。

| 章节 | 主题 | 你要真正吃透的点 |
| --- | --- | --- |
| `s15_agent_teams` | Agent Teams | 多 agent 不只是多个线程，而是要有消息系统和权限冒泡 |
| `s16_team_protocols` | Team Protocols | 队友之间必须有明确的请求、响应、确认、关闭协议 |
| `s17_autonomous_agents` | Autonomous Agents | 子 agent 可以从任务看板自主认领工作 |
| `s18_worktree_isolation` | Worktree Isolation | 并行改代码必须隔离目录和分支，否则互相踩踏 |
| `s19_mcp_plugin` | MCP Tools | 外部工具应该通过标准协议接入统一工具池 |
| `s20_comprehensive` | Comprehensive Agent | 所有机制最终还是回到一个 agent loop |

阶段四练习：

- 用文件或 SQLite 做一个简单 message bus。
- 实现 lead agent 和 worker agent：lead 拆任务，worker 认领任务并回报结果。
- 给每个 worker 分配独立工作目录。
- 用 git worktree 为并行任务创建隔离分支。
- 接入一个最小 MCP server，把 MCP tool 映射到本地 tool registry。
- 把前面所有机制整合成一个小型 coding agent runtime。

验收标准：

- 两个 worker 可以并行处理两个互不冲突的任务。
- worker 的权限请求能冒泡给 lead 或用户。
- 每个任务的文件修改可以独立 review。
- 外部工具能通过 MCP 被发现和调用。

## 和你当前路线的衔接

这个仓库可以补强你已有路线中的几个关键模块。

| 你当前文档 | learn-claude-code 对应章节 | 衔接方式 |
| --- | --- | --- |
| `10-llm-api-and-tool-calling.md` | `s01`、`s02` | 从 tool calling API 进入 agent loop |
| `11-agent-trace-and-observability.md` | `s03`、`s04`、`s13` | 把工具调用、hook、后台任务都纳入 trace |
| `12-agent-eval-and-metrics.md` | `s05`、`s11`、`s12` | 评估计划质量、错误恢复率、任务完成率 |
| `14-agentops-mini-platform-design.md` | `s12` 到 `s20` | 任务系统、多 agent、MCP、worktree 是平台化核心 |
| `07-on-device-agent-rag.md` | `s07`、`s08`、`s09` | skill、compact、memory 可以迁移到端侧 agent |
| `08-on-device-edge-cloud-routing.md` | `s03`、`s10`、`s11` | 权限、prompt 组装、fallback 可用于端云路由 |

## 建议产出物

学完不要只留下读书笔记，最好留下可以展示的工程产物。

### 产出物一：Mini Coding Agent

最低功能：

- agent loop
- bash/read/write/edit 工具
- tool registry
- permission gate
- hook trace
- todo_write
- context compact

这对应 `s01` 到 `s08`，是最小作品集。

### 产出物二：AgentOps Trace Viewer

最低功能：

- 每轮模型调用落 trace
- 每次工具调用落 trace
- 记录 token、latency、cost、error
- 支持按 task id 回放执行过程

这和你已有的 AgentOps Mini Platform 路线最贴。

### 产出物三：Task-based Multi-Agent Runtime

最低功能：

- task graph
- worker agent
- message bus
- worktree isolation
- MCP tool 接入

这对应 `s12` 到 `s20`，难度更高，但也更能体现“agent 平台工程”能力。

## 每章学习记录模板

建议每章读完后按这个格式记：

```markdown
## sXX 章节名

### 解决的问题

这章解决 agent runtime 中的什么痛点？

### 新增机制

- 数据结构：
- 工具：
- hook：
- 状态：

### 放在 loop 的哪里

说明它发生在 user input 前、LLM call 前、tool call 前、tool result 后，还是任务结束后。

### 我自己的复刻

我在 mini agent 中怎么实现？

### 生产化缺口

- 安全：
- 持久化：
- 并发：
- trace：
- eval：
```

## 推荐节奏

如果每天能投入 1 到 2 小时，建议用 3 周推进。

### 第 1 周：跑通单 agent

- Day 1：`s01`、`s02`
- Day 2：`s03`、`s04`
- Day 3：`s05`
- Day 4：复刻 mini agent loop
- Day 5：补 trace 和权限
- Day 6-7：用一个真实小项目测试

### 第 2 周：补上下文工程

- Day 1：`s06`
- Day 2：`s07`
- Day 3：`s08`
- Day 4：`s09`
- Day 5：`s10`、`s11`
- Day 6-7：把 skill、compact、memory 加到 mini agent

### 第 3 周：平台化机制

- Day 1：`s12`
- Day 2：`s13`、`s14`
- Day 3：`s15`、`s16`
- Day 4：`s17`、`s18`
- Day 5：`s19`、`s20`
- Day 6-7：整理成 AgentOps Mini Platform 的二期设计

## 阅读时要保持的警惕

这个仓库很适合作为工程拆解材料，但阅读时要注意三点：

1. 它是教学实现，不是生产实现。权限、沙箱、并发、持久化都需要你自己加固。
2. 它强烈围绕 Claude Code 的产品形态展开，迁移到通用业务 agent 时要抽象出机制，而不是照搬命名。
3. 它的重点是 harness。不要把所有问题都交给 prompt，工具设计、状态管理、评测和权限才是工程壁垒。

## 最重要的学习收获

学完这套资料，你应该形成一个核心判断：

> Agent 工程的核心不是“写更复杂的流程图”，而是给模型一个可靠、可观察、可恢复、可控的行动环境。

当你能自己实现并解释下面这些东西时，就算真正入门了：

- agent loop
- tool registry
- permission gate
- hook system
- todo/task system
- context compact
- memory
- dynamic system prompt
- error recovery
- background task
- message bus
- worktree isolation
- MCP tool integration

这些能力叠起来，就是 coding agent、AgentOps 平台、端侧 agent 和企业业务 agent 的共同底座。
