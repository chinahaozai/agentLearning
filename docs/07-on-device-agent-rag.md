# 端侧 Agent / RAG 实战：小模型可靠工具调用 + 离线本地检索（写给 Android 开发者）

## 0. 这份文档怎么用

这是 [端侧 AI 学习轨道](02-on-device-ai-track.md) **Part 5.6 的展开篇**，端侧展开篇系列第 3 篇。

**前置**：假设你已经会**云端 agent loop**（主线 [LLM API 与 Tool Calling](10-llm-api-and-tool-calling.md)），也能在端侧**把模型跑起来**（[端侧推理实战](05-on-device-inference-llamacpp.md) + [C++ 篇](03-cpp-for-on-device-ai.md)）。这篇要解决的是把"能跑的端侧模型"变成"能干活的端侧 Agent"。

**核心问题**：端侧 Agent 的循环和云端**完全同构**（模型提议调用→你执行→回传→再循环，[第2篇] Part 4 那套）。但端侧多出两个云端没有的硬坑：

1. **小模型工具调用不稳**（参数乱填、该调不调、胡编工具名、JSON 格式跑偏）。
2. **没网**——工具和知识都得在本地（离线 RAG）。

这篇就专治这两个。

带 ★ 的是重点：

- ★★★ **Part 2 让小模型可靠调工具** 和 **Part 3 端侧 RAG** —— 端侧 Agent 真正的难点和差异化全在这。
- ★★ **Part 1 和云端的差异** 和 **Part 4 拼成一个最小端侧 Agent**。

取舍：循环机制不重讲（回扣 [第2篇]），只讲端侧新增的坑和招；以 llama.cpp 为例，概念通用。

---

## Part 1 · 端侧 Agent 难在哪：和云端的三个差异 ★★

先回扣 [第2篇] 的 agentic loop——这套在端侧**一字不改**：

```
模型输出 → 判断要不要调工具 → 调本地工具 → 结果回填 → 再推理 → 直到完成
```

差异在于端侧的"模型"和"环境"都更受限：

| 维度 | 云端（大模型）| 端侧（小模型）| 对策 |
|---|---|---|---|
| 工具调用可靠性 | 强，JSON 基本不会错 | **弱，容易跑飞** | 约束解码（Part 2）|
| 上下文窗口 | 很大 | **小，KV cache 还贵** | 工具描述短、检索内容精简 |
| 知识来源 | 可联网 / 大模型内化 | **要离线、要本地** | 端侧 RAG（Part 3）|
| 兜底 | —— | 实在不行**降级到云** | 端云路由（[Part 5.7]）|

> **一句话**：端侧 Agent = 云端那套 loop + "怎么让一个能力弱、记性短、还断网的模型也能稳稳干活"的一堆工程招。

---

## Part 2 · 让小模型可靠地调用工具 ★★★

小模型最大的问题：你给它工具，它可能**不调、调错、参数乱填、或输出一段没法解析的 JSON**。下面四招由强到弱，前两招是关键。

### 2.1 第一招（最关键）：约束解码 / 语法约束 ★

回扣 [第2篇] 的结构化输出。云端靠大模型自觉守格式；**端侧要"物理上"逼它守格式**——这就是**约束解码（constrained decoding）**。

原理：模型每步输出的是"下一个 token 的概率分布"（回扣 [推理实战 Part 4.2]）。约束解码在采样这一步，**把所有"会让输出不合法"的 token 概率直接掐成 0**——于是模型在物理上**只能**生成符合你 schema 的内容。

llama.cpp 用 **GBNF（一种 BNF 语法）** 描述约束，还能**从 JSON Schema 自动转 GBNF**。比如强制模型只能输出"调哪个工具 + 合法参数"：

```gbnf
# 强制输出形如 {"tool":"search_notes","query":"..."} 的合法 JSON
root   ::= "{" ws "\"tool\":" ws tool "," ws "\"query\":" ws string ws "}"
tool   ::= "\"search_notes\"" | "\"get_device_status\""   # 只能从这两个里选！
string ::= "\"" ([^"\\] | "\\" .)* "\""
ws     ::= [ \t\n]*
```

> **效果**：`tool` 字段被语法限定只能是你定义的工具名，模型**胡编不出**第三个；整体结构保证是合法 JSON，**解析不会崩**。这是端侧相对云端最重要的工具调用技巧——**云端模型够强可以不用，端侧几乎必须用**。

### 2.2 第二招：把工具调用做"简单到不会错"

小模型怕复杂。降低难度本身就是提可靠性：

- **工具少**：起步 2~3 个就够，工具越多模型越容易选错。
- **参数少、类型简单**：能一个参数就别三个。
- **描述短而准**：工具 `description` 直接吃 context（端侧 context 金贵），写一句话点清"什么时候用"即可。
- **能用"选择题"就别用"填空题"**：参数尽量用**枚举**（`enum`）而非自由文本——枚举可以用约束解码锁死，错不了。

### 2.3 第三招：选一个"本来就擅长工具调用"的模型

模型之间差别很大。**Qwen2.5 系列以函数调用能力强著称**，端侧做 Agent 优先考虑；纯 base 模型或太小（0.5B）的模型工具调用会很吃力。这也是一条可量化的选型结论（见 Part 5 评测）。

### 2.4 第四招：容错 + 重试 + 降级

回扣 [第2篇 Part 4.5] 的工具失败处理，端侧重试成本低（本地、不花钱）：

```
解析/校验工具调用 → 不合法？把错误作为 tool_result 回传，让模型改 → 本地重试
反复失败（如 2 次）→ 降级到云端（端云路由 Part 5.7）
```

> **Android 类比**：约束解码 ≈ 给输入框加**输入校验 + 只读下拉选择**——从源头不让用户（模型）填出非法值，而不是等填错了再报错。比"事后 try/catch JSON 解析"可靠得多。

---

## Part 3 · 端侧 RAG：离线检索本地知识 ★★★

让 Agent 能基于**本地文档/笔记/聊天记录**回答，且**全程不联网**。

### 3.1 RAG 一句话 + 端侧版的意义

RAG = 先**检索**出相关片段，塞进 prompt，再让模型基于它作答（回扣主线 RAG 概念）。端侧版的价值：**隐私**（数据不出设备）+ **离线**可用——这正是很多端侧场景的硬需求。

### 3.2 端侧 RAG 三步

```
① 索引(离线一次)：本地文档 → 切块 → 每块算 embedding → 存进本地向量库
② 检索(每次提问)：query → 算 embedding → 在向量库找最相近的 top-k 块
③ 拼接：把命中的块塞进 prompt → 交给端侧模型作答
```

### 3.3 端侧 embedding 模型

需要一个**小的 embedding 模型**跑在端上，把文本变成向量：

- 候选：**bge-small / gte-small**（中文用 **bge-small-zh / bge-m3**）、Google 的 **EmbeddingGemma**（专为端侧做的 embedding 模型）。都是几十~几百 MB，端上能跑。
- 跑法：llama.cpp 本身支持 embedding 模式（一个引擎同时干生成和 embedding），或用 ONNX / MediaPipe 的 text embedder。

### 3.4 端上向量库：你其实早就会一半 ★

存向量 + 找最近邻，端侧两个现成选择：

| 方案 | 是什么 | Android 类比 |
|---|---|---|
| **sqlite-vec** | SQLite 的向量检索扩展 | **就是你用的 SQLite/Room 加了向量查询**，编进 NDK 即可 |
| **ObjectBox** | 移动端数据库，内置向量检索（HNSW）| 一个带向量能力的本地 DB |
| 暴力检索 | 几百个块直接内存里算余弦相似度 | 最简单，小知识库够用，**起步先用它** |

> **Android 类比**：`sqlite-vec` 对你几乎零门槛——你本来就用 SQLite/Room，它只是多了"按向量距离排序"的查询。先用"暴力余弦"跑通逻辑，量大了再上 sqlite-vec/ObjectBox 的索引。

### 3.5 切块与上下文预算（端侧特别要克制）

端侧 context 小、KV cache 贵（回扣 [推理实战 Part 4.3]），所以：

- **chunk 切小**（如几百字一块），别一块塞几千字。
- **top-k 克制**（取 2~3 块就好），别把检索结果一股脑全塞——会撑爆 context、还拖慢 prefill。
- 小知识直接塞 prompt 即可，**够大才上向量检索**——别为了用 RAG 而 RAG。

---

## Part 4 · 拼起来：一个最小端侧 Agent ★★

把前面的招 + 端侧推理（上两篇）+ JNI（[C++ 篇]）组装成 Kotlin 侧的 agent loop。**RAG 本身就当成一个工具**（`search_notes`），统一进工具调用框架：

```
用户输入
  └▶ 端侧模型(约束解码) ── 输出合法的"调用意图" JSON
        ├─ get_device_status → 直接返回设备信息
        └─ search_notes(query) → 端侧 RAG 检索本地文档 → 命中片段回填
  └▶ 结果回填 → 再推理 → 直到给出最终答案
```

```kotlin
// 端侧 agent loop（和第2篇同构，差别：调模型走 JNI、工具调用上约束解码）
suspend fun runAgent(input: String): String {
    val msgs = mutableListOf(userMsg(input))
    repeat(MAX_STEPS) {
        val out = llm.complete(msgs, grammar = TOOL_CALL_GBNF)  // ← 约束解码保证可解析
        val call = parseToolCall(out) ?: return out             // 没调工具=最终答案
        val result = when (call.tool) {
            "get_device_status" -> deviceStatus()
            "search_notes"      -> localRag.search(call.query, topK = 3)  // 端侧 RAG
            else                -> "未知工具"                    // 约束解码下基本到不了这
        }
        msgs += assistantToolCall(call); msgs += toolResult(result)
    }
    return "（已达最大步数）"
}
```

这正是 [PocketAgent 收官项目](04-edge-cloud-agent-capstone.md) 的 **M1（端侧 Agent + 工具）** 和 **M4（本地 RAG）**。

---

## Part 5 · 评测端侧 Agent（比云端多一层）★

回扣主线 [Eval 篇](12-agent-eval-and-metrics.md)，端侧要多测几个端侧专属维度：

- **工具调用准确率 / 任务成功率**（同云端）。
- **约束解码 开 vs 关 的对比**——直接证明这招的价值（成功率提升多少、JSON 解析失败率降多少）。
- **不同模型 / 量化档的工具调用可靠性**——比如 Qwen2.5-1.5B 比 0.5B 稳多少、Q4 比 Q3 稳多少（接 [推理实战 Part 3.3] 的质量验证）。
- **RAG 命中率**——检索回来的块到底相不相关。

> 这些对比表是 [PocketAgent M5](04-edge-cloud-agent-capstone.md) 的料，也是面试里"你怎么保证小模型在端上靠谱"的实证回答。

---

## Part 6 · 常见坑速查

| 现象 | 多半因为 |
|---|---|
| 模型该调工具时不调 / 乱调 | 工具描述含糊、工具太多、模型太小（Part 2.2/2.3）|
| JSON 解析老失败 | **没上约束解码**（Part 2.1）——端侧几乎必须上 |
| 工具名被"胡编" | grammar 没把 `tool` 字段限定成枚举（Part 2.1）|
| 一加 RAG 就变慢 / 爆内存 | top-k 太大、chunk 太大撑爆 context 和 KV cache（Part 3.5）|
| RAG 检索到一堆无关内容 | embedding 模型不匹配（中文用错模型）、chunk 切太大、该建索引没建 |
| 中文检索效果差 | 用了纯英文 embedding 模型，换 bge-zh / bge-m3 |
| 多轮后越来越慢 | 历史 + 工具结果堆积，超出 context，要裁剪 |

---

## 参考链接 / 延伸阅读

> 链接随版本变动，**失效时按标题搜索**。

**约束解码 / 工具调用**
- llama.cpp GBNF 语法（`grammars/` 与 README 的 grammar 章节）：https://github.com/ggml-org/llama.cpp
- JSON Schema → grammar（仓库自带转换）：见 llama.cpp `json_schema_to_grammar`

**端侧 embedding / 向量库**
- sqlite-vec（SQLite 向量扩展）：https://github.com/asg017/sqlite-vec
- ObjectBox（移动端向量数据库）：https://objectbox.io/
- EmbeddingGemma（端侧 embedding 模型）：https://ai.google.dev/gemma
- BGE embedding 模型（含中文）：https://huggingface.co/BAAI

**回到轨道 / 相邻篇**
- [端侧 AI 学习轨道](02-on-device-ai-track.md)（Part 5.6 全景）
- [LLM API 与 Tool Calling](10-llm-api-and-tool-calling.md)（agent loop 基础）
- [端侧推理实战](05-on-device-inference-llamacpp.md) / [C++ 补齐计划](03-cpp-for-on-device-ai.md)
- [PocketAgent 收官蓝图](04-edge-cloud-agent-capstone.md)（M1 / M4 / M5）

---

## 下一步建议

动手做 [PocketAgent](04-edge-cloud-agent-capstone.md) 的 M1 + M4：给你端侧跑通的模型加**约束解码的工具调用** + 一个 `search_notes` 本地 RAG（先用"暴力余弦"，量大了再上 sqlite-vec）。做一次 Part 5 的"约束解码 开/关"对比，你会直观看到这招把小模型的可靠性拉起来多少。

> 这一篇让端侧模型"能干活、能查本地知识"。下一篇 **端云路由设计**（轨道 Part 5.7）会讲最后一块：怎么判断一个请求该留在端上还是甩给云、怎么做降级和一致性——把端和云缝成一个完整的协同 Agent。
