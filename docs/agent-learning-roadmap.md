# Android 开发转 Agent 工程学习路线

## 目标定位

这条路线面向已经有 Android 工程经验，并且使用过 Claude Code、Codex、Cursor 等 AI 编程工具的开发者。

目标不是单纯学会调用大模型 API，而是逐步具备以下能力：

- 能开发一个 Agent 后端服务
- 能为 Agent 定义工具、上下文和执行流程
- 能记录和回放 Agent 执行轨迹
- 能设计基础评测集，衡量 Agent 是否变好
- 能把服务容器化并部署
- 能面向业务系统或前端提供稳定 API

一句话概括：

> 从 App 工程师，升级为能做 Agent 应用和 Agent 平台的全栈工程师。

## 最小胜任标准

如果想初步胜任 Agent 全栈开发工程师或 Agent 架构工程师相关岗位，最低要能独立完成：

> 一个可部署的 Agent 系统：支持任务提交、模型调用、工具调用、多步骤执行、trace 回放、eval 测试、成本统计和 Docker 部署。

这比只会写 prompt 或调用聊天接口更接近真实岗位要求。

## 需要掌握的核心能力

### 1. Python FastAPI 后端

FastAPI 是一个用 Python 编写后端 API 服务的框架。它适合快速开发 LLM 和 Agent 服务。

需要掌握：

- Python 基础语法
- type hints 类型标注
- async / await
- FastAPI 路由
- request body / response model
- Pydantic 数据模型
- 错误处理
- 测试接口
- 环境变量管理
- 服务启动与部署

Android 类比：

- Pydantic model 类似 Kotlin data class 加 JSON schema
- FastAPI endpoint 类似服务端 HTTP 接口
- httpx 类似 OkHttp / Retrofit 客户端
- SQLAlchemy 类似服务端 ORM
- pytest 类似单元测试框架

### 2. LLM 应用开发基础

需要掌握：

- system prompt / user prompt
- context window
- token 计算和成本
- streaming response
- structured output
- JSON schema
- function calling / tool calling
- RAG 基础
- embedding 基础
- 多轮上下文管理
- 模型调用的超时、重试和错误处理

重点不是“能聊”，而是能把模型能力包装成稳定服务。

### 3. Agent 工具系统

Agent 的核心不是模型自己会做事，而是开发者提供工具，模型负责选择和调用工具。

需要掌握：

- tool registry
- tool schema
- 参数校验
- 工具调用权限
- 工具调用失败处理
- retry / timeout
- human approval
- 多步骤执行
- agent state

可以先实现这些工具：

- get_current_time
- read_file
- search_code
- run_tests
- inspect_gradle
- query_order_status
- create_patch

### 4. Agent 框架与协议

建议优先学习：

- LangGraph
- OpenAI Agents SDK
- Anthropic tool use
- MCP
- LangChain 基础概念

重点理解：

- Agent 状态如何保存
- 每一步执行如何编排
- 工具结果如何回传给模型
- 多 Agent 是否真的必要
- checkpoint 如何实现
- 如何支持人工确认

### 5. Trace 与可观测性

真实 Agent 系统一定需要 trace，否则无法调试。

每次执行至少记录：

- task id
- 用户输入
- system prompt
- model name
- model response
- tool name
- tool arguments
- tool result
- error
- latency
- token usage
- cost

建议了解：

- OpenTelemetry
- LangSmith
- Arize Phoenix
- Helicone
- Prometheus
- Grafana
- Sentry

### 6. Eval 评测体系

Eval 是 Agent 岗位的重要分水岭。很多 demo 能跑一次，但不能证明新版比旧版更好。

需要掌握：

- eval dataset 设计
- expected output
- expected tool call
- task success rate
- tool call accuracy
- step accuracy
- latency
- token cost
- regression test
- 自动评分与人工评分结合

基础流程：

1. 准备测试任务集
2. 批量运行 Agent
3. 保存每次 trace
4. 自动或人工评分
5. 对比不同模型、prompt、工具配置
6. 输出评测报告

### 7. Docker 与部署

Agent 系统常涉及代码执行、文件读写、浏览器操作等能力，所以运行环境隔离很重要。

需要掌握：

- Dockerfile
- docker compose
- 环境变量
- 容器日志
- Redis
- Postgres
- worker
- queue
- 基础 Kubernetes 概念

Kubernetes 先理解这些即可：

- Pod
- Deployment
- Service
- ConfigMap
- Secret

## 四周学习路线

### 第 1 周：Python 与 FastAPI 基础

目标：

- 能写基础 API
- 能用 Pydantic 定义请求和响应
- 能启动服务并访问自动文档
- 能写简单测试

学习内容：

- Python 基础语法
- venv / uv / pip
- FastAPI GET / POST
- Pydantic BaseModel
- pytest
- httpx

练习项目：

> Task API

功能：

- 创建任务
- 查询任务
- 更新任务状态
- 删除任务

### 第 2 周：FastAPI 接入 LLM

目标：

- 能把模型能力包装成后端 API
- 能处理超时、错误和日志
- 能实现流式输出

学习内容：

- OpenAI / Anthropic API
- streaming
- structured output
- 环境变量管理
- 日志记录
- latency 统计
- token usage 统计

练习项目：

> Code Review API

功能：

- 输入一段代码
- 返回代码解释
- 返回风险点
- 返回改进建议
- 记录请求耗时和 token 用量

### 第 3 周：Agent Tool Calling 与 Trace

目标：

- 能定义工具
- 能让模型选择工具
- 能保存每一步执行轨迹

学习内容：

- function calling
- tool schema
- tool arguments validation
- trace 数据结构
- SQLite / Postgres
- Agent run lifecycle

练习项目：

> Android Project Analyzer Agent

功能：

- 读取 Gradle 文件
- 分析依赖版本
- 搜索代码
- 总结模块结构
- 给出升级建议
- 保存每次 Agent trace

### 第 4 周：Eval 与 Docker 部署

目标：

- 能批量评测 Agent
- 能对比不同版本效果
- 能用 Docker Compose 启动完整服务

学习内容：

- eval dataset
- 自动评分
- trace 回放
- Dockerfile
- docker compose
- Redis / Postgres
- 后台任务队列

练习项目：

> Agent Eval Mini Platform

功能：

- 创建 eval dataset
- 批量运行 Agent
- 统计任务成功率
- 统计工具调用准确率
- 统计延迟和成本
- 对比两个 Agent 版本
- Docker 一键启动

## 推荐作品集项目

最推荐做一个完整项目：

> AgentOps Mini Platform

核心功能：

- 创建 Agent
- 配置模型
- 配置 prompt
- 配置 tools
- 运行任务
- 查看执行列表
- 查看 trace timeline
- 查看 tool calls
- 查看 token / cost / latency
- 跑 eval dataset
- 对比两个 Agent 版本
- Docker Compose 一键启动

推荐技术栈：

- Frontend: React + TypeScript
- Backend: FastAPI
- Agent: LangGraph 或 OpenAI Agents SDK
- Database: Postgres
- Queue: Redis + RQ / Celery
- Observability: OpenTelemetry 或自研 trace
- Deploy: Docker Compose

这个项目可以覆盖 Agent 全栈开发和 Agent 架构岗位的大部分关键词。

## 学习优先级

时间有限时，按这个顺序学习：

1. Python FastAPI
2. LLM API 与 tool calling
3. trace 数据结构与回放
4. eval dataset 与评测指标
5. Docker / Redis / Postgres
6. LangGraph / OpenAI Agents SDK
7. React 内部平台
8. MCP
9. Kubernetes
10. RL 基础设施概念

RL 不建议最先学。多数工程岗位里，RL 更偏训练和评测基础设施对接，不是要求你先掌握强化学习算法。

## 面试表达方式

不要只说：

> 我是 Android 开发，想转 AI。

可以说：

> 我有客户端工程背景，熟悉 AI 编程工具在真实研发流程中的使用。近期重点补齐了 FastAPI 后端、LLM tool calling、Agent trace、eval 评测和 Docker 部署能力，目标是做 Agent 工程平台和研发效率工具。

更具体的表达：

> 我做过一个 AgentOps Mini Platform，支持配置 Agent、运行任务、查看 trace、执行 eval、统计 token 成本，并通过 Docker Compose 部署。这个项目让我理解了 Agent 不只是 prompt，而是工具调用、状态管理、可观测性和评测体系共同构成的工程系统。

## 下一步文档规划

后续可以继续补充：

- FastAPI 入门笔记
- Python 给 Android 开发者的速通笔记
- LLM Tool Calling 实战
- Agent Trace 数据结构设计
- Agent Eval 指标设计
- MCP 入门与实践
- Docker Compose 部署 Agent 服务
- AgentOps Mini Platform 项目设计文档
