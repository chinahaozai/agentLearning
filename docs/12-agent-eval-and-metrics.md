# Agent Eval：评测与回归（写给 Android / Java 开发者）

## 0. 这份文档怎么用

系列第四篇，对应路线图学习优先级 #4「eval dataset 与评测指标」、规划里的「Agent Eval 指标设计」。

和第三篇（Trace）一样，这是个 **设计 / 方法论题**，不是“读某个现成 API”。配方不变：**概念 + 设计 + 看懂代码**，**自研为主、入门为主**，Java / Android 类比、难点上代码、结尾速查表 + 参考链接。

一句话剧透，把整篇串起来：

> **Eval 就是给 Agent 写测试 —— 只不过断言是“模糊”的。** 而且它直接建在第三篇的 trace 上：每跑一个测试用例就产出一条 trace，打分就是读这条 trace。

路线图把 Eval 称为 **“Agent 岗位的重要分水岭”**：很多 demo 能跑一次，但**证明不了“新版比旧版更好”**。会做 eval，是从“能写 demo”到“能交付可迭代系统”的关键一步。

带 ★ 的是重点：
- ★★ **Part 3 数据集设计**、**Part 4 评测指标**、**Part 5 Eval Harness（逐行精读）** —— 这篇的核心。
- ★ Part 2 心智模型。

---

# Part 1 · 为什么 Eval 是“分水岭”

你做 Android 时，改完代码为什么敢发版？因为 **有测试**：单测、Espresso 跑一遍，绿了就有底。

Agent 没有这套，你就抓瞎：

- 改了 system prompt，**怎么知道没把别的场景搞坏？**
- 从 Opus 换成 Sonnet 省钱，**质量掉了多少？值不值？**
- 加了个新工具，**整体成功率是升了还是降了？**

凭感觉“试两条看着还行”——这就是 demo 级别。**Eval 就是给 Agent 补上那套“测试 + 回归”**，让每次改动都有数据支撑：是变好了、变差了、还是没动。

**为什么 Agent 的“测试”比普通测试难？** 两点：

1. **非确定**：同样输入，模型每次输出可能不同，不能指望逐字相等。
2. **输出模糊**：答案是自然语言，“4”“答案是 4”“应该是四”都对，没法简单 `assertEquals`。

这两点决定了 eval 的全部特殊性——下一节就讲怎么应对。

---

# Part 2 · 核心心智模型：Eval = 给 AI 写测试 ★

把 eval 的每个概念映射到你熟悉的测试，立刻就懂：

| 测试（你熟的） | Eval（Agent 版）|
|---|---|
| 测试用例 test case | eval case（一条评测用例）|
| 输入 / 参数 | case 的 `input`（喂给 Agent）|
| 断言 `assertEquals(expected, actual)` | 期望输出 / 期望工具调用 + 打分逻辑 |
| 测试套件 JUnit / pytest | eval dataset + harness（评测脚本）|
| 通过率 pass rate | **任务成功率** task success rate |
| 回归测试 regression test | 重跑数据集，确认改动没让指标变差 |
| 在 CI 里跑测试卡发布 | eval 设阈值门槛卡发布 |

**唯一的大不同：断言是“模糊”的。**

普通测试：`assertEquals("4", result)` —— 错一个字符就红。
Eval：模型回 `"答案是 4"` 也该算对。所以“断言”得换成三种更宽松的判法（Part 4 展开）：

1. **宽松/规则匹配**（包含、忽略大小写、比对工具名……）
2. **LLM-as-judge**（用另一个模型按标准打分）
3. **人工评分**（兜底，最准最贵）

> 记住这条主线：**eval 就是测试，只是“断言”从精确相等，换成了能容忍模糊的打分。** 后面所有内容都是这句话的展开。

---

# Part 3 · Eval Dataset 设计（数据结构）★★

数据集就是“一批测试用例”。先把一条用例的结构定出来（Pydantic，回扣第一、三篇）：

```python
from pydantic import BaseModel, Field

class EvalCase(BaseModel):
    id: str
    input: str                               # 喂给 Agent 的输入(相当于测试入参)
    expected_output: str | None = None       # 期望的最终答案(可选,相当于断言的期望值)
    expected_tools: list[str] = Field(default_factory=list)  # 期望调用哪些工具
    tags: list[str] = Field(default_factory=list)            # 分类:normal/edge/regression...
```

一份数据集就是 `list[EvalCase]`，可以写在 JSON / YAML 里：

```json
[
  {
    "id": "order-001",
    "input": "查一下订单 A123 的状态",
    "expected_output": "已发货",
    "expected_tools": ["query_order"],
    "tags": ["normal"]
  },
  {
    "id": "order-002",
    "input": "订单 ZZZ999 到哪了",
    "expected_output": "订单不存在",
    "expected_tools": ["query_order"],
    "tags": ["edge"]
  }
]
```

**怎么造数据集**（这是 eval 做得好不好的关键）：

- **从真实使用 / trace 里捞**：第三篇存的 trace 就是金矿——把真实跑过的典型任务、尤其是**出过错的**，挑出来变成用例。
- **覆盖三类**：`normal`（常规）、`edge`（边界：空输入、不存在的订单、超长输入）、`regression`（曾经踩过的坑，专门留一条防复发）。
- **每条用例的期望要“可判定”**：要么有明确答案（`expected_output`），要么有明确的工具调用预期（`expected_tools`），否则没法自动打分。

> **入门建议：小而精 > 大而泛。** 20~50 条覆盖到位的用例，比 500 条重复用例有用得多。先把“最该不出错”的场景钉住。

**Android 类比**：这跟你整理一份测试用例清单一回事——常规路径、边界条件、回归用例（修过的 bug 留个测试防它回来）。`expected_tools` 就像断言“这个方法内部应该调用了某个依赖”。

---

# Part 4 · 评测指标：到底打哪些分 ★★

路线图列的指标，分两类看：**对不对**（质量）和 **快不快、贵不贵**（成本）。后者直接从第三篇的 trace 里读，几乎白送。

| 指标 | 含义 | 怎么算 |
|---|---|---|
| **任务成功率** task success rate | 最终结果对不对 | 通过的用例数 / 总用例数 |
| **工具调用准确率** tool call accuracy | 该调的工具调了吗、对不对 | 从 trace 读 tool spans，对比 `expected_tools` |
| **步骤准确率** step accuracy | 中间步骤走得对不对 | 对比实际步骤序列与期望 |
| **平均延迟** latency | 每次多慢 | trace 各 span 耗时之和的平均 |
| **平均成本** token cost | 每次多少钱 | trace 的 `total_cost` 的平均 |

**关键认知：别只盯成功率。** 一个又对、又慢、又贵的版本，不一定赢过一个稍差但快一半、便宜一半的版本。**质量 + 成本要一起看**（Part 6 的对比表会体现）。

## 三种“断言”怎么写

### ① 宽松 / 规则匹配（有标准答案时）

适合分类、信息提取、工具名比对——这些有明确对错：

```python
def check_output(expected: str, actual: str) -> bool:
    # 最宽松:期望答案是否出现在实际回答里(忽略首尾空白)
    return expected.strip() in actual.strip()

def check_tools(expected: list[str], called: list[str]) -> bool:
    # 期望调用的工具是否都被调到了(子集判断)
    return set(expected) <= set(called)
```

约等于 Java 里的 `assertTrue(actual.contains(expected))`、`assertThat(called).containsAll(expected)`——只是放宽到“包含/子集”而非“全等”。

### ② LLM-as-judge（开放式输出，没法精确匹配时）

“这段解释写得好不好”“回答是否礼貌且准确”——这类没法用规则判，就**让一个模型按评分标准（rubric）来打分**。这里直接复用第二篇的**结构化输出**（让裁判模型返回结构化结果）：

```python
class Judgement(BaseModel):     # 让裁判模型按这个结构回答
    passed: bool
    reason: str

JUDGE_PROMPT = """你是评分员。判断【实际回答】是否满足【期望】。
只看是否达标，不挑剔措辞。

问题：{question}
期望：{expected}
实际回答：{actual}
"""

def judge(question: str, expected: str, actual: str) -> Judgement:
    resp = client.messages.parse(           # parse = 强制按 Judgement 结构返回(doc2)
        model="claude-opus-4-8",            # 裁判建议用强模型
        max_tokens=512,
        messages=[{"role": "user",
                   "content": JUDGE_PROMPT.format(
                       question=question, expected=expected, actual=actual)}],
        output_format=Judgement,
    )
    return resp.parsed_output               # 一个 Judgement(passed=..., reason=...)
```

**Android 类比**：有点像把“断言”这件需要主观判断的事，委托给一个“自动评审员”。但记住它**不是真理**（Part 8 会讲它的坑）：裁判也会错，要给清晰 rubric、用强模型、并抽样人工核对。

### ③ 人工评分

最准也最贵，做兜底和校准用。实践里是 **自动为主、人工抽查**：自动评分跑全量，人工抽一部分检查自动分准不准（尤其校准 LLM-judge）。

---

# Part 5 · 跑一遍：Eval Harness（逐行精读）★★

把前面拼起来：遍历数据集 → 每条跑一次 Agent（产出 trace）→ 打分 → 汇总。**这段是本文的“看懂代码”锚点，能读懂就达标。**

先定结果结构：

```python
class EvalResult(BaseModel):
    case_id: str
    passed: bool
    output_ok: bool
    tools_ok: bool
    latency_ms: float
    cost: float
    note: str = ""
```

给单条用例打分（注意这里**全程在读第三篇的 trace**）：

```python
def score_case(case: EvalCase, trace: Trace) -> EvalResult:
    # ① 最终答案:取 trace 里最后一个 llm 步骤的输出文本
    llm_spans = [s for s in trace.spans if s.type == "llm"]
    final_text = _text_of(llm_spans[-1].output) if llm_spans else ""
    output_ok = (case.expected_output is None
                 or check_output(case.expected_output, final_text))

    # ② 工具调用:从 trace 的 tool 步骤里读出"实际调了哪些工具"
    called = [s.name for s in trace.spans if s.type == "tool"]
    tools_ok = check_tools(case.expected_tools, called)

    # ③ 成本/延迟:trace 现成的(第三篇就汇总好了)
    latency = sum(s.latency_ms for s in trace.spans)
    cost = trace.total_cost

    return EvalResult(
        case_id=case.id,
        passed=output_ok and tools_ok,        # 输出对 且 工具对,才算过
        output_ok=output_ok, tools_ok=tools_ok,
        latency_ms=latency, cost=cost,
    )
```

跑全量并汇总成指标：

```python
def run_eval(dataset: list[EvalCase], label: str) -> list[EvalResult]:
    results = []
    for case in dataset:
        trace = run_agent(case.input)         # 跑 Agent(第二/三篇),产出 trace
        results.append(score_case(case, trace))

    n = len(results)
    print(f"[{label}]  共 {n} 条")
    print(f"  任务成功率   {sum(r.passed   for r in results)/n:.0%}")
    print(f"  工具准确率   {sum(r.tools_ok for r in results)/n:.0%}")
    print(f"  平均延迟     {sum(r.latency_ms for r in results)/n:.0f}ms")
    print(f"  平均成本/次  ${sum(r.cost     for r in results)/n:.4f}")
    return results
```

逐步看（对应注释序号）：

1. **最终答案对不对**：从 trace 最后一个 `llm` span 的输出取文本，用宽松匹配判（`expected_output` 没填就跳过这项）。
2. **工具调用对不对**：从 trace 里筛出 `type == "tool"` 的 span，取它们的 `name`，就是“实际调了哪些工具”，和 `expected_tools` 比——**这正是第三篇 trace 的价值兑现**：没有 trace，你根本拿不到“它中途调了什么”。
3. **延迟 / 成本**：trace 早就汇总好了，直接读。

`run_eval` 就是个 **测试 runner**：遍历用例、逐条打分、最后报一张指标小结。和 pytest / JUnit 跑完打印 `X passed, Y failed` 是同一个角色，只是多报了延迟和成本。

> `_text_of(...)` 是个把 span 输出取成纯文本的小helper（trace 里输出可能是结构化的），按你的 trace 格式实现即可，这里不展开。

---

# Part 6 · 对比两个版本 & 回归测试

这才是 eval 的“分水岭”价值所在——**用同一份数据集，量化对比两个版本**。

## A/B 对比

把 Agent 参数化（不同 prompt / 模型），同一份数据集各跑一遍：

```python
# 把 run_agent 改成可配置:run_agent(input, model=..., system=...)
v1 = run_eval(dataset, label="v1-opus-旧prompt")
v2 = run_eval(dataset, label="v2-opus-新prompt")
# 然后把两组 results 并排成一张表(Part 7)
```

> 要对比，前提是 `run_agent` 能接收“用哪个模型、哪个 prompt、哪套工具”作为参数。这也是为什么生产里 Agent 的 model/prompt/tools 通常是**配置**而非写死。

## 回归测试

每次改完 prompt / 换模型 / 加工具，**重跑数据集**，确认：成功率没掉、工具准确率没降、成本没失控。和你 Android 里“改完跑回归测试，确认没破坏老功能”一模一样。

更进一步：**接进 CI，设阈值当门槛**——比如“成功率 < 90% 或 平均成本涨 20% 就拦住合并/发布”。这就把“凭感觉发版”变成了“数据达标才发版”。

```python
results = run_eval(dataset, label="ci")
rate = sum(r.passed for r in results) / len(results)
assert rate >= 0.90, f"成功率 {rate:.0%} 低于阈值 90%，拦截发布"   # CI 里红灯
```

---

# Part 7 · 成果长什么样：一份评测报告

A/B 跑完，把两个版本并排，就是一张能拿去做决策的报告：

```
指标             v1 (Opus, 旧 prompt)   v2 (Opus, 新 prompt)
任务成功率        78%                    91%   ↑
工具调用准确率     85%                    96%   ↑
平均延迟          2400ms                 2600ms ↓(略慢)
平均成本 / 次      $0.011                 $0.013 ↓(略贵)
```

结论一目了然：v2 **质量明显更好**，代价是 **稍慢、稍贵**。值不值，由业务定——但现在你是**拿着数据**在决策，不是拍脑袋。这就是 eval 给你的东西。

单条用例的打分结果（落库或写报告时）大致长这样：

```json
{
  "case_id": "order-002",
  "passed": false,
  "output_ok": false,
  "tools_ok": true,
  "latency_ms": 1900,
  "cost": 0.009,
  "note": "调对了 query_order，但没正确回复'订单不存在'"
}
```

读 AI 生成的 eval 代码时，最终产物就是这种“逐条结果 + 一张汇总表”——心里有这张成品图，再看打分代码就知道每行在为哪个指标服务。

---

# Part 8 · 速查表 & 易错点

## 指标速查

| 指标 | 一句话 |
|---|---|
| 任务成功率 | 过的用例 / 总用例 |
| 工具调用准确率 | 从 trace 读 tool spans，比对期望工具 |
| 步骤准确率 | 中间步骤序列对不对 |
| 平均延迟 / 成本 | 从 trace 读，别只看质量不看这俩 |

## 读 Eval 代码的“三步速读法”

1. **找数据集**：`EvalCase` 有哪些字段、用例从哪来 → 在测什么、覆盖了哪些场景。
2. **找打分逻辑**：`score_case` 怎么判对错——是规则匹配、还是 LLM-judge？工具调用是不是从 trace 读的？
3. **找 runner 与对比**：`run_eval` 怎么汇总指标、怎么 A/B 两个版本、有没有设 CI 阈值。

## 最容易踩的坑

- **LLM-as-judge 不是真理**：裁判模型有偏见（偏长答案、偏自己风格），会误判。要给清晰 rubric、用强模型当裁判、并**抽样人工核对**校准。
- **别对着 eval 集“过拟合”**：只盯着这几十条用例调 prompt，分数会涨但实际没变好——等于“对着测试用例写作弊代码”。数据集要定期补充新鲜的真实用例。
- **小数据集噪声大**：20 条用例里成功率差 1~2 个点，可能只是随机波动，不代表真的更好。样本太小别过度解读。
- **只看成功率会误判**：又对又慢又贵不算赢。**质量、延迟、成本三件套一起看。**
- **期望要可判定**：用例的 `expected_*` 含糊（“答得好就行”），就没法自动打分。要么给明确答案/工具，要么明确交给 LLM-judge 按 rubric 判。

---

## 参考链接 / 延伸阅读

> 下面是相关开源项目/平台入口，内容随版本更新；**失效时按项目名搜索即可**（名字比 URL 稳）。

**把 Eval 当“测试”来跑的工具**
- promptfoo（开源，把 prompt/eval 当测试跑，内置断言与版本对比）：https://github.com/promptfoo/promptfoo
- DeepEval（号称“LLM 界的 pytest”）：https://github.com/confident-ai/deepeval
- OpenAI Evals（评测框架）：https://github.com/openai/evals

**平台自带评测**（第三篇也提过这几家）
- LangSmith Evaluation：https://docs.smith.langchain.com/
- Langfuse：https://langfuse.com/

**示例代码**
- Anthropic Cookbook（含评测/打分示例）：https://github.com/anthropics/anthropic-cookbook

---

## 下一步建议

读完这篇，最有效的巩固方式是 **给前三篇攒下来的小 Agent 配一份 eval**：写 10~20 条 `EvalCase`（从你跑过的 trace 里挑），实现 `score_case` + `run_eval`，然后**故意改坏一次 prompt**，看成功率掉下来——你就亲眼见证了“eval 能抓住质量回退”。

到这里，四件套闭环了：

> **FastAPI（造服务）→ LLM API/工具（让模型干活）→ Trace（记录干了什么）→ Eval（判断干得好不好）**

下一步是路线图 #5 **Docker / Redis / Postgres**：把这套东西**打包成一个能一键启动、可部署的服务**——也就是从“能在你机器上跑的脚本”变成“别人也能跑起来的系统”。
