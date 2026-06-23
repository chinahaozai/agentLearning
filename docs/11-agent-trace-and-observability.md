# Agent Trace 与可观测性：数据结构与回放（写给 Android / Java 开发者）

## 0. 这份文档怎么用

这是系列第三篇，对应路线图学习优先级 #3、以及规划里的「Agent Trace 数据结构设计」。

**先说清楚：这篇和前两篇性质不一样。**

- 前两篇（FastAPI、LLM API）是**读现成的语言/API**，目标是“看懂 AI 写的代码”。
- Trace 没有一个“现成 API 让你调”——它是个 **设计题**：记哪些字段、数据结构怎么定、怎么存、怎么回放，要你（或你指挥 AI）去**设计**。

所以这篇的配方调整为：**概念 + 数据结构设计 + 看懂代码** 三件套。难度仍和前两篇齐平（入门为主，给最小可用实现），依旧用 Java / Android 类比、难点上代码、结尾有速查表和参考链接。

**这篇有个巨大便利**：它直接接上第二篇那个 agentic loop。一句话剧透——

> **Trace 就是给第二篇那个 `while` 循环装一个“行车记录仪”**：每转一圈（一次模型调用、一次工具调用）都记一条，最后拼成一次完整执行的“黑匣子”。

带 ★ 的是重点：
- ★★ **Part 3 数据结构设计** 和 **Part 4 给循环埋点** —— 这篇的核心。
- ★ Part 2 心智模型、Part 5 存储与回放。

---

# Part 1 · 为什么 Agent 一定要 Trace

普通 CRUD 后端，出问题看几行日志就知道哪错了。Agent 不一样，它有三个“天生难调”的特点：

1. **非确定**：同样的输入，模型每次的回答、调用的工具都可能不同。
2. **多步**：一个任务要在循环里转好几圈，调好几次模型、好几个工具。
3. **黑盒**：模型“为什么决定调这个工具、传这个参数”，你看不见。

结果就是：**不记录，就没法回答“它刚才到底干了什么、为什么这么干、花了多少钱、慢在哪一步”**。路线图原话——“真实 Agent 系统一定需要 trace，否则无法调试”。

Trace 解决三件事：

- **调试**：一次执行的每一步都摊开看，定位是模型选错工具、还是工具返回了脏数据。
- **算钱算时间**：每步的 token、成本、耗时都记下来，能算总账、找瓶颈。
- **为 Eval 供料**：第四篇的评测，本质就是“拿一批 trace 来打分”（Part 8 细说）。

## Trace ≠ 随手打日志

你可能会想：那我 `print` 几行不就行了？区别在于 **结构化 + 可关联 + 可查询**：

| 随手 `print` / Logcat | Trace |
|---|---|
| 一堆零散文本，混在一起 | 结构化记录（字段化）|
| 多次执行的日志交织，分不清哪条属于哪次 | 用 `trace_id` 把**一次执行**的所有步骤串起来 |
| 只能肉眼翻 | 能存库、能查询、能回放成时间线 |

**Android 类比**：这就像 **Logcat 里满屏 `Log.d`** 和 **Crashlytics / Firebase Analytics 的结构化事件** 的区别——后者每条带上下文、能按会话串起来、能在后台查询和聚合。

---

# Part 2 · 核心心智模型：Trace 与 Span ★

整个 trace 体系就两个概念，记住它俩这篇就过半了：

- **Trace（一次执行）**：Agent 处理**一个任务**的完整过程。对应第二篇里 `run_agent("...")` 跑的那一整次。
- **Span（一个步骤）**：执行过程中的**一个工作单元**。一次模型调用是一个 span，一次工具调用是一个 span。

一次 trace 里有多个 span，而且 span 可以**嵌套**（一个步骤里又触发子步骤，比如调用了子 Agent），靠 `parent_span_id` 串成一棵**树**：

```
Trace: "查订单A123并报时"
 ├─ span(llm)  第1次问模型      → 模型说要调 query_order 和 get_current_time
 ├─ span(tool) query_order      → "已发货"
 ├─ span(tool) get_current_time → "2026-06-12..."
 └─ span(llm)  第2次问模型      → 最终回答
```

**Android 类比（最贴切的一个）**：这棵“span 嵌套树 + 各自耗时”，跟 **Android Studio 的 CPU Profiler / Perfetto / Systrace 时间线**几乎一模一样——一条主调用里嵌套着子调用，每段标着起止和耗时。你看 trace 回放，就是在看 Agent 这次执行的“调用时间线”。

> **这套 trace / span 模型就是 OpenTelemetry（OTel）的核心模型** —— 业界通用的可观测性标准。你不必现在就上 OTel 那套重框架，但**概念是通用的**：先理解 trace/span，以后无论用哪个平台（LangSmith、Langfuse…）都是这套词。

---

# Part 3 · 一次执行该记哪些字段（数据结构设计）★★

这是这篇的“设计”核心。把路线图列的字段（task id、输入、system prompt、模型名、模型响应、工具名、工具参数、工具结果、错误、耗时、token、成本）**落成两层结构**：Trace 层（整体）+ Span 层（每步）。

用 Pydantic（回扣第一篇）定义——你会发现它就是几个 data 类：

```python
from pydantic import BaseModel, Field
from typing import Any

class Span(BaseModel):                  # 一个步骤
    span_id: str
    parent_span_id: str | None = None   # 父步骤;顶层为 None。用来重建嵌套树
    type: str                           # "llm" | "tool"
    name: str                           # 模型名(如 claude-opus-4-8) 或 工具名(如 query_order)
    input: Any = None                   # 输入:发给模型的 messages / 给工具的参数
    output: Any = None                  # 输出:模型回复 / 工具返回结果
    error: str | None = None            # 出错信息(没错就是 None)
    started_at: str = ""
    ended_at: str = ""
    latency_ms: float = 0.0             # 这一步耗时(毫秒)
    tokens_in: int = 0                  # 仅 llm 步骤有意义
    tokens_out: int = 0

class Trace(BaseModel):                 # 一次完整执行
    trace_id: str                       # 串起本次所有 span 的 id
    input: str                          # 用户最初的输入
    status: str = "running"             # running | success | error
    error: str | None = None
    started_at: str = ""
    ended_at: str = ""
    spans: list[Span] = Field(default_factory=list)   # 本次的所有步骤
    # 汇总字段(可由 spans 累加得到)
    total_tokens_in: int = 0
    total_tokens_out: int = 0
    total_cost: float = 0.0
```

设计要点（看 AI 写的 trace 结构时按这些对号入座）：

- **两层**：`Trace` 是“一次执行”的整体账本，`Span` 是“每一步”的明细。这就是路线图说的“trace 数据结构”。
- **`trace_id` 是主线索**：一次执行里所有 span 都带同一个 `trace_id`，这样才能把它们“串起来”（回扣 Part 1 的“可关联”）。
- **`parent_span_id` 撑起树形**：平铺的 Agent 循环里每步都直接挂在执行下面；等以后有子 Agent / 嵌套步骤，靠它表达父子关系。
- **`type` 区分步骤种类**：`llm` 还是 `tool`，回放和统计时要分开看。

> **一个 Python 小坑**（Java 开发者常踩）：`spans: list[Span] = Field(default_factory=list)` —— 列表这种**可变默认值**不能直接写 `= []`，否则所有实例会共享同一个列表（Python 的著名陷阱）。Pydantic 用 `default_factory` 来正确处理。看到这个写法别奇怪。

---

# Part 4 · 把 Trace 接进 Agent 循环（埋点）★★

现在把 Part 3 的结构接到第二篇那个 loop 上。核心技巧：用一个 **`with` 上下文管理器**把每一步“包”起来，自动记开始/结束/耗时/错误——**这正是第一篇讲的 `with`（try-with-resources）的用法**，只不过这次“收尾动作”是“把这一步的 span 归档进 trace”。

## 4.1 一个自动记账的 span 包装器

```python
import time, datetime, uuid, contextlib

def _now() -> str:
    return datetime.datetime.now().isoformat(timespec="seconds")

@contextlib.contextmanager
def span(trace: Trace, type: str, name: str, input):
    s = Span(span_id=uuid.uuid4().hex[:8], type=type, name=name,
             input=input, started_at=_now())
    t0 = time.perf_counter()
    try:
        yield s                       # ← 把 span 交给 with 块；块里负责填 output / tokens
    except Exception as e:
        s.error = str(e)              # 这一步抛异常,也记下来
        raise                         # 记完继续往外抛,不吞错误
    finally:
        s.latency_ms = (time.perf_counter() - t0) * 1000  # 自动算耗时
        s.ended_at = _now()
        trace.spans.append(s)         # 无论成功失败,都把这步收进 trace
```

读法：`with span(...) as s:` 进入时创建一个 span 并开始计时；离开时（无论正常还是异常）`finally` 自动补上耗时、结束时间，并把它塞进 `trace.spans`。**埋点逻辑集中在这一个地方，业务循环里只管干活。**

> **Android 类比**：和第二篇说的 **OkHttp Logging Interceptor** 是同一个思想——把“记录每次调用”这件事抽到一个统一的包装层，调用方无感。区别只是这里记的是“一步 Agent 执行”，那里记的是“一次 HTTP 请求”。

## 4.2 埋好点的 Agent 循环（逐行精读）

把第二篇的 loop 套上 `span(...)`，就成了带 trace 的 Agent。**能读懂这段，这篇的核心就拿下了：**

```python
def run_agent(user_input: str) -> Trace:
    trace = Trace(trace_id=uuid.uuid4().hex[:8],   # 本次执行的总账本
                  input=user_input, started_at=_now())
    messages = [{"role": "user", "content": user_input}]

    try:
        while True:
            # ① 每次"问模型"包一个 llm span —— 自动记耗时
            with span(trace, type="llm", name="claude-opus-4-8", input=messages) as s:
                resp = client.messages.create(
                    model="claude-opus-4-8", max_tokens=1024,
                    tools=tools, messages=messages,
                )
                s.tokens_in = resp.usage.input_tokens    # 把 token 记到这步(回扣 doc2 的 usage)
                s.tokens_out = resp.usage.output_tokens
                s.output = [b.model_dump() for b in resp.content]  # 模型这步输出了什么

            if resp.stop_reason == "end_turn":           # 模型说完了,退出循环
                break

            messages.append({"role": "assistant", "content": resp.content})

            # ② 每个工具调用包一个 tool span
            results = []
            for block in resp.content:
                if block.type == "tool_use":
                    with span(trace, type="tool", name=block.name, input=block.input) as s:
                        out = run_tool(block.name, block.input)   # 你的代码真正执行工具
                        s.output = out
                    results.append({"type": "tool_result",
                                    "tool_use_id": block.id, "content": out})
            messages.append({"role": "user", "content": results})

        trace.status = "success"
    except Exception as e:               # 整次执行崩了,也记下来
        trace.status = "error"
        trace.error = str(e)
        raise
    finally:
        trace.ended_at = _now()
        # 汇总 token 与成本
        trace.total_tokens_in = sum(s.tokens_in for s in trace.spans)
        trace.total_tokens_out = sum(s.tokens_out for s in trace.spans)
        trace.total_cost = cost_of("claude-opus-4-8",
                                   trace.total_tokens_in, trace.total_tokens_out)
        save_trace(trace)               # 落库(Part 5)

    return trace
```

对比第二篇，**业务逻辑一行没变**，只是：①问模型外面包了 `with span(...llm...)`；②工具调用外面包了 `with span(...tool...)`；③最外层用 `try/finally` 保证“无论成功失败都汇总并落库”。这就是“埋点”——在不改业务的前提下，把每一步记下来。

成本用一个小函数算（费率取自第二篇的选型表）：

```python
PRICES = {  # 美元 / 每百万 token：(输入价, 输出价)
    "claude-opus-4-8":   (5.0, 25.0),
    "claude-sonnet-4-6": (3.0, 15.0),
    "claude-haiku-4-5":  (1.0,  5.0),
}
def cost_of(model: str, tin: int, tout: int) -> float:
    pin, pout = PRICES[model]
    return tin / 1e6 * pin + tout / 1e6 * pout
```

---

# Part 5 · 存储与回放 ★

## 5.1 存哪：两张表（trace 一张、span 一张）

入门用 **SQLite** 就够（路线图也提了 SQLite/Postgres）。设计成两张表，用 `trace_id` 关联：

```sql
CREATE TABLE traces (
  trace_id          TEXT PRIMARY KEY,
  input             TEXT,
  status            TEXT,
  started_at        TEXT,
  ended_at          TEXT,
  total_tokens_in   INTEGER,
  total_tokens_out  INTEGER,
  total_cost        REAL
);

CREATE TABLE spans (
  span_id         TEXT PRIMARY KEY,
  trace_id        TEXT,        -- 属于哪次执行(外键,关联到 traces)
  parent_span_id  TEXT,        -- 父步骤,用来重建嵌套树
  type            TEXT,        -- llm | tool
  name            TEXT,
  input           TEXT,        -- JSON 文本
  output          TEXT,        -- JSON 文本
  error           TEXT,
  started_at      TEXT,
  ended_at        TEXT,
  latency_ms      REAL,
  tokens_in       INTEGER,
  tokens_out      INTEGER
);
```

落库代码（注意：`input`/`output` 是复杂结构，存进 SQLite 前要 `json.dumps` 成文本）：

```python
import sqlite3, json

def save_trace(trace: Trace):
    db = sqlite3.connect("traces.db")
    db.execute(
        "INSERT INTO traces VALUES (?,?,?,?,?,?,?,?)",
        (trace.trace_id, trace.input, trace.status, trace.started_at, trace.ended_at,
         trace.total_tokens_in, trace.total_tokens_out, trace.total_cost),
    )
    for s in trace.spans:
        db.execute(
            "INSERT INTO spans VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (s.span_id, trace.trace_id, s.parent_span_id, s.type, s.name,
             json.dumps(s.input, ensure_ascii=False),
             json.dumps(s.output, ensure_ascii=False),
             s.error, s.started_at, s.ended_at, s.latency_ms, s.tokens_in, s.tokens_out),
        )
    db.commit(); db.close()
```

**Android 类比**：这跟你用 **Room + SQLite** 一回事；`json.dumps` 存复杂字段，相当于 Room 里用 **TypeConverter** 把对象转成 JSON 字符串入库。两张表 + 外键，就是最普通的关系建模。

## 5.2 回放：把一次执行读出来、排成时间线

“回放（replay）”= 按 `trace_id` 把 trace 和它的 spans 查出来，按时间排好打印（或喂给前端画时间线）：

```python
def replay(trace_id: str):
    db = sqlite3.connect("traces.db")
    db.row_factory = sqlite3.Row
    t = db.execute("SELECT * FROM traces WHERE trace_id=?", (trace_id,)).fetchone()
    spans = db.execute(
        "SELECT * FROM spans WHERE trace_id=? ORDER BY started_at", (trace_id,)
    ).fetchall()

    print(f"TRACE {t['trace_id']}  {t['status']}  "
          f"in={t['total_tokens_in']} out={t['total_tokens_out']} "
          f"${t['total_cost']:.4f}  «{t['input']}»")
    for s in spans:
        mark = "✗" if s["error"] else " "
        print(f" {mark} [{s['type']:4}] {s['name']:22} "
              f"{s['latency_ms']:6.0f}ms  → {s['output']}")
    db.close()
```

> 当前 Agent 循环是“平铺”的（所有 span 直接挂在执行下），按时间排即可。等以后步骤嵌套（子 Agent），就用 `parent_span_id` 重建成树、按层级缩进——就还原成 Part 2 那种 Profiler 时间线了。

---

# Part 6 · 工具与平台速览（不必重复造轮子）

自己写 trace 是为了**理解原理**（也贴合路线图第 3 周项目）。等到生产环境，多数团队会用现成平台——原理你懂了，接哪个都快。路线图点名的几个，一句话定位：

**LLM 专用可观测平台**（直接理解 trace/span、token、成本）：
- **Langfuse** —— 开源、可自托管，最流行的之一。自研练完想升级，通常先看它。
- **LangSmith** —— LangChain 出品的追踪 / 调试 / eval 平台（SaaS）。
- **Arize Phoenix** —— 开源，本地可跑，偏 LLM/RAG 追踪与评测。
- **Helicone** —— 以**代理（proxy）**方式接管你的 LLM 请求来记录，接入最省事。

**通用可观测基础设施**（不止 LLM）：
- **OpenTelemetry** —— 厂商中立的**标准**，trace/span 就是它的模型；通用但偏底层。
- **Sentry** —— 错误监控与告警（Android 你可能用过）。
- **Prometheus + Grafana** —— **指标（metrics）**采集与看板。

> **一个容易混的区别**：**trace** 记的是“单次执行的每一步细节”（本文重点）；**metrics**（Prometheus/Grafana）记的是“聚合数字”，比如每分钟请求数、p95 延迟、总成本曲线。两者互补：trace 用来“查一次具体执行为什么出问题”，metrics 用来“看整体健康度”。

---

# Part 7 · 成果长什么样：一次执行的 Trace 与回放

把前面串起来。跑一次：

```python
trace = run_agent("帮我查下订单 A123 的状态，再告诉我现在几点")
replay(trace.trace_id)
```

`replay` 打印出的时间线，就是这次执行的“黑匣子”：

```
TRACE 7f3a9c1b  success  in=730 out=105  $0.0063  «帮我查下订单 A123 的状态，再告诉我现在几点»
   [llm ] claude-opus-4-8         1120ms  → [想调用 query_order, get_current_time]
   [tool] query_order              305ms  → "订单 A123 状态：已发货"
   [tool] get_current_time           1ms  → "2026-06-12T15:04:00"
   [llm ] claude-opus-4-8         1780ms  → "订单 A123 已发货；现在是 6 月 12 日 15:04。"
```

一眼能看出：模型先决定调两个工具 → 工具各自返回 → 模型综合出最终回答；每步耗时、总 token、总成本一清二楚。**这就是“可观测”——执行不再是黑盒。**

数据库里 `spans` 表对应的（节选一条 tool span）大致长这样：

```json
{
  "span_id": "a1b2c3d4",
  "trace_id": "7f3a9c1b",
  "parent_span_id": null,
  "type": "tool",
  "name": "query_order",
  "input": {"order_id": "A123"},
  "output": "订单 A123 状态：已发货",
  "error": null,
  "latency_ms": 305.0,
  "tokens_in": 0,
  "tokens_out": 0
}
```

读 AI 生成的 trace 代码时，最终产物就是这种东西——你心里有这个“成品图”，再看埋点代码就知道每行在为哪个字段服务。

---

# Part 8 · 速查表 & 为 Eval 铺垫

## 字段速查（设计 trace 时对照）

| 层级 | 字段 | 作用 |
|---|---|---|
| Trace | `trace_id` | 串起本次所有 span 的主线索 |
| Trace | `input` / `status` / `error` | 本次输入、成功还是失败 |
| Trace | `total_tokens_in/out` / `total_cost` | 这次花了多少 token、多少钱 |
| Span | `parent_span_id` | 父步骤，撑起嵌套树 |
| Span | `type` (`llm`/`tool`) | 这步是问模型还是调工具 |
| Span | `name` / `input` / `output` | 调了谁、传了什么、返回什么 |
| Span | `latency_ms` / `error` | 这步多慢、有没有出错 |
| Span | `tokens_in/out` | 这步的 token（仅 llm 步骤）|

## 读 Trace 代码的“三步速读法”

1. **找数据结构**：`Trace` / `Span` 两层各有哪些字段 → 这套 trace 记了什么。
2. **找埋点点位**：哪里包了 `with span(...)`（或装饰器/中间件）→ 在哪些动作上记了账（通常就是“问模型”和“调工具”两处）。
3. **找落库与回放**：`save_trace` 存哪、`replay` 怎么按 `trace_id` 查出来排时间线。

## 最容易看错/记混的点

- **trace（单次执行细节） ≠ metrics（聚合指标）**：前者查具体一次，后者看整体趋势。
- **`trace_id` 是灵魂**：没有它，多次执行的记录就串不起来。
- **复杂字段存库要 `json.dumps`**：SQLite 没有原生 dict/list 列，存 JSON 文本，读出来再 `json.loads`。
- **`with span(...)` 是“自动记账”**：靠上下文管理器在退出时补耗时/错误并归档，不是普通函数调用。
- **埋点不改业务**：好的 trace 代码，业务循环几乎原样，记录逻辑都在包装层里（回扣 Interceptor 思想）。

## 承上启下：Trace 是 Eval 的原材料

第四篇要讲的 **Eval（评测）**，本质就是建立在 trace 之上：

```
准备一批测试任务 → 每个任务跑一次 Agent（各产出一条 trace）
→ 拿 trace 和"期望结果/期望工具调用"对比打分
→ 统计成功率、工具调用准确率、平均延迟、平均成本
→ 换个 prompt / 模型再跑一批，对比两个版本谁更好
```

也就是说，**这篇设计的 trace 字段（工具调用、token、耗时、成功失败），正是下一篇打分要用的数据**。Trace 记得好，Eval 才做得动。

---

## 参考链接 / 延伸阅读

> 这些是各项目/平台官方入口，内容随版本更新；链接偶尔会调整，**失效时按项目名搜索即可**（名字比 URL 稳）。

**概念标准**
- OpenTelemetry —— Traces 概念（trace/span 模型的源头）：https://opentelemetry.io/docs/concepts/signals/traces/

**LLM 专用可观测平台**
- Langfuse（开源、可自托管，推荐先看）：https://langfuse.com/
- LangSmith：https://docs.smith.langchain.com/
- Arize Phoenix（开源）：https://github.com/Arize-ai/phoenix
- Helicone（代理式接入）：https://www.helicone.ai/

**通用可观测基础设施**
- Sentry（错误监控）：https://sentry.io/
- Prometheus（指标）：https://prometheus.io/ ｜ Grafana（看板）：https://grafana.com/

---

## 下一步建议

读完这篇，最有效的巩固方式是 **给第二篇你写的那个小 Agent 加上 trace**：照 Part 3 定 `Trace`/`Span`、照 Part 4 用 `with span(...)` 把循环包起来、照 Part 5 存进 SQLite、再写个 `replay` 打印时间线。

跑通后你会得到一个能“回放每次执行”的小工具——这正是路线图第 3 周「Android Project Analyzer Agent」要求的“保存每次 Agent trace”，也直接为第四篇的 **Eval 评测** 备好了原材料。
