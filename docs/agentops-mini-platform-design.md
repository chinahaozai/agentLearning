# AgentOps Mini Platform 项目设计文档（作品集蓝图）

## 0. 这份文档怎么用

这是系列的**收官篇**，性质和前五篇不同：前五篇是**教概念 / 看懂代码**，这篇是 **施工图**——把前五篇学的东西收束成一个**你现在就能动手做、做完能在面试里讲清楚**的完整项目。

**几个框架选择，先讲明（重要）：**

- **后端优先、自研为主、分阶段长**：用你已经学会的那五块（FastAPI、自研 agent loop、自研 trace/eval、Docker）先拼出一个**端到端能跑的 MVP**，再按里程碑往全功能长。
- **React 前端、LangGraph 暂不依赖**：它们是路线图后面才学的（前端 #7、Agent 框架 #6），所以放到“后续阶段”。MVP 阶段，**FastAPI 自带的 `/docs` + curl 就能验证全部后端能力**，前端是锦上添花。
- **目标**：避免把作品集做成无底洞。**先求“端到端打通”，再求“功能丰富”。**

**怎么用**：照 **Part 7 的里程碑**一步步做，每个里程碑都标了“对应哪篇文档、怎么验收”。Part 4/5/6（数据模型、API、目录结构）是动手时的参照图。

> 全文出现的 `[第N篇]` 都链到本系列对应文档，动手时回去查具体代码。

---

## 1. 这个平台是什么

**AgentOps Mini Platform** = 一个迷你的“Agent 运维平台”：让你能**配置一个 Agent（模型/提示词/工具）→ 提交任务异步执行 → 回看每次执行的 trace 时间线 → 跑评测集 → 对比两个版本谁更好 → 一键部署**。

说白了，它把前五篇的五件套，变成一个**有接口、有数据、能演示**的产品：

> **FastAPI（造服务）→ LLM/工具（让模型干活）→ Trace（记录）→ Eval（评判）→ Docker（部署）** —— 五块拼成一个平台。

**为什么是它**：路线图把它列为最推荐的作品集项目，因为**一个项目就覆盖了 Agent 全栈的几乎所有关键词**（配置、异步执行、可观测、评测、成本统计、容器化部署），面试时一句话能展开一大片（Part 9）。

---

## 2. 整体架构

```
                  ┌──────────────────────────────────────────────┐
   你 / 前端 ──▶  │  FastAPI app (容器)                            │
  (/docs, curl)   │   /agents  配置 Agent                          │
                  │   /tasks   提交任务 ──┐                         │
                  │   /traces  看执行轨迹  │ 入队                    │
                  │   /eval-*  跑评测/对比 │                        │
                  └────────────┬──────────┼────────────────────────┘
                               │ 读写      │ 入队/查状态
                        ┌──────▼─────┐  ┌──▼──────┐
                        │ Postgres   │  │ Redis    │ (队列+缓存)
                        │ agents     │  └──┬───────┘
                        │ tasks      │     │ 取任务
                        │ traces     │  ┌──▼─────────────────────────┐
                        │ spans      │◀─│  Worker (容器, 同一镜像)     │
                        │ eval_*     │  │   run_agent_job():          │
                        └────────────┘  │     agent loop ─▶ LLM API   │
                               ▲        │              └▶ Tools        │
                               └────────│   每步埋 trace, 写 Postgres  │
                                  写入   └─────────────────────────────┘
```

**一条任务的数据流**（把五件套串起来）：

1. 你 `POST /agents` 配好一个 Agent（模型、system prompt、工具）→ 存 `agents` 表。【[第1篇] + [第2篇]】
2. `POST /tasks` 提交一个任务 → app **入队到 Redis**，立刻返回 `task_id`（状态 `queued`）。【[第5篇]】
3. **Worker** 从队列取出任务，跑 **agent loop**：循环调模型、执行工具。【[第2篇]】
4. loop 的每一步都**埋 trace**（一次模型调用/工具调用 = 一个 span），跑完把 trace 写进 **Postgres**。【[第3篇]】
5. 你 `GET /tasks/{id}` 轮询拿结果，`GET /traces/{id}` 看**执行时间线**（每步的 tool call、token、cost、latency）。【[第3篇] + [第5篇]】
6. `POST /eval-runs` 拿一个评测集在某个 Agent 版本上批量跑，出**成功率/工具准确率/成本**等指标；换个版本再跑一次，**对比两版**。【[第4篇]】
7. 全套用 `docker compose up` **一键启动**。【[第5篇]】

---

## 3. 技术栈（MVP 用什么 vs 后续升级）

| 组件 | MVP 选型（你已会的）| 后续升级方向 | 来自 |
|---|---|---|---|
| 后端框架 | **FastAPI** | — | [第1篇] |
| Agent 执行 | **自研 agent loop** | LangGraph / OpenAI Agents SDK（路线图 #6）| [第2篇] |
| 可观测 / Trace | **自研 trace（Trace/Span）** | OpenTelemetry / Langfuse | [第3篇] |
| 评测 / Eval | **自研 eval（规则 + LLM-judge）** | promptfoo / DeepEval | [第4篇] |
| 数据库 | **Postgres**（开发期可先 SQLite）| — | [第3篇]/[第5篇] |
| 队列 / 异步 | **Redis + RQ** | Celery | [第5篇] |
| 部署 | **Docker Compose** | Kubernetes（路线图 #9）| [第5篇] |
| 前端 | **FastAPI `/docs` + 极简页面 / curl** | React + TypeScript（路线图 #7）| 后续 |

**原则**：MVP 全部用“你已经理解原理”的自研/轻量方案；框架和平台是**讲得清原理之后的平替**，不是前置条件。

---

## 4. 数据模型（Postgres 表设计）★

这是整个项目的骨架。每张表都直接对应你学过的某个结构：

**Agent 配置**（支持版本，为“对比两版”做准备）
| 表 | 关键字段 | 说明 / 来自 |
|---|---|---|
| `agents` | `id`, `name`, `model`, `system_prompt`, `tools`(JSON), `version`(int), `created_at` | 一行 = 一个 Agent 配置版本。同名 `name` 下 `version` 递增 = 同一 Agent 的不同版本。【[第2篇]配置项】 |

**任务执行**（异步）
| 表 | 关键字段 | 说明 / 来自 |
|---|---|---|
| `tasks` | `id`, `agent_id`, `input`, `status`(queued/running/done/error), `result`, `trace_id`, `created_at` | 一行 = 一次任务执行。`status` 给前端轮询用。【[第5篇]Task API + worker】 |

**Trace（可观测）**
| 表 | 关键字段 | 说明 / 来自 |
|---|---|---|
| `traces` | `trace_id`, `task_id`, `input`, `status`, `started_at`, `ended_at`, `total_tokens_in`, `total_tokens_out`, `total_cost` | 一行 = 一次执行的总账。【[第3篇] Trace】 |
| `spans` | `span_id`, `trace_id`, `parent_span_id`, `type`(llm/tool), `name`, `input`(JSON), `output`(JSON), `error`, `started_at`, `ended_at`, `latency_ms`, `tokens_in`, `tokens_out` | 一行 = 一步。【[第3篇] Span】 |

**Eval（评测）**
| 表 | 关键字段 | 说明 / 来自 |
|---|---|---|
| `eval_datasets` | `id`, `name`, `created_at` | 一个评测集。【[第4篇]】 |
| `eval_cases` | `id`, `dataset_id`, `input`, `expected_output`, `expected_tools`(JSON), `tags`(JSON) | 一条评测用例 = `EvalCase`。【[第4篇]】 |
| `eval_runs` | `id`, `dataset_id`, `agent_id`, `label`, `success_rate`, `tool_accuracy`, `avg_latency_ms`, `avg_cost`, `created_at` | 一次“拿某数据集在某 Agent 版本上跑”的汇总。【[第4篇]】 |
| `eval_results` | `id`, `eval_run_id`, `case_id`, `passed`, `output_ok`, `tools_ok`, `latency_ms`, `cost`, `note` | 一条用例的打分 = `EvalResult`。【[第4篇]】 |

**关系一览**：
```
agents 1 ── * tasks 1 ── 1 traces 1 ── * spans
agents 1 ── * eval_runs * ── 1 eval_datasets 1 ── * eval_cases
eval_runs 1 ── * eval_results * ── 1 eval_cases
```

> **版本对比怎么落地**：同名 Agent 存成多行（`version` 不同）。对比两版 = 同一个 `dataset` 各跑一次 `eval_run`（分别绑不同 `agent_id`），并排两行的指标（`success_rate` / `tool_accuracy` / `avg_cost`）。

---

## 5. API 设计（FastAPI 路由）★

按功能分组，正好覆盖路线图的功能清单：

| 方法 & 路径 | 作用 | 来自 |
|---|---|---|
| `POST /agents` | 新建 Agent（或同名 +1 版本）| [第1篇][第2篇] |
| `GET /agents` / `GET /agents/{id}` | 列出 / 查看 Agent 配置 | [第1篇] |
| `POST /tasks` | 提交任务 → **入队**，返回 `task_id`（不阻塞）| [第5篇] |
| `GET /tasks/{id}` | 查任务状态 / 结果（前端轮询）| [第5篇] |
| `GET /tasks` | 执行列表 | [第1篇] |
| `GET /traces/{trace_id}` | 看执行时间线：spans / tool calls / token / cost / latency | [第3篇] |
| `POST /eval-datasets` / `GET /eval-datasets/{id}` | 建 / 看评测集 | [第4篇] |
| `POST /eval-runs` | 拿某数据集在某 Agent 版本上批量跑，出指标 | [第4篇] |
| `GET /eval-runs/{id}` | 看一次评测的指标 + 逐条结果 | [第4篇] |
| `GET /eval-runs/compare?a={id}&b={id}` | 对比两次评测（两版）| [第4篇] |

**出入参全用 Pydantic 模型**（[第1篇]）；`POST /tasks` 入队、`GET /tasks/{id}` 查状态对应 [第5篇] 的 worker 模式。

---

## 6. 目录结构（项目骨架）★

把 [第1篇] 的项目结构扩成完整平台，每个文件标了出处——**这就是“你学的一切，组装起来的样子”**：

```
agentops/
├── docker-compose.yml          # app + worker + postgres + redis 编排   [第5篇]
├── Dockerfile                  # 给 app/worker 打镜像                    [第5篇]
├── .dockerignore
├── .env.example                # 列出需要的环境变量(不含真值)            [第2篇][第5篇]
├── requirements.txt            # 依赖清单                                [第1篇]
├── README.md                   # 一键启动说明(docker compose up)
└── app/
    ├── main.py                 # 创建 FastAPI app, 挂载 routers          [第1篇]
    ├── config.py               # 读环境变量(API key / DB / Redis URL)    [第2篇][第5篇]
    ├── db.py                   # 数据库连接 / SQLAlchemy 会话            [第1篇][第5篇]
    ├── models.py               # ORM 模型 = Part 4 那些表                [第1篇]
    ├── schemas.py              # Pydantic 出入参                          [第1篇]
    ├── queue.py                # RQ 队列(连 Redis)                       [第5篇]
    ├── jobs.py                 # run_agent_job(): worker 执行的入口       [第5篇]
    ├── routers/
    │   ├── agents.py           # /agents
    │   ├── tasks.py            # /tasks
    │   ├── traces.py           # /traces
    │   └── evals.py            # /eval-*
    ├── agent/
    │   ├── loop.py             # 自研 agentic loop(run_agent)            [第2篇]
    │   ├── tools.py            # 工具定义 + dispatcher(run_tool)         [第2篇]
    │   └── trace.py            # Trace/Span + span() 埋点上下文管理器     [第3篇]
    └── eval/
        ├── cases.py            # EvalCase / 加载评测集                    [第4篇]
        └── runner.py           # score_case / run_eval                   [第4篇]
```

worker 不需要单独的代码入口——它就是 compose 里跑 `rq worker` 的同一个镜像，执行 `app/jobs.py` 里的 `run_agent_job`（[第5篇]）。

---

## 7. 分阶段里程碑（怎么一步步做出来）★★

**最重要的部分。** 每个里程碑都是一个**能跑、能演示的增量**，做完再做下一个，别跳。

### M0 · 骨架跑起来
- **做什么**：FastAPI 一个 `/health` 接口；写 `Dockerfile` + `docker-compose.yml`（app + postgres + redis）。
- **验收**：`docker compose up` 起来，浏览器打开 `/docs` 能看到接口。
- **对应**：[第1篇] + [第5篇]

### M1 · 配置 Agent + 同步跑一次
- **做什么**：`agents` 表 + `POST/GET /agents`；实现自研 `run_agent`（agent loop + 几个工具，比如 `get_current_time`、`query_order`）；先加一个**同步** `POST /agents/{id}/run` 直接返回结果（先不异步）。
- **验收**：配一个 Agent，跑一句话，能拿到带工具调用的回答。
- **对应**：[第1篇] + [第2篇]

### M2 · 加 Trace
- **做什么**：给 loop 埋点（`span()` 上下文管理器），跑一次产出一条 trace 存进 `traces`/`spans` 表；`GET /traces/{id}` 返回时间线。
- **验收**：跑完能查到这次执行的每一步——调了哪些工具、各自耗时、token、总成本。
- **对应**：[第3篇]

### M3 · 异步化（worker + 队列）
- **做什么**：把 `POST /tasks` 改成**入队即返回 `task_id`**；compose 加 `worker` 服务跑 `rq worker`；worker 执行 `run_agent_job`（跑 agent → 存 trace → 更新 `tasks.status`）；`GET /tasks/{id}` 轮询。
- **验收**：提交任务**立刻**返回 task_id，稍后轮询查到 `done` + 结果 + trace_id。
- **对应**：[第5篇]（worker 模式 ≈ WorkManager）

### M4 · 加 Eval + 版本对比
- **做什么**：`eval_datasets`/`eval_cases` + 录入 10~20 条用例；`POST /eval-runs`（拿数据集在某 Agent 版本上批量跑，算成功率/工具准确率/成本，存 `eval_runs`/`eval_results`）；`GET /eval-runs/compare` 并排两版指标。
- **验收**：同一数据集，跑 v1 和 v2（比如改了 system prompt），出一张对比表；**故意改坏一版**，看成功率掉下来。
- **对应**：[第4篇]

### M5 · 一键部署（+ 可选前端）
- **做什么**：完善 `docker-compose.yml`（卷、env_file、depends_on）；`README` 写清 `docker compose up` 步骤和 `.env.example`；**可选**加个极简前端页面（或直接留 React 作后续）。
- **验收**：别人 clone 下来，填好 `.env`，`docker compose up` 一条命令整套跑起来。
- **对应**：[第5篇]（前端 = 路线图 #7，后续）

> 做完 M0–M5，你就有了一个**端到端可演示、可部署**的 AgentOps Mini Platform。这正是路线图说的“最小胜任标准”（见 Part 10）。

---

## 8. 关键设计决策 & 取舍（FAQ）

- **为什么自研 agent loop，不直接上 LangGraph？** 先懂原理（[第2篇]），框架是后续平替（#6）。自研 loop 简单、可控、好埋 trace，也更能讲清楚“Agent 到底怎么转的”。
- **为什么 MVP 先不做 React？** React 是路线图 #7，你还没学。FastAPI 的 `/docs` + curl 能验证**全部后端能力**，前端是锦上添花，别让它阻塞主线。
- **开发期 SQLite 还是直接 Postgres？** 接了 SQLAlchemy 后两者切换只改连接串（[第5篇]）。**建议直接用 compose 起 Postgres**，免得后面再切；想极快起步也可先 SQLite。
- **trace 自研还是上 Langfuse/OTel？** 自研够用、讲得清原理（[第3篇]），数据结构你自己设计的最熟。后续要接平台，把写库那层换掉即可。
- **怎么防止做成无底洞？** **严格按 M0–M5 走，每个里程碑都能跑能演示。** 先打通端到端，再加功能。不要一上来就纠结前端样式、K8s、多 Agent 协作。

---

## 9. 怎么在简历 / 面试里讲它

回扣路线图的“面试表达方式”——不要说“我做了个调 API 的 demo”，而是：

> 我做了一个 **AgentOps Mini Platform**：支持配置 Agent（模型 / 提示词 / 工具）、**异步**提交任务、回看每次执行的 **trace 时间线**（工具调用、token、成本、延迟）、跑 **eval 数据集**并**对比两个 Agent 版本**的成功率与成本，整套用 **Docker Compose 一键部署**。后端 FastAPI，自研 agentic loop 和 trace，Postgres 存状态，Redis + RQ 跑异步 worker。

能展开的关键词（每个都对应一篇文档，面试官追问你都答得出）：

| 关键词 | 你能讲的点 |
|---|---|
| 异步执行 | 为什么 Agent 要走队列、worker 模式（≈WorkManager）|
| Trace / 可观测 | trace/span 数据结构、怎么埋点、回放时间线 |
| Eval / 评测 | 成功率/工具准确率、LLM-as-judge、版本对比、回归 |
| 成本统计 | 从 trace 汇总 token 与成本 |
| 部署 | Dockerfile / compose、密钥不进镜像、一键启动 |

---

## 10. 总验收清单 & 下一步

**对照路线图的“最小胜任标准”**逐项打勾——做完这个项目，你应该能勾满：

- [ ] 任务提交（`POST /tasks`）
- [ ] 模型调用（agent loop 调 LLM）
- [ ] 工具调用（tool_use → 执行 → 回传）
- [ ] 多步骤执行（loop 转多圈）
- [ ] trace 回放（`GET /traces/{id}` 时间线）
- [ ] eval 测试（`POST /eval-runs` + 指标）
- [ ] 成本统计（trace 汇总 token / cost）
- [ ] Docker 部署（`docker compose up` 一键起）

**下一步扩展**（对应路线图后续优先级，做完 MVP 再挑着加）：

- **React + TypeScript 前端**（#7）：给这套接口加个真正的界面——Agent 列表、trace 时间线可视化、eval 对比图表。
- **LangGraph / OpenAI Agents SDK**（#6）：把自研 `run_agent` 换成框架，体会“checkpoint / 状态编排 / 人工确认”。
- **MCP**（#8）：把工具从写死改成接 MCP 标准服务器，工具变可插拔（[第2篇] 结尾提过）。
- **接 Langfuse / OpenTelemetry**：把自研 trace 的写库层换成标准平台。
- **Kubernetes**（#9）：compose 扛不住时再上。

---

## 参考（回到前五篇）

这份蓝图把下面五篇拼成一个项目，动手时回去查具体代码：

1. [Python + FastAPI 阅读理解指南](python-fastapi-essentials.md) —— 后端骨架、Pydantic、项目结构
2. [LLM API 与 Tool Calling 阅读理解指南](llm-api-and-tool-calling.md) —— agent loop、工具调用
3. [Agent Trace 与可观测性](agent-trace-and-observability.md) —— Trace/Span、埋点、回放
4. [Agent Eval：评测与回归](agent-eval-and-metrics.md) —— 评测集、指标、版本对比
5. [Docker 部署 Agent 服务](docker-deploy-agent-service.md) —— 容器化、worker 队列、一键部署

以及总纲：[Android 开发转 Agent 工程学习路线](agent-learning-roadmap.md)。

> **一句话收尾**：前五篇让你“看得懂、讲得清”，这篇让你“拼得起来、跑得起来”。把 M0–M5 走完，你就从“学过 Agent”变成了“做过一个 Agent 平台”。
