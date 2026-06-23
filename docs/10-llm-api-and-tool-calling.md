# LLM API 与 Tool Calling 阅读理解指南（写给 Android / Java 开发者）

## 0. 这份文档怎么用

接着上一篇《Python + FastAPI 阅读理解指南》，这是路线图学习优先级 #2：**LLM API 与 tool calling**。

目标依旧是 **看懂 AI 写的相关代码、并判断它对不对**，取舍也一样：

- 核心概念讲透，不重要的不写。
- 能用 Java / Android 生态类比就类比，没有合适类比就直接讲清楚。
- 难点上代码。

> **关于代码示例用哪家：**
> 具体代码用 **Anthropic 官方 Python SDK**（当前默认模型 `claude-opus-4-8`），因为它准确、现成、也是本仓库工具链所用。
> 但你要建立的核心认知是**通用的** —— OpenAI、Gemini 等其他厂商 **概念完全一致，只是字段名不同**（比如 Claude 叫 `tool_use`，OpenAI 叫 `tool_calls`）。读懂一家，另一家就是查字段名的事。最后 Part 8 有一张对照表。

带 ★ 的是重点：
- ★★★ **Part 1 心智模型** 和 **Part 4 Tool Calling** —— 这两部分是“看不看得懂 Agent 代码”的命根子。
- ★ 流式、结构化输出。

---

# Part 1 · 先建立正确的心智模型 ★★★

这一节没有几行代码，但它是后面所有内容的地基。**Java 开发者初学 LLM 最容易在这三点上想歪**，想正了，后面读代码会非常顺。

## 1.1 LLM API 本质就是一个 HTTP 接口

别被“大模型”三个字唬住。你调用 LLM，本质就是 **向一个 REST 接口 POST 一段 JSON，拿回一段 JSON**。

Anthropic 的全部能力都走这一个端点：

```
POST https://api.anthropic.com/v1/messages
```

你发过去的 JSON 大致是：

```json
{
  "model": "claude-opus-4-8",
  "max_tokens": 1024,
  "messages": [
    { "role": "user", "content": "你好" }
  ]
}
```

拿回来的 JSON 大致是：

```json
{
  "content": [{ "type": "text", "text": "你好！有什么可以帮你？" }],
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 10, "output_tokens": 12 }
}
```

**Android 类比**：这跟你用 Retrofit 调一个后端接口**没有本质区别**：

```java
@POST("v1/messages")
Call<MessageResponse> createMessage(@Body MessageRequest req);
```

SDK（Python 的 `anthropic`、JS 的 `@anthropic-ai/sdk`）只是帮你把这个 HTTP 调用包了一层，约等于 Retrofit + Gson 帮你做的事：拼请求、发请求、解析响应、自动重试。所以看到 `client.messages.create(...)`，脑子里就是“它在发一个 POST /v1/messages”。

## 1.2 API 是无状态的（模型没有记忆）

**这是第二个关键认知**：LLM 接口是**无状态**的。模型本身**不记得**你上一句说了什么。

那多轮对话怎么实现？—— **每次请求，你都要把完整的对话历史重新发过去**。

```
第 1 轮：messages = [用户:"我叫Tom"]
第 2 轮：messages = [用户:"我叫Tom", 助手:"你好Tom", 用户:"我叫什么?"]
                      └──────────── 你得把前面全带上 ────────────┘
```

**Android 类比**：就像一个**没有 session 的 REST 接口**——服务端不保存会话状态，状态全在你客户端手里，每次调用自己把上下文带全。模型的“记忆”完全是你喂给它的 `messages` 数组造出来的幻觉。

> 这条直接决定了两件事：**(1)** 对话越长，每次发的 token 越多、越贵（见 Part 6）；**(2)** “多轮上下文管理”本质就是“你怎么维护这个 messages 数组”。

## 1.3 Tool Calling 不是“模型帮你执行代码” ★

**这是 Java 开发者最大的误解，必须扭过来。**

听到“模型会调用工具/函数”，很容易以为模型自己去执行了你的代码、读了文件、查了数据库。**完全不是。**

真实流程是：

```
1. 你告诉模型："我这有几个工具，长这样（名字+参数）"
2. 模型说："我想调用 get_weather，参数是 {city: '北京'}"   ← 它只是"提出请求"，一个意图
3. 【你的代码】真正去执行 get_weather("北京")，拿到 "晴 25℃"   ← 执行的是你！
4. 你把结果回传给模型："get_weather 的结果是 晴 25℃"
5. 模型基于结果继续回答："北京今天天气晴，25度"
```

模型**自己不执行任何东西**。它只会输出一个结构化的“我想调用 X，参数是 Y”，**真正动手的永远是你的代码**。

**Android 类比 —— 命令模式（Command Pattern）**：

这跟命令模式几乎一模一样。模型扮演“发起者”，它产出一个 **Command 对象**（`{name: "get_weather", input: {...}}`）；你的代码是 **Invoker/dispatcher**，根据 command 的名字找到对应实现去执行，再把结果回传。

```java
// 模型返回的 tool_use 就像这样一个命令对象
class ToolCommand {
    String name;              // "get_weather"
    Map<String,Object> input; // {"city": "北京"}
}
// 你的 dispatcher 负责执行
String result = dispatch(command.name, command.input);
```

记住这句话，整篇文档的 Agent 部分都通了：

> **模型负责“决定调用什么工具、参数是什么”；你的代码负责“真正执行工具”。两者通过一个来回的循环协作。**

这也是为什么路线图说 “Agent 的核心不是模型自己会做事，而是开发者提供工具，模型负责选择和调用”。

---

# Part 2 · 基础调用：一次请求长什么样

## 2.1 最小可运行例子（Anthropic Python SDK）

```python
import anthropic

client = anthropic.Anthropic()      # 自动从环境变量 ANTHROPIC_API_KEY 读密钥

response = client.messages.create(
    model="claude-opus-4-8",        # 用哪个模型
    max_tokens=1024,                # 最多生成多少 token（硬上限）
    messages=[
        {"role": "user", "content": "用一句话解释什么是 REST API"}
    ],
)

# response.content 是一个"块列表"，不是字符串！（见 2.5）
print(response.content[0].text)
```

`client.messages.create(...)` = 发一个 `POST /v1/messages`。就这么直接。

## 2.2 三个最核心的入参

| 参数 | 作用 | 类比 / 备注 |
|---|---|---|
| `model` | 用哪个模型 | 见 2.8 选型表 |
| `messages` | 对话历史（数组）| 见 2.3 |
| `max_tokens` | **本次回复**最多生成多少 token | 硬上限。撞上限会被截断（`stop_reason` 变 `max_tokens`）|

> `max_tokens` 不是“我希望它回多长”，是“**绝不允许超过**这么多”。设小了会把回答砍断。非流式一般给 ~16000，流式可给 ~64000（见 Part 3）。

## 2.3 messages 的结构

`messages` 是一个数组，每个元素是 `{"role": ..., "content": ...}`：

```python
messages = [
    {"role": "user",      "content": "我叫 Tom"},
    {"role": "assistant", "content": "你好，Tom！"},
    {"role": "user",      "content": "我叫什么？"},
]
```

规则（看代码时对号入座）：

- `role` 只有 `user`（你/用户）和 `assistant`（模型）两种。
- **第一条必须是 `user`**。
- 通常 user / assistant 交替出现。
- 模型的“记忆”就来自这个数组（回扣 1.2 无状态）。

## 2.4 system prompt（系统提示）

`system` 是单独的参数，用来设定模型的“角色/规则”，权重比普通消息高：

```python
response = client.messages.create(
    model="claude-opus-4-8",
    max_tokens=1024,
    system="你是一个简洁的代码助手，只用中文回答，回答不超过 3 句话。",
    messages=[{"role": "user", "content": "什么是 FastAPI？"}],
)
```

可以理解成给这次对话定的“全局配置 / 人设说明书”。

## 2.5 读响应：content 是“块列表”，不是字符串 ★

**这是读响应代码最容易看错的地方。** `response.content` **不是一个字符串**，而是一个**内容块（content block）的列表**。每个块有 `type` 字段，要先判断类型再取值：

```python
for block in response.content:
    if block.type == "text":        # 普通文字
        print(block.text)
    elif block.type == "thinking":  # 模型的思考过程（开了 thinking 才有）
        print(block.thinking)
    elif block.type == "tool_use":  # 模型想调用工具（见 Part 4）
        print(block.name, block.input)
```

**Android 类比**：这就是一个**多态列表 + 按子类型分支**，跟 Java 里对一个 `List<ContentBlock>` 用 `instanceof` / sealed class 的 `switch` 完全一个套路：

```java
for (ContentBlock block : response.getContent()) {
    if (block instanceof TextBlock t)      { print(t.getText()); }
    else if (block instanceof ToolUseBlock u) { dispatch(u); }
}
```

> 所以看到 `response.content[0].text` 这种写法要警觉：它假设了第一个块就是文字块。开了 thinking 或 tool 的场景下第一个块可能不是 text，更稳的写法是遍历 + 判 `type`（上面那种）。

## 2.6 stop_reason：模型为什么停下来

每个响应都有 `response.stop_reason`，告诉你这次为什么结束。**它是驱动 Agent 循环的关键信号**（Part 4 会用到）：

| 值 | 含义 | 你该怎么办 |
|---|---|---|
| `end_turn` | 正常说完了 | 结束，用结果 |
| `tool_use` | **模型想调用工具** | 执行工具，把结果回传，继续循环（核心！）|
| `max_tokens` | 撞到 max_tokens 上限被截断 | 调大 max_tokens 或改用流式 |
| `refusal` | 出于安全原因拒绝 | 看 `stop_details`，别原样重试 |

## 2.7 usage：token 用量与成本

`response.usage` 告诉你这次花了多少 token —— 直接对应**钱**：

```python
print(response.usage.input_tokens)   # 输入（你发过去的）token 数
print(response.usage.output_tokens)  # 输出（模型生成的）token 数
```

输入和输出**分别计费**，且输出通常比输入贵好几倍（见下表）。这也是路线图反复强调“token 计算和成本”的原因。

## 2.8 模型选型与计费（当前主力，2026 年）

| 模型 | 模型 ID | 定位 | 输入 $/百万 token | 输出 $/百万 token | 上下文窗口 |
|---|---|---|---|---|---|
| Claude Opus 4.8 | `claude-opus-4-8` | **默认首选**，最强综合/Agent | $5 | $25 | 1M |
| Claude Sonnet 4.6 | `claude-sonnet-4-6` | 速度/成本平衡，高并发 | $3 | $15 | 1M |
| Claude Haiku 4.5 | `claude-haiku-4-5` | 最快最便宜，简单任务 | $1 | $5 | 200K |
| Claude Fable 5 | `claude-fable-5` | 最强能力，最难的长任务 | $10 | $50 | 1M |

读代码看到 `model="..."` 就知道作者在性能/成本上做了什么取舍。一般默认用 Opus 4.8，高并发或简单任务才降级到 Sonnet / Haiku。

> **“上下文窗口（context window）”** 指模型一次能“看见”的最大 token 总量（输入+输出加起来）。类比一个**固定大小的缓冲区**：对话历史 + 这次回答必须塞进这个窗口。超了就得裁剪或压缩历史（见 Part 6）。

---

# Part 3 · 流式输出 streaming ★

## 3.1 为什么要流式

非流式：等模型把整段话生成完，一次性返回。长回答要干等好几秒甚至更久，体验差，还可能触发 HTTP 超时。

流式（streaming）：模型生成一个字就推一个字给你，像打字机效果（ChatGPT 那种逐字蹦出来就是流式）。

**Android 类比**：非流式像普通 `Call<T>` 一次性拿到 body；流式像 **OkHttp 的 SSE（EventSource）/ 分块响应**，数据一段段到达，你边到边处理。底层用的就是 SSE（Server-Sent Events）。

## 3.2 SDK 写法

```python
with client.messages.stream(
    model="claude-opus-4-8",
    max_tokens=2048,
    messages=[{"role": "user", "content": "写一首关于秋天的诗"}],
) as stream:
    for text in stream.text_stream:     # 每次拿到一小段新文字
        print(text, end="", flush=True) # 立刻打印，不缓冲

    final = stream.get_final_message()  # 流结束后，拿完整的最终消息对象
    print("\ntoken:", final.usage.output_tokens)
```

读法：`stream.text_stream` 是个迭代器，每轮给你**新增的一小段文字**；循环完后 `get_final_message()` 拿到拼好的完整结果（含 usage、stop_reason 等）。

## 3.3 什么时候必须流式

**输出可能很长时（大 `max_tokens`、长文生成）必须流式**，否则非流式请求容易因为生成时间太长而 HTTP 超时。经验值：非流式 `max_tokens` 控制在 ~16000 以内；要更长就改流式（可到 64K+）。

看 AI 代码时，看到 `.stream(...)` + `text_stream` 就是“它在做逐字输出”；看到普通 `.create(...)` 就是“一次性返回”。

---

# Part 4 · Tool Calling（核心中的核心）★★★

回扣 **Part 1.3**：模型只“提出”调用工具，你的代码“真正执行”，两者来回循环。这一 Part 把这个循环讲透。

## 4.1 一句话原理

```
你给模型一份"工具清单" → 模型挑一个并给出参数（tool_use）
→ 你执行它、拿到结果（tool_result）→ 回传给模型 → 模型继续
→ 直到模型不再要调用工具（stop_reason = end_turn）
```

## 4.2 怎么定义一个工具

一个工具 = **名字 + 描述 + 参数结构（JSON Schema）**：

```python
tools = [
    {
        "name": "get_weather",
        "description": "查询某个城市的当前天气。当用户问到天气时调用。",
        "input_schema": {
            "type": "object",
            "properties": {
                "city": {"type": "string", "description": "城市名，如 北京"}
            },
            "required": ["city"],
        },
    }
]
```

三个字段都关键：

- `name`：工具名，你的 dispatcher 靠它分发。
- `description`：**极其重要**。模型完全靠这段话来判断“什么时候该用这个工具”。写得含糊，模型就乱用或不用。
- `input_schema`：参数的结构，用 **JSON Schema** 描述（类型、哪些必填）。

**Android 类比**：`input_schema` 就是在**向模型描述一个方法签名 + 参数校验规则**——“这个工具接受一个 string 类型的 city，必填”。这跟你在 Java 里定义方法参数 + 加 `@NotNull` 校验，或者写一份 OpenAPI/Swagger 接口定义，是同一个东西（JSON Schema 本来就是 Swagger 用的那套）。模型读了它，就知道该怎么“填表”来调用。

## 4.3 完整的 Agentic Loop —— 这套循环要会读

这是 tool calling 的灵魂。**Java 开发者把这个循环读懂，就理解了 Agent 的运行机制。**

```python
messages = [{"role": "user", "content": "北京今天天气怎么样？"}]

while True:
    # ① 带着工具清单和当前对话历史，请求模型
    response = client.messages.create(
        model="claude-opus-4-8",
        max_tokens=1024,
        tools=tools,          # 告诉模型有哪些工具可用
        messages=messages,
    )

    # ② 模型说"我说完了"，跳出循环
    if response.stop_reason == "end_turn":
        break

    # ③ 走到这，说明 stop_reason == "tool_use"：模型想调用工具
    #    先把模型这一轮的完整响应原样塞回历史（必须包含 tool_use 块）
    messages.append({"role": "assistant", "content": response.content})

    # ④ 找出所有 tool_use 块，逐个【真正执行】，收集结果
    tool_results = []
    for block in response.content:
        if block.type == "tool_use":
            result = run_tool(block.name, block.input)   # ← 你的代码执行工具
            tool_results.append({
                "type": "tool_result",
                "tool_use_id": block.id,   # 必须对上是哪个调用的结果
                "content": result,
            })

    # ⑤ 把工具结果作为一条 user 消息塞回历史，进入下一轮循环
    messages.append({"role": "user", "content": tool_results})

# 循环结束，输出最终回答
print(next(b.text for b in response.content if b.type == "text"))
```

逐步讲清楚（对应注释序号）：

1. **请求**：每轮都把 `tools` 和完整 `messages` 发过去（无状态，回扣 1.2）。
2. **出口**：`end_turn` = 模型不需要再调工具了，循环结束。
3. **回填模型响应**：把模型这一轮的 `response.content`（里面含 `tool_use` 块）作为 `assistant` 消息加回历史——不加，模型下一轮就“忘了”自己刚才要调工具。
4. **执行工具**：遍历找出 `tool_use` 块，用 `run_tool(...)`（你自己实现的 dispatcher）真正执行，结果包成 `tool_result`。注意 **`tool_use_id` 必须对上**——告诉模型“这是你那个调用的结果”。
5. **回填结果**：工具结果作为一条 `user` 消息塞回去，进入下一轮，让模型基于结果继续。

> **关键直觉**：整个过程是 **“模型 ↔ 你的代码” 反复打乒乓**，`messages` 数组不断变长，记录了完整的“谁说了什么、调了什么工具、结果是什么”。一个 Agent 跑一个任务，可能在这个循环里转好几圈。

## 4.4 tool_choice：控制模型用不用工具

```python
tool_choice={"type": "auto"}   # 默认：模型自己决定用不用
tool_choice={"type": "any"}    # 必须用某个工具（至少一个）
tool_choice={"type": "tool", "name": "get_weather"}  # 强制用指定工具
tool_choice={"type": "none"}   # 禁止用工具
```

看到它就知道作者在控制模型的工具调用自由度。

## 4.5 工具执行失败怎么办

工具报错时，不要崩，而是把错误**作为结果回传**给模型，让它自己调整：

```python
tool_results.append({
    "type": "tool_result",
    "tool_use_id": block.id,
    "content": "错误：城市 'xyz' 不存在，请提供有效城市名",
    "is_error": True,        # 标记这是个错误结果
})
```

模型收到错误后通常会换个参数重试或改问。这就是路线图里说的“工具调用失败处理”。

## 4.6 Tool Runner：自动跑循环的语法糖

每次都手写 4.3 那个 `while` 循环很烦，所以 SDK 提供了 **tool runner**，自动帮你跑完整个循环：你用装饰器把工具定义成普通函数，它负责“调模型→执行函数→回传→再调”直到结束。

```python
from anthropic import beta_tool

@beta_tool
def get_weather(city: str) -> str:
    """查询某个城市的当前天气。

    Args:
        city: 城市名，如 北京
    """
    return f"{city}今天晴，25℃"

runner = client.beta.messages.tool_runner(
    model="claude-opus-4-8",
    max_tokens=1024,
    tools=[get_weather],
    messages=[{"role": "user", "content": "北京天气怎么样？"}],
)
for message in runner:   # runner 自动把 4.3 那套循环跑完
    print(message)
```

读到 tool runner 代码，心里清楚：**它只是把 4.3 的手写循环自动化了**，本质没变。需要精细控制（人工审批、自定义日志、条件执行）时，作者才会用手写循环。

---

# Part 5 · 结构化输出 structured output ★

## 5.1 为什么需要

很多时候你要的不是一段“话”，而是**能直接当数据用的 JSON**（提取信息、分类、填表单）。如果只让模型“尽量返回 JSON”，它可能多嘴、格式跑偏。**结构化输出**强制模型按你给的 schema 返回，保证能解析。

## 5.2 写法（配合 Pydantic，最常见）

还记得上一篇的 Pydantic 吗？这里直接复用：

```python
from pydantic import BaseModel

class ContactInfo(BaseModel):     # 定义你想要的数据结构
    name: str
    email: str
    wants_demo: bool

response = client.messages.parse(  # 注意是 parse，不是 create
    model="claude-opus-4-8",
    max_tokens=1024,
    messages=[{"role": "user",
               "content": "提取信息：张三 zhang@co.com 想预约演示"}],
    output_format=ContactInfo,     # 强制按这个结构输出
)

contact = response.parsed_output   # 直接是一个校验过的 ContactInfo 对象
print(contact.name, contact.email, contact.wants_demo)
```

**Android 类比**：这就像 **Gson/Moshi 把 JSON 反序列化成一个 data 类**，外加一层 schema 校验保证字段齐全、类型正确。`response.parsed_output` 直接是个结构化对象，不用你手动 `json.loads` 再校验。

> 也有不用 Pydantic、直接传 JSON Schema 的写法（`output_config={"format": {"type": "json_schema", "schema": {...}}}`），作用一样，看到知道即可。

---

# Part 6 · 几个必须知道的工程概念（简要）

这些路线图里都点了名，但读代码时知道“是什么、为什么”就够，不用钻细节。

## 6.1 token 与上下文窗口

- **token**：模型处理文本的最小单位，约等于“半个词/一个字根”。一个汉字常常 1～2 个 token。**计费和长度限制都按 token 算**，不是按字符。
- **上下文窗口（context window）**：一次请求里“历史 + 本次输出”能占用的 token 上限（Opus 4.8 是 1M）。对话太长会逼近上限，需要裁剪或压缩。
- **算 token 用官方接口，别用 tiktoken**：

```python
n = client.messages.count_tokens(
    model="claude-opus-4-8",
    messages=messages,
).input_tokens
```

> `tiktoken` 是 OpenAI 的分词器，拿来数 Claude 的 token 会**偏差很大**（代码/中文尤其离谱）。数哪家模型就用哪家的接口。

## 6.2 成本控制：prompt caching（前缀缓存）

如果每次请求都带一大段**相同的前缀**（比如一份很长的 system prompt 或文档），可以开 **prompt caching**：相同前缀第二次起按缓存价收费，能省到 ~90%。

**Android 类比**：有点像 HTTP 缓存——稳定不变的部分缓存命中，省钱省时间。本质是**前缀匹配**：前缀里任何一个字节变了，缓存就失效。所以 system prompt 里别塞 `当前时间`、随机 ID 这种每次都变的东西（会冲掉缓存）。

## 6.3 超时与重试

官方 SDK **自动重试** 429（限流）和 5xx（服务端错误），用指数退避。

**Android 类比**：相当于 SDK 内置了一个 **OkHttp Interceptor**，遇到限流/服务端错误自动隔一会儿重试，不用你手写。你也能配 `max_retries`、`timeout`。长输出务必用流式防超时（见 Part 3）。

## 6.4 thinking / 推理深度

新模型支持“先想再答”。开启自适应思考：

```python
response = client.messages.create(
    model="claude-opus-4-8",
    max_tokens=16000,
    thinking={"type": "adaptive"},      # 让模型自己决定想多少
    output_config={"effort": "high"},   # 思考/努力程度：low | medium | high | xhigh | max
    messages=[{"role": "user", "content": "一道复杂的推理题..."}],
)
```

`effort` 越高，模型想得越多、越准，但越慢越贵。读代码看到这俩参数，就知道作者在调“质量 vs 成本/延迟”。响应里会多出 `type == "thinking"` 的块（回扣 2.5）。

## 6.5 多轮对话 = 自己维护 messages

没有黑魔法。所谓“多轮上下文管理”，就是 **你自己把 messages 数组维护好**（每轮追加 user/assistant），每次请求重发（回扣 1.2 无状态）。对话太长就裁剪老消息或做压缩。

## 6.6 MCP（一句话）

**MCP（Model Context Protocol）** 是一个**标准协议**，把“工具/数据源”做成可插拔的服务——这样工具不用写死在你代码里，可以接别人提供的标准化“工具服务器”。

**Android 类比**：有点像给工具定义了一套**标准接口 + 服务发现**，类似插件化 / SPI 的思路。这是路线图后面专门要学的，现在知道“它是让工具标准化、可复用的协议”即可。

---

# Part 7 · 逐行精读一段 AI 生成的 Agent 代码

下面是一段典型的、AI 很可能生成的 **带工具的小 Agent**。逐行注释，把前面的知识点串起来。**能独立读懂这段，本文目标就达成了。**

```python
import anthropic
import datetime

client = anthropic.Anthropic()              # 创建客户端，自动读环境变量里的密钥

# ---------- ① 定义工具清单（name + description + JSON Schema）----------
tools = [
    {
        "name": "get_current_time",
        "description": "获取当前的日期和时间。用户问'现在几点/今天几号'时调用。",
        "input_schema": {"type": "object", "properties": {}},   # 不需要参数
    },
    {
        "name": "query_order",
        "description": "根据订单号查询订单状态。",
        "input_schema": {
            "type": "object",
            "properties": {
                "order_id": {"type": "string", "description": "订单号"}
            },
            "required": ["order_id"],                            # order_id 必填
        },
    },
]

# ---------- ② 你的工具 dispatcher：真正执行工具的地方 ----------
def run_tool(name: str, args: dict) -> str:    # name=工具名, args=模型给的参数
    if name == "get_current_time":
        return datetime.datetime.now().isoformat()
    if name == "query_order":
        # 真实项目这里会查数据库；这里写死演示
        return f"订单 {args['order_id']} 状态：已发货"
    return f"未知工具：{name}"

# ---------- ③ Agentic loop ----------
def run_agent(user_input: str) -> str:
    messages = [{"role": "user", "content": user_input}]   # 初始化对话历史

    while True:
        response = client.messages.create(    # 发请求（= POST /v1/messages）
            model="claude-opus-4-8",
            max_tokens=1024,
            tools=tools,                       # 带上工具清单
            messages=messages,                 # 带上完整历史（无状态）
        )

        if response.stop_reason == "end_turn": # 模型说完了 → 退出循环
            break

        # 走到这 = 模型想调工具。先把它这轮响应原样存回历史
        messages.append({"role": "assistant", "content": response.content})

        # 遍历找出 tool_use 块，逐个真正执行，收集结果
        results = []
        for block in response.content:         # content 是块列表，按 type 分支
            if block.type == "tool_use":
                output = run_tool(block.name, block.input)   # ← 你的代码执行
                results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,   # 对上是哪个调用的结果
                    "content": output,
                })

        # 工具结果作为一条 user 消息回填，进入下一轮
        messages.append({"role": "user", "content": results})

    # 循环结束，从最终响应里取出文字块返回
    return next(b.text for b in response.content if b.type == "text")

print(run_agent("帮我查下订单 A123 的状态，再告诉我现在几点"))
```

如果上面每一行你都能说出“在干嘛、为什么”——特别是 ③ 里那个循环“请求 → 判 stop_reason → 执行工具 → 回填 → 再请求”的节奏——那 AI 生成的大部分 tool calling / Agent 代码你都能读懂了。

---

# Part 8 · 速查表 & 三步速读法

## Anthropic ↔ OpenAI 概念对照（同概念，不同名）

读到 OpenAI 的代码时，把名字一换就能对上。**概念完全一致，验证确切语法时查对方官方文档即可。**

| 概念 | Anthropic（Claude） | OpenAI（大致对应）|
|---|---|---|
| 发起调用 | `client.messages.create(...)` | `client.chat.completions.create(...)` |
| 系统提示 | 独立的 `system` 参数 | `messages` 里一条 `role: "system"` |
| 定义工具 | `tools=[{name, description, input_schema}]` | `tools=[{type:"function", function:{name, description, parameters}}]` |
| 模型请求调工具 | `content` 里的 `tool_use` 块；`stop_reason="tool_use"` | `message.tool_calls`；`finish_reason="tool_calls"` |
| 回传工具结果 | `user` 消息里的 `tool_result` 块 | 一条 `role:"tool"` 消息 |
| 参数结构 | JSON Schema | JSON Schema（一样）|

> 共性：都走 HTTP；都是 messages + roles；都用 JSON Schema 定义工具；tool calling 都是“模型提出→你执行→回传”的循环。差的只是字段名。

## 读 Tool Calling 代码的“三步速读法”

1. **找工具清单**：`tools=[...]`，看每个工具的 `name`/`description`/`input_schema` → 这个 Agent 有哪些能力。
2. **找 dispatcher**：哪个函数根据工具名真正执行（`run_tool`/`execute_tool` 之类）→ 工具到底干了什么。
3. **找循环**：那个 `while`（或 tool runner）→ 看它怎么 `判 stop_reason → 执行 → 把 tool_result 回填 messages → 再请求`。

## 最容易看错/记混的点

- **模型不执行工具**，只产出“要调哪个工具”的意图；执行的是你的代码（命令模式）。
- **API 无状态**：多轮靠你每次重发完整 `messages`，模型没记忆。
- **`response.content` 是块列表不是字符串**：先判 `type`（text / thinking / tool_use）再取值。
- **`stop_reason` 是循环的方向盘**：`tool_use` 就继续干活，`end_turn` 才结束。
- **`tool_result` 必须带 `tool_use_id`**：否则模型不知道这是哪个调用的结果。
- **`max_tokens` 是硬上限**：设小了回答会被截断。
- **算 token 用对家的接口**：数 Claude 别用 tiktoken。

---

## 参考链接 / 延伸阅读

> 本文代码示例基于 Anthropic Claude，所以下面以它的官方文档为主；**OpenAI 等其他家概念相同、字段名不同**，按各自文档核对即可（回扣 Part 8 对照表）。链接随版本更新，失效时按标题搜索。

**Anthropic（本文示例所用，最贴合）**
- 工具使用总览：https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview
- 工具调用如何运作（agentic loop 的官方讲解，直接对应本文 Part 4）：https://platform.claude.com/docs/en/agents-and-tools/tool-use/how-tool-use-works
- 手把手教程：构建一个会用工具的 Agent：https://platform.claude.com/docs/en/agents-and-tools/tool-use/build-a-tool-using-agent
- 流式输出：https://platform.claude.com/docs/en/build-with-claude/streaming
- 结构化输出：https://platform.claude.com/docs/en/build-with-claude/structured-outputs
- prompt caching（成本优化）：https://platform.claude.com/docs/en/build-with-claude/prompt-caching
- 模型与定价：https://platform.claude.com/docs/en/about-claude/models/overview
- Python SDK（直接看官方示例代码）：https://github.com/anthropics/anthropic-sdk-python

**OpenAI（字段名不同，自行核对）**
- Function calling 指南：https://platform.openai.com/docs/guides/function-calling

**MCP（路线图后面会专门学）**
- 官网（什么是 MCP）：https://modelcontextprotocol.io/
- 核心概念与架构：https://modelcontextprotocol.io/docs/learn/architecture

---

## 下一步建议

读完这篇，最有效的巩固方式是 **让 AI 生成一个带 1～2 个工具的小 Agent（比如路线图第 3 周的 "Android Project Analyzer Agent" 雏形：读 Gradle 文件 + 搜代码），然后用 Part 7 的方式逐行读懂那个 agentic loop**。

它会自然衔接路线图后面的 **Trace（把每一步 tool 调用、参数、结果、耗时、token 记下来）** 和 **Eval（评测工具调用是否正确）**——而这两件事，本质就是在上面那个循环的每一圈里“埋点记录”和“事后打分”。
