# Agent Learning

这个仓库用于维护 Android 开发转向 Agent 工程 / 端侧 AI 方向的学习笔记、路线图和实战项目设计。

## 文档

### 总纲

- [Android 开发转 Agent 工程学习路线](docs/agent-learning-roadmap.md)

### 分叉轨道（端侧 / 移动端方向）

- [端侧 AI 学习轨道](docs/on-device-ai-track.md) —— 把 Android 背景变成护城河，与主线叠加而非替代
- [C++ 补齐计划（给 Android/Java 开发者）](docs/cpp-for-on-device-ai.md) —— 够用到能读懂/改 llama.cpp、写 JNI、做端侧推理集成
- [PocketAgent 端云协同收官蓝图](docs/edge-cloud-agent-capstone.md) —— 端侧轨道的作品集项目：端侧 Agent + 端云路由 + 设备指标 trace + 多配置 eval

**端侧展开篇**（代码级深入，按学习顺序）：

1. [端侧推理实战：llama.cpp 跑通 + 量化 / 后端对比](docs/on-device-inference-llamacpp.md) —— 对应轨道 Part 5.1–5.3 / 里程碑 M0–M2
2. [端侧 profiling 实战：延迟 / 内存 / 功耗 / 降频怎么科学测](docs/on-device-profiling.md) —— 对应轨道 Part 5.5，你最硬的护城河
3. [端侧 Agent / RAG 实战：小模型可靠工具调用 + 离线本地检索](docs/on-device-agent-rag.md) —— 对应轨道 Part 5.6
4. [端云路由设计：怎么判断端 vs 云、降级与一致性](docs/on-device-edge-cloud-routing.md) —— 对应轨道 Part 5.7（系列收尾）

**动手工程**：[`pocketagent/`](pocketagent/) —— PocketAgent 的 M0 起点脚手架（CMake + JNI + llama.cpp + 流式桥接已接好，clone + 塞模型即可跑）

### 学习笔记（按学习顺序，写给 Android / Java 开发者）

1. [Python + FastAPI 阅读理解指南](docs/python-fastapi-essentials.md) —— 造服务
2. [LLM API 与 Tool Calling 阅读理解指南](docs/llm-api-and-tool-calling.md) —— 让模型干活
3. [Agent Trace 与可观测性：数据结构与回放](docs/agent-trace-and-observability.md) —— 记录干了什么
4. [Agent Eval：评测与回归](docs/agent-eval-and-metrics.md) —— 评判干得好不好
5. [Docker 部署 Agent 服务](docs/docker-deploy-agent-service.md) —— 打包部署

### 收官项目蓝图

- [AgentOps Mini Platform 项目设计文档](docs/agentops-mini-platform-design.md) —— 把上面五篇拼成一个可落地的作品集项目
