# 端侧 profiling 实战：延迟 / 内存 / 功耗 / 降频怎么科学测（写给 Android 开发者）

## 0. 这份文档怎么用

这是 [端侧 AI 学习轨道](on-device-ai-track.md) **Part 5.5 的展开篇**，端侧展开篇系列第 2 篇，接上一篇 [端侧推理实战](on-device-inference-llamacpp.md)（那篇 Part 6 只做了轻量 benchmark，这篇把"测"讲透）。

**为什么这篇对你最值钱**：profiling 是你相对纯算法背景的人**最大的差异化**。回扣 [轨道 Part 1 / 3.4](on-device-ai-track.md)——端侧的瓶颈是内存带宽和功耗，纯算法的人对这些没感觉，**你天天用 Profiler / Perfetto / batterystats 排查 App，只是从没把它们对准过推理**。把这套迁移过来，就是简历和面试白板上别人画不出来的东西。

取舍：

- 主轴是"怎么测得**准**、测得**可复现**、测出能**讲成结论**"。
- 工具以 **Android 原生（Profiler / Perfetto / dumpsys）+ llama.cpp 自带**为主，你大概率都用过。
- 命令给全，照着能跑。

带 ★ 的是重点：

- ★★★ **Part 1 测什么（prefill vs decode）** 和 **Part 4 功耗与降频** —— 一个是端侧 profiling 的核心认知，一个是你最稀缺、最能拉开差距的能力。
- ★★ **Part 2 速度**、**Part 3 内存**、**Part 6 实验纪律**、**Part 7 产出物**。

---

## Part 1 · 先想清楚：端侧推理到底要测什么 ★★★

### 1.1 四类指标，各对应一种产品体感

| 类别 | 核心指标 | 用户体感 |
|---|---|---|
| **速度** | 首 token 延迟、decode 吞吐(tok/s) | "点了多久才开始说"、"吐字快不快" |
| **内存** | 峰值内存、是否 OOM | "会不会闪退"、"低端机能不能用" |
| **功耗** | 每千 token 耗电、发热 | "烫不烫手"、"掉电快不快" |
| **稳定性** | 持续推理下的降频曲线 | "用一会儿是不是越来越卡" |

### 1.2 LLM 推理分两个阶段，瓶颈完全不同 ★★★

**这是端侧 profiling 最核心的认知，没有之一。** 一次推理分两段，别混着测：

| 阶段 | 在干嘛 | 瓶颈 | 决定 |
|---|---|---|---|
| **Prefill**（prompt processing）| 把你输入的 prompt 一次性"读"进去 | **算力**（compute-bound）| **首 token 延迟** |
| **Decode**（generation）| 一个一个往外吐 token | **内存带宽**（memory-bound）| **吞吐 tok/s** |

为什么 decode 是内存带宽瓶颈：每生成一个 token，都要把**整个模型权重**从内存搬一遍过一遍。所以 decode 速度几乎正比于"内存带宽 ÷ 模型大小"——这正是 [轨道 Part 3.4](on-device-ai-track.md) 说的，也是**量化能直接提速**的原因（搬的字节少了）。

> **llama.cpp 的 `llama-bench` 就是分开报这两个的**：`pp`（prompt processing = prefill）和 `tg`（text generation = decode）。看到这两个数，你要立刻知道它们卡在不同地方、要分别优化。

### 1.3 指标 → 体感 → 瓶颈 速查

```
首 token 慢  → prefill 慢   → 算力/大小核调度/prompt 太长
吐字慢       → decode 慢    → 内存带宽/模型太大/量化档太高
峰值内存高   → 权重 + KV cache → 模型规模/n_ctx
烫 & 掉电    → 持续高负载    → 散热/降频/后端选择
```

---

## Part 2 · 速度：首 token 延迟 与 decode 吞吐 ★★

### 2.1 两个必报数字

- **TTFT（Time To First Token）**：从提交到第一个 token 出现的毫秒数 = 模型加载（若未预载）+ prefill。**体感最直接**。
- **decode 吞吐（tok/s）**：生成阶段每秒多少 token。

### 2.2 怎么测

- **`llama-bench`（首选）**：llama.cpp 自带，直接给 `pp`（prefill tok/s）和 `tg`（decode tok/s），还能扫不同参数。
- **自己埋时间戳**：在你的 Kotlin/JNI 层，记"提交 → 第一个 token 回调"算 TTFT，记"生成段总时长 ÷ token 数"算 tok/s。

```kotlin
val t0 = SystemClock.elapsedRealtime()
var firstTokenAt = 0L; var n = 0
llm.complete(prompt) { token ->
    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()  // TTFT
    n++
}
val ttft = firstTokenAt - t0
val decodeTokPerSec = n * 1000.0 / (SystemClock.elapsedRealtime() - firstTokenAt)
```

> 用 `elapsedRealtime()`（单调时钟），不是 `currentTimeMillis()`——后者会被系统时间调整影响。**Android 类比**：跟你测帧耗时 / 启动耗时用单调时钟一个道理。

### 2.3 报告时务必带上下文

- **TTFT 随 prompt 长度变**（prefill 越长越慢）——报 TTFT 一定要说"在多长 prompt 下"。
- **模型加载时间单列**：加载 `.gguf` 可能好几百毫秒到数秒，影响冷启动，但和推理速度是两回事，别混进 TTFT。

---

## Part 3 · 内存：峰值、构成、OOM 边界 ★★

### 3.1 内存由什么构成（回扣推理篇 Part 4.3）

```
总占用 ≈ 模型权重 + KV cache + 激活/临时缓冲 + runtime + 你的 App 自身
              └ 量化档定         └ n_ctx 定
```

### 3.2 怎么测

| 工具 | 看什么 |
|---|---|
| **Android Studio Memory Profiler** | 实时曲线，Java vs **Native** 分开 |
| `adb shell dumpsys meminfo <包名>` | PSS 明细：Java Heap / **Native Heap** / Code / Graphics / **mmap 文件** |
| `/proc/<pid>/status` 的 VmRSS | 进程常驻内存 |

### 3.3 关键：推理内存基本在 Native，不在 Java 堆 ★

**Java 开发者最容易看错的点**：llama.cpp 是 native 的，模型内存**不进 Java/Kotlin 堆**，盯着 Java heap 看会以为"没占多少"。要看 **Native Heap + mmap 文件页**。

- llama.cpp 默认 **mmap** 模型文件：权重不是一次性 `malloc` 进 native heap，而是按需映射文件页。所以在 `meminfo` 里它常体现为 **file-backed / mmap** 那部分，PSS 会随实际访问增长。看"总 PSS"最实在。

> **Android 类比**：就像你用 native 解码大图 / 大视频——内存在 native 层，Java Profiler 那条线很平，真相在 native PSS。你踩过这个坑。

### 3.4 找 OOM 边界

低端机（如 4~6GB）+ 大 `n_ctx` 最容易爆。系统地测：固定模型，逐步加大 `n_ctx`（1K→2K→4K→8K），记每档峰值 PSS 和是否被系统 kill，得出"这台机器这个模型的安全上限"。

---

## Part 4 · 功耗与发热：端侧独有、最难也最值钱 ★★★

速度内存大家都会测，**功耗和降频是真正拉开差距的地方**——也是产品最敏感的（用户能感到烫手和掉电）。

### 4.1 为什么是命门

手机不能持续满载。LLM 推理是持续高负载，几分钟就让 SoC 升温 →**触发降频（thermal throttling）**→ 速度掉、体验崩。能把这条讲清楚 + 测出来，是端侧岗的强信号。

### 4.2 怎么测电

| 手段 | 怎么做 | 精度 |
|---|---|---|
| **batterystats + Battery Historian** | `dumpsys batterystats --reset` → 跑固定 workload → `dumpsys batterystats > stats.txt` → 喂给 Battery Historian 可视化 | 中，免费，通用 |
| **on-device power rails（ODPM）** | Pixel 6+ 等支持，经 Perfetto 抓功率轨 | 高，限机型 |
| **外接电流计 / 厂商功耗台** | 硬件测整机电流 | 最高，要设备 |

实操要点：**测之前 reset，跑一段固定长度的生成，记掉了多少电量 / 多少 mAh**，换算成**每千 token 耗电**才有可比性。

### 4.3 怎么测温与降频

```bash
adb shell dumpsys thermalservice          # 热状态、是否在 throttle
adb shell cat /sys/class/thermal/thermal_zone*/temp   # 各温区温度(部分机型/需权限)
adb shell cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq  # 各核当前频率
```

### 4.4 降频曲线：面试白板的杀手锏 ★

**持续生成 N 分钟，每隔几秒记一次 decode tok/s（和温度/频率），画成随时间的曲线。** 你会看到典型形状：

```
tok/s
  │ ────●●●●●●●╮                  开头满频很快
  │            ╰●●●●●╮            升温后降频，速度阶梯式下滑
  │                  ╰●●●●●●●●    最终稳定在一个"热稳态"
  └──────────────────────────▶ 时间
```

> 这张图 + 一句"持续推理 5 分钟后 tok/s 从 X 掉到 Y，对应 SoC 从满频降到 Z"，比任何"我懂端侧优化"的空话都有说服力。**纯算法的人从来不测这个。**

### 4.5 能效指标

报**每千 token 耗电（mAh / 1k tokens）**或 **tokens per joule**。这把"快"和"省"统一起来——有时低一档量化或换后端，tok/s 没快多少，但能效大幅改善，这就是值得讲的优化。

---

## Part 5 · 系统级追踪：Perfetto 看到底卡在哪 ★

测出"慢"之后，要知道**为什么慢**——用 **Perfetto**（Android 现在的系统追踪工具，取代了 systrace）。

- **你多半用过**：排查 jank、启动慢时拉过 trace。这次把它对准推理线程。
- 能看到：**CPU 大小核调度**（推理线程有没有跑在大核上）、**频率曲线**、各线程占用、是否有线程在空等。
- 典型发现：线程数设太多/太少、推理被排到小核、或 GPU offload 后反而在 CPU↔GPU 同步上空耗——这些用 Perfetto 一眼看出。

> **Android 类比**：和你用 Perfetto/Systrace 找渲染掉帧、主线程阻塞**完全是同一套手法和读图方式**，只是这次"耗时块"是推理 kernel。这是你的主场。

---

## Part 6 · 把测量做"科学"：可复现的实验纪律 ★★

不可复现的数字没人信，面试官一追问就露馅。守这几条：

- **控制变量**：固定机型、系统版本、**温度起点**（每次从凉的开始，或等回基线温）、电量区间、关后台、固定亮度、屏幕常亮。
- **预热 + 多次取中位数**：丢掉第一次（冷启动 / JIT / 文件未缓存），跑 N 次取**中位数**，别用平均（易被离群值带偏）。
- **标清环境**：每个数字都附——机型 / 芯片 / 系统 / 模型 / 量化档 / `n_ctx` / 后端 / 线程数。
- **分阶段、带条件**：prefill 和 decode 分开；TTFT 标 prompt 长度。

> **Android 类比**：跟你做启动耗时 / 帧率基准测试的纪律一模一样——同机型、多次、控温、丢首次、标清 build 配置。你已经会这套方法论，迁移成本几乎为零。

---

## Part 7 · 产出物：能放进简历 / 面试的东西 ★★

profiling 的终点是**可展示的产出物**，直接喂给 [PocketAgent 收官项目](edge-cloud-agent-capstone.md) 的 M5 和它的"设备指标 trace"字段（[蓝图 Part 4](edge-cloud-agent-capstone.md)）。

**① 多配置对比表**（横轴配置、纵轴指标）：

| 配置 | TTFT(ms) | decode tok/s | 峰值 PSS(MB) | mAh/1k tok | 5min 后 tok/s |
|---|---|---|---|---|---|
| Q4_K_M · CPU | … | … | … | … | … |
| Q8_0 · CPU | … | … | … | … | … |
| Q4_K_M · GPU | … | … | … | … | … |

**② 一张降频曲线图**（Part 4.4）。

**③ 一句结论型 resume bullet**：

> 在 \<机型/芯片\> 上对端侧 LLM 做系统 profiling：分离 prefill/decode 瓶颈，对比 Q4/Q8 与 CPU/GPU 后端的延迟、峰值内存与能效；通过\<某优化\>把持续推理的热稳态 tok/s 提升 X%、每千 token 耗电降低 Y%。

---

## Part 8 · 常见坑 / 为什么测不准

| 现象 | 多半因为 |
|---|---|
| 数字每次差很多 | 没控温 / 没预热 / 电量区间不同 / 后台在抢核 |
| 速度"虚高" | 只测了 decode、漏了 prefill；或 prompt 太短 |
| 模拟器上很快 | **模拟器数据无意义**，端侧一切以真机为准 |
| 跑久了变慢却没记录 | 没做降频曲线，只测了冷启动那一下 |
| 内存"看着不高" | 盯了 Java heap，没看 native / 总 PSS（Part 3.3）|
| 省电模式下偏慢 | 系统限频，关掉或标注 |

---

## 参考链接 / 延伸阅读

> 链接随版本变动，**失效时按标题搜索**。

**Android 性能 / 追踪（你的主场工具）**
- Perfetto（系统追踪）：https://perfetto.dev/
- 内存检查（Memory Profiler / meminfo）：https://developer.android.com/studio/profile/memory-profiler
- Battery Historian：https://github.com/google/battery-historian
- 功耗分析（Power Profiler）：https://developer.android.com/studio/profile/power-profiler

**llama.cpp 基准**
- 仓库（`llama-bench` 在 `tools/`，报 pp/tg）：https://github.com/ggml-org/llama.cpp

**回到轨道**
- [端侧 AI 学习轨道](on-device-ai-track.md)（Part 5.5 全景）
- [端侧推理实战](on-device-inference-llamacpp.md)（上一篇，跑通 + 基础 benchmark）
- [PocketAgent 收官蓝图](edge-cloud-agent-capstone.md)（设备指标 trace / M5）

---

## 下一步建议

挑一台你的真机，做一次**最小但完整**的 profiling：固定一个模型，测 `Q4_K_M` vs `Q8_0` 两档的 TTFT / decode tok/s / 峰值 PSS，再跑一条 5 分钟的降频曲线。把它整理成 Part 7 的表 + 图——这就是你端侧能力**最硬的一页作品**。

> 这一篇让你"测得准、讲得清"。下一篇 **端侧 Agent / RAG 实战**（轨道 Part 5.6）会回到功能层：小模型上怎么做可靠的工具调用、怎么做离线本地检索，把推理变成真正能用的端侧 Agent。
