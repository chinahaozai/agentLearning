# 端侧推理实战：llama.cpp 跑通 + 量化 / 后端对比（写给 Android 开发者）

## 0. 这份文档怎么用

这是 [端侧 AI 学习轨道](on-device-ai-track.md) 的第一篇**代码级展开篇**，对应轨道的 **Part 5.1–5.3** 和里程碑 **M0–M2**。

和已有两篇的分工，别搞混：

- [C++ 补齐计划](cpp-for-on-device-ai.md)：讲**语言和 JNI 桥**（指针、内存、`Java_xxx` 函数怎么写）。
- **本篇**：讲**推理本身**——模型怎么选、GGUF 是什么、量化档怎么挑和验证、运行时的关键参数、CPU/GPU/NPU 后端怎么切怎么比、怎么测出能写进简历的数字。

取舍：

- 以 **llama.cpp** 为主线（端侧生态最大、上手最快、文档最全），但**概念是通用的**——换 MNN / ONNX Runtime 只是 API 名字不同。
- 主轴是一条实操链：**跑通 → 换模型 → 比量化 → 比后端 → 测数字**。
- 能用 Android 类比就类比。

带 ★ 的是重点：

- ★★★ **Part 1 先跑通** 和 **Part 3 量化** —— 一个是你的第一个正反馈，一个是端侧工程师最该有判断力的地方。
- ★★ **Part 4 运行时参数** 和 **Part 5 后端对比**。

---

## Part 1 · 先跑通：最快的正反馈路径 ★★★

**别从语法书或论文开始。** 端侧学习最有效的起点是：在你自己的手机上，让一个模型离线蹦出第一个 token。这一刻的成就感会带你走完后面所有理论。

### 1.1 两条上手路，先走第一条

| 路 | 做法 | 适合 |
|---|---|---|
| **A. 改官方示例**（推荐）| clone llama.cpp，跑它自带的 `examples/llama.android`（Kotlin + JNI + CMake，开箱即用）| **第一次**，最快出结果 |
| B. 从零集成 | 自己写 CMake 链 llama.cpp、自己写 JNI | 跑通 A 之后，理解了再做 |

> 先走 A：把官方示例在真机上跑起来，**1～2 个晚上**能成。跑通后再对着它的代码逐行读（配合 [C++ 篇](cpp-for-on-device-ai.md) Part 5 的 JNI 视角），比任何教程都快。

### 1.2 拿一个模型

端侧只玩小模型。第一个建议直接下**别人量化好的 GGUF**，不要自己转换：

- 去 Hugging Face 搜 `<模型名> GGUF`（如 `Qwen2.5-1.5B-Instruct-GGUF`、`gemma-3 GGUF`）。
- 选 **1.5B~3B** 规模、**`Q4_K_M`** 档起步（为什么是它，见 Part 3）。
- 模型文件就是一个 `.gguf`，几百 MB 到 1+ GB。

### 1.3 跑通的检查点

跑起来后，做这一个动作确认它是**真端侧**：

> **打开飞行模式**，再发一句话。还能正常出 token = 推理真的发生在本机，不是偷偷调了云。

**Android 类比**：整个过程就是**集成一个本地 native 库 + 加载一个大资源文件**。`.so`（llama.cpp 编出来的）类似你引入的 native 依赖，`.gguf` 类似一个超大的 assets 资源。你做过"集成 native 库处理数据"，只是这次数据是 token、库是推理引擎。

---

## Part 2 · 模型与格式：GGUF 是什么 ★

### 2.1 模型 = 一份权重文件

一个训练好的模型，本质就是**一大堆数字（权重）**。"加载模型"就是把这个文件读进内存；"推理"就是拿这些数字做矩阵运算。**模型文件就是模型的全部**，没有别的隐藏依赖。

### 2.2 GGUF：llama.cpp 生态的标准格式

**GGUF** 是 llama.cpp 用的单文件格式，特点：

- **单文件**：权重 + 元数据 + 分词器（tokenizer）+ chat 模板，全打包进一个 `.gguf`。
- **自带量化**：文件名直接标了量化档，如 `qwen2.5-1.5b-instruct-q4_k_m.gguf`。
- **跨平台**：同一个文件，Android / PC / Mac 都能用同一套 llama.cpp 加载。

> 其他生态有别的格式：LiteRT 用 `.task`/`.tflite`，ONNX 用 `.onnx`，ExecuTorch 用 `.pte`。**概念一样：都是"打包好的、可被对应 runtime 加载的模型文件"**，只是各家格式不通用。

### 2.3 选多大的：回扣内存预算

端侧选型第一约束是**内存**。回扣 [轨道 Part 3.3](on-device-ai-track.md) 的估算（权重内存 ≈ 参数量 × 每权重字节）：

| 规模 | Q4（×0.5）权重 | 适合 |
|---|---|---|
| 0.5B | ~0.3 GB | 低端机 / 极致省内存 |
| 1.5B | ~0.9 GB | **中端机甜点** |
| 3B | ~1.7 GB | 中高端机 |
| 7B | ~3.8 GB | 旗舰机勉强，多数手机别碰 |

> 别忘了**权重不是全部**：还有 KV cache（随上下文长度涨）、runtime 本身、以及你 App 自己的内存。实际可用预算要再打折（见 Part 4.3）。

---

## Part 3 · 量化：怎么选档、怎么验证质量 ★★★

量化是端侧工程师最该有判断力的地方。回扣 [轨道 Part 3.3](on-device-ai-track.md)：量化是**用精度换体积和速度**。这一节讲怎么**读懂档位、选对档、验证质量**。

### 3.1 量化档命名解码

看到 `Q4_K_M` 不要懵，拆开看：

```
Q4_K_M
│  │ └─ 尺寸档：S(small) / M(medium) / L(large)，同样 4-bit 里再分大小
│  └─── K = K-quant（现代量化方法，比老的更聪明地分配比特）
└────── 4 = 平均每权重约 4 bit
```

常见档，从大到小（质量从高到低、体积从大到小）：

| 档 | 约 bit | 定位 |
|---|---|---|
| `Q8_0` | 8 | 几乎无损，体积大，端侧少用 |
| `Q6_K` | 6.5 | 高质量 |
| `Q5_K_M` | 5.5 | 质量与体积平衡 |
| **`Q4_K_M`** | **4.5** | **甜点：多数场景的默认选择** |
| `Q3_K_M` | 3.5 | 更省，质量开始明显下降 |
| `IQ2_M` 等 | 2~3 | 极限压缩（IQ = importance 量化），救低端机用 |

### 3.2 选档经验

- **起步无脑选 `Q4_K_M`**：业界公认的体积/质量甜点。
- 质量不够再往上（`Q5_K_M`/`Q6_K`）；内存实在不够再往下（`Q3`/`IQ`）。
- **别用 `Q8`/FP16 上端**：体积翻倍、速度更慢，端侧不值。

### 3.3 怎么验证"质量掉了多少" ★

量化必然损失精度，工程师的本事是**量化这个损失、判断能不能接受**。三种办法，由轻到重：

1. **固定输入肉眼对比**（最快）：拿 5～10 条代表性输入，同一模型跑 `Q8` 和 `Q4`，并排看输出有没有变差。
2. **perplexity（困惑度）**：llama.cpp 自带 `llama-perplexity` 工具，在一段文本上算困惑度。**数值越低越好**；比较 `Q4` 比 `Q8` 高多少，就是量化的"质量代价"。
3. **任务正确率**（最贴业务）：用一个评测集算成功率——这直接接上主线 [Eval 篇](agent-eval-and-metrics.md) 和 [收官项目](edge-cloud-agent-capstone.md) M5。

> **这就是你简历里的硬货**："我对比了 Q4_K_M 和 Q8_0：体积从 X 降到 Y、decode 速度从 A 升到 B、perplexity 只升了 C%、任务成功率几乎不变——所以端侧选 Q4_K_M。" 这种带数据的取舍判断，纯调 API 的人讲不出来。

### 3.4 体积 / 速度 / 质量三角

记住这个权衡（量化档越低）：

```
体积 ↓↓   速度 ↑（要搬的字节少，端侧瓶颈是内存带宽，回扣轨道 Part 3.4）
质量 ↓     稳定性/格式遵循 ↓（小档模型更容易胡说、不听格式）
```

---

## Part 4 · 运行时怎么用：8 步骨架的参数展开 ★★

回扣 [C++ 篇 Part 4.1](cpp-for-on-device-ai.md) 的 8 步骨架（加载→上下文→tokenize→decode 循环→采样→detokenize→释放）。这里展开**实战中真正要调的参数**。

### 4.1 加载与上下文的关键参数

```cpp
// 示意，具体字段以你那版 llama.cpp 头文件为准（API 会变）
cparams.n_ctx        = 2048;   // 上下文窗口：能记住多少 token（越大越吃内存，见 4.3）
cparams.n_threads    = 4;      // CPU 线程数：一般设为大核数
cparams.n_gpu_layers = 0;      // 把多少层放 GPU 跑（0=纯 CPU，见 Part 5）
```

### 4.2 采样参数：控制"输出多随机"

模型每步输出的是**下一个 token 的概率分布**，采样参数决定怎么从里面挑：

| 参数 | 作用 | 对照 |
|---|---|---|
| `temperature` | 越高越随机/有创意，越低越确定 | 同主线 [LLM API 篇](llm-api-and-tool-calling.md) |
| `top_k` / `top_p` / `min_p` | 只在概率最高的若干候选里挑，过滤长尾乱词 | 同上 |
| `repeat_penalty` | 抑制复读 | 小模型尤其需要 |
| `n_predict` | 最多生成多少 token | ≈ `max_tokens` |

> 端侧小模型更容易"跑飞"（复读、胡说、不收尾）。`temperature` 调低（如 0.2~0.7）、加 `repeat_penalty`，输出会稳很多。

### 4.3 KV cache：被忽视的内存大户 ★

模型生成时要缓存已处理 token 的中间结果（KV cache），**它随上下文长度线性增长**，是权重之外的第二大内存消耗：

```
KV cache 内存 ≈ 2 × 层数 × n_ctx × 隐藏维度 × 每元素字节
```

> 实战意义：`n_ctx` 不是越大越好。设 8K 上下文可能比模型权重还吃内存，直接 OOM。**端侧按需设小**（如 2K），检索/对话内容精简（回扣 [轨道 Part 5.6](on-device-ai-track.md) 端侧 RAG）。

### 4.4 流式输出

decode 循环里每出一个 token 就 `token_to_piece` 转成文字片段，立刻回传 UI——这就是打字机效果。怎么从 native 把每个 token 推回 Kotlin，见 [C++ 篇 Part 5.3](cpp-for-on-device-ai.md)。

---

## Part 5 · 后端对比：CPU / GPU / NPU 怎么切、怎么比 ★★

同一个模型可以跑在不同硬件后端上，速度和功耗差很多。这是端侧 JD 高频考点。

### 5.1 端侧的几种后端

| 后端 | llama.cpp 支持 | 现实 |
|---|---|---|
| **CPU** | ✅ 一等公民（ARM NEON / dotprod 优化）| **最稳的基线**，先用它 |
| **GPU（Vulkan）** | ✅ 有 Vulkan 后端 | Adreno/Mali 上能用，但移动 GPU 提速**时好时坏**，要实测 |
| **GPU（OpenCL）** | ✅ 有 Adreno OpenCL 后端 | 高通 GPU 上可试 |
| **NPU** | ⚠️ **基本没有现成支持** | 见 5.3 的诚实说明 |

### 5.2 在 llama.cpp 里怎么切

- **CPU↔GPU**：主要靠 `n_gpu_layers`——把多少层 offload 到 GPU。`0` = 纯 CPU，设大 = 更多层上 GPU。
- 要启用 GPU 后端，编译时要开对应开关（如 `GGML_VULKAN=ON`），并确保设备/驱动支持。

### 5.3 NPU 的现实（别被带歪）★

> **诚实提醒**：想跑 **NPU**，llama.cpp **不是合适工具**——它的 NPU 支持很有限。NPU 路线要换栈：**高通 QNN / AI Hub**、**MNN**（对部分芯片 NPU 适配更好）、**ExecuTorch + QNN 后端**、或 **MediaPipe/LiteRT**。

所以一个务实的学习顺序是：

1. 先用 **llama.cpp + CPU** 把端侧推理、量化、Agent 全链路跑通（最低摩擦）。
2. 想做 **NPU 对比**（JD 里 QNN/NeuroPilot 那条，回扣 [轨道 Part 5.4](on-device-ai-track.md)），再换 MNN / QNN / ExecuTorch 专门做一版。

### 5.4 该选哪个后端

| 场景 | 选 |
|---|---|
| 入门 / 求稳 / 中小模型 | **CPU**（够快、最省心）|
| 想榨吞吐、设备 GPU 给力 | GPU（Vulkan），但**必须实测**有没有真提速 |
| 追极致延迟/功耗、对接旗舰芯片 | NPU（换 QNN/MNN/ExecuTorch 栈）|

---

## Part 6 · 基础 benchmark：测出能写进简历的数字 ★

跑通之后，**用数据说话**。这一节是轻量 benchmark；完整的延迟/内存/功耗方法论（Perfetto、能耗、降频曲线）留给下一篇 profiling 展开篇。

要测的四个核心数字：

| 指标 | 怎么测 | 说明 |
|---|---|---|
| **首 token 延迟** | 从提交到第一个 token 的毫秒数 | 体感最直接 |
| **decode 吞吐（tok/s）** | 生成阶段每秒多少 token | llama.cpp 自带 `llama-bench` 直接给 |
| **模型加载时间** | 加载 `.gguf` 到可推理的耗时 | 影响冷启动体验 |
| **峰值内存** | Android Studio Profiler 看 | 决定低端机会不会 OOM |

两个一定要注意的坑：

- **冷启动 vs 持续**：第一次和连续跑几分钟后速度不一样——手机**发热降频**（回扣 [轨道 Part 3.4](on-device-ai-track.md)）。两个都要测、都要报。
- **真机为准**：模拟器数字没有任何参考意义。

> 产出物建议：一张表，横向是配置（量化档 × 后端），纵向是上面四个指标。这张表就是 [收官项目](edge-cloud-agent-capstone.md) M5 的雏形，也是面试白板上你能画出来的东西。

---

## Part 7 · 接进项目

跑通和测完，把它收进一个 Kotlin 友好的接口（隐藏 JNI 细节），就能接进 [PocketAgent 收官项目](edge-cloud-agent-capstone.md) 的 M0–M2，或直接试着推进你现职 App 的一个小功能（[轨道 Part 7](on-device-ai-track.md) "把现职变成作品集"）。

```kotlin
// 目标：让上层完全感觉不到 native / llama.cpp 的存在
interface OnDeviceLlm {
    suspend fun load(modelPath: String, nCtx: Int = 2048)
    fun complete(prompt: String, onToken: (String) -> Unit)   // 流式回调
    fun close()
}
```

---

## Part 8 · 常见坑速查

| 现象 | 多半是 |
|---|---|
| 加载就崩 / OOM | 模型太大或 `n_ctx` 太大（Part 2.3 / 4.3）|
| `UnsatisfiedLinkError` | `.so` 没打进、ABI 不符、JNI 函数名拼错（[C++ 篇 Part 7](cpp-for-on-device-ai.md)）|
| 输出乱码 / 不停 / 答非所问 | **chat 模板没套对**——每个模型有自己的对话格式，要用对应模板包裹 prompt |
| 中文 token 异常 | tokenizer 问题，确认用的是该模型自带的 |
| 很慢 | 没开多线程、跑了过大的模型、或 GPU offload 反而拖累——回 Part 5 实测 |
| 上下文越长越慢/越占内存 | KV cache 在涨（Part 4.3），裁剪历史 |

---

## 参考链接 / 延伸阅读

> 链接随版本变动，**失效时按标题搜索**。llama.cpp 迭代很快，API 与工具名以你 clone 的那版仓库为准。

**llama.cpp**
- 仓库（含 Android 示例 `examples/llama.android`）：https://github.com/ggml-org/llama.cpp
- 量化档说明 / `llama-bench` / `llama-perplexity`：见仓库 `tools/` 与 README

**模型**
- Hugging Face（搜 `<模型> GGUF`）：https://huggingface.co/models

**其他端侧 runtime（换栈做 NPU 时）**
- MNN（阿里）：https://github.com/alibaba/MNN
- ExecuTorch（PyTorch，含 QNN 后端）：https://github.com/pytorch/executorch
- 高通 AI Hub：https://aihub.qualcomm.com/

**回到轨道**
- [端侧 AI 学习轨道](on-device-ai-track.md)（全景）
- [C++ 补齐计划](cpp-for-on-device-ai.md)（JNI / 语言）
- [PocketAgent 收官蓝图](edge-cloud-agent-capstone.md)（把它用起来）

---

## 下一步建议

照 **Part 1 直奔跑通**：clone llama.cpp 的 `examples/llama.android`，真机出第一个 token。然后做一次最小对比——下载同一模型的 `Q4_K_M` 和 `Q8_0` 两档，用 Part 6 的指标 + Part 3.3 的质量验证，得出"端侧为什么选 Q4"的结论。

> 这一篇让你"跑得起来、比得出来"。下一篇 **端侧 profiling 展开篇** 会把"测"这件事讲透：延迟/内存/功耗/降频到底怎么科学测量、怎么画出能放进简历和面试的曲线。
