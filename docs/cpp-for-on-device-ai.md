# C++ 补齐计划（写给转端侧 AI 的 Android / Java 开发者）

## 0. 这份文档怎么用

这是 [端侧 AI 学习轨道](on-device-ai-track.md) 的配套技能篇。真实端侧 JD 里 C++ 几乎是硬门槛（6 份里 4 份要求"精通 C++：内存管理 / 多线程 / 性能分析"），**这是你从 Android 转端侧最大的、也最该优先补的缺口**。

但**目标要划清楚**，否则会陷进 C++ 的无底洞：

> 你的目标不是"成为 C++ 专家"，而是**够用到能"读懂、改、集成"端侧推理代码**——具体说就是：看懂 llama.cpp 这类推理引擎的公共 API、改它的 JNI 层、把推理能力封装成 Kotlin 能调的模块塞进 App。

取舍（和主线笔记一致）：

- 只讲"读 / 改 / 集成推理代码"必需的 C++，**不重要、不常用的直接砍**（Part 9 列了"明确不用学"的清单）。
- 全程用 **Kotlin / Java 类比**——你不是零基础学编程，是把已有的工程直觉迁移到一门新语言。
- 难点上代码，尤其是**内存所有权**和 **JNI**这两块。

带 ★ 的是重点：

- ★★★ **Part 3 内存与所有权** 和 **Part 5 JNI** —— 一个是 C++ 与 Java 最伤人的差异（也是崩溃的来源），一个是你"把 native 推理接进 App"的命根子。
- ★★ **Part 2 语言速查对照** 和 **Part 4 读懂 llama.cpp**。

---

## 1. 先扭正心智：C++ 不是"没有 GC 的 Java" ★

你已经会一门面向对象语言，所以语法层面（`if`/`for`/`class`/函数）几乎不用学。真正会绊倒 Java/Kotlin 开发者的，是下面这几个**本质差异**。先把它们想正，后面读代码不会懵。

| Java/Kotlin 的世界 | C++ 的世界 | 你要扭转的认知 |
|---|---|---|
| 对象都在堆上，引用传递，GC 回收 | 对象可以在**栈**上（出作用域即销毁），也可以在堆上（你管生死）| "谁负责释放"成了你要时刻想的事 |
| `null` + 空安全（Kotlin）| 裸指针可能悬垂、可能野指针 | 访问已释放的内存 = 崩溃或更糟（不是干净的 NPE）|
| 一次编译成字节码，JVM 跑 | **编译 + 链接**成机器码，分平台（ABI）| `.h` 声明、`.cpp` 定义、`.so`/`.a` 产物，要在链接期对上 |
| 异常栈清晰 | 出错可能是 **undefined behavior**（UB）：可能崩、可能跑出乱结果 | 不能"靠崩溃定位"，要靠纪律 + 工具（Part 7）|
| 值都是引用（除基本类型）| **值语义**默认：赋值 / 传参会**拷贝**整个对象 | "我改的是副本还是本体"要分清 |

> **一句话**：Java 帮你管内存和生命周期，C++ 把这份权力（和责任）还给你。端侧推理引擎为了性能恰恰大量用裸指针 / 手动内存，所以这块躲不掉——但你只要**会读会改**，不必从零写出一个引擎。

---

## 2. Kotlin / Java → C++ 速查对照表 ★★

读 C++ 代码时，把符号在脑子里换成你熟的东西。**这张表覆盖你读推理代码 90% 会遇到的语法**。

| 你熟的（Kotlin/Java）| C++ | 备注 |
|---|---|---|
| `String` | `std::string` / `const char*` | C 风格 API 常用 `const char*`（见 JNI）|
| `ArrayList<T>` | `std::vector<T>` | 端侧代码里到处是 `vector`（存 token、张量数据）|
| `HashMap<K,V>` | `std::unordered_map<K,V>` | |
| `T?` + `?.`（空安全）| `T*` + 手动 `if (p != nullptr)` | **没有空安全**，判空靠自觉 |
| `obj.method()` | `obj.method()` 或 `ptr->method()` | `.` 用于对象/引用，`->` 用于指针 |
| `package a.b` | `namespace a { namespace b { ... } }` | 访问用 `a::b::foo()` |
| `import` | `#include "xxx.h"` | 且要在编译/链接期找得到，不只是引用 |
| `val`（不可变）| `const` | `const` 在 C++ 里无处不在，读代码要会看 |
| `interface` / 抽象方法 | 纯虚类：`virtual void f() = 0;` | |
| `@Override` | `override`（放在函数签名后）| |
| try-with-resources / `use { }` | **RAII**：对象析构时自动释放（Part 3）| C++ 没有 `finally`，靠析构函数 |
| lambda `{ x -> ... }` | `[capture](auto x){ ... }` | `[ ]` 里写捕获，**捕引用要防悬垂** |
| `Long` 存个句柄 | `reinterpret_cast<jlong>(ptr)` | JNI 里把 native 指针塞进 Kotlin 的 `Long`（Part 5）|
| 泛型 `List<T>` | 模板 `template<class T>` | **会读就行**，自己一般不写复杂模板 |

`auto`、范围 for 也先认一下，现代 C++ 代码满屏都是：

```cpp
std::vector<int> tokens = tokenize(text);
for (auto t : tokens) {          // auto = 让编译器推类型，类似 Kotlin 的类型推断
    process(t);
}
auto* ctx = llama_init(...);     // auto* 推出来是个指针
```

---

## 3. 内存与所有权：C++ 的命根子 ★★★

**这一节是 Java 开发者读 / 改 C++ 最容易翻车的地方，也是端侧崩溃的头号来源。** 想透了，llama.cpp 里满屏的指针就不吓人了。

### 3.1 栈 vs 堆：对象住在哪

```cpp
void f() {
    std::string a = "hi";          // 栈上：出了 f() 自动销毁，不用你管
    std::string* b = new std::string("hi");  // 堆上：你 new 的，必须自己 delete
    delete b;                      // 忘了 delete = 内存泄漏
}
```

**Java 类比**：Java 里 `new` 出来的都在堆上、GC 收。C++ 里**栈对象自动销毁**（这点 Java 没有），**堆对象（`new`）要你自己 `delete`**。

### 3.2 RAII：C++ 版的 try-with-resources ★

C++ 没有 `finally`，但有更优雅的机制：**对象析构时自动跑清理代码**（析构函数 `~T()`）。把资源（内存 / 文件 / 模型句柄）包进一个对象，对象一出作用域就自动释放——这就是 **RAII**。

```cpp
class ModelHandle {
    llama_model* model;
public:
    ModelHandle(const char* path) { model = llama_load(path); }  // 构造时拿资源
    ~ModelHandle() { llama_free(model); }                        // 析构时自动还
};
// 用的时候：
{
    ModelHandle h("model.gguf");   // 加载
    ... 用 h ...
}                                  // 离开 {} 自动 llama_free，不会漏
```

**Kotlin 类比**：等价于 `use { }` / try-with-resources，只不过是**自动**触发、不用你写 `.use`。读代码看到一个类有 `~ClassName()`，那就是它的"清理逻辑"。

### 3.3 智能指针：现代 C++ 怎么管堆内存 ★

现代 C++ 尽量不用裸 `new/delete`，改用**智能指针**自动管：

| 智能指针 | 含义 | Java 类比 |
|---|---|---|
| `std::unique_ptr<T>` | **独占**所有权，出作用域自动 `delete` | 唯一持有者，类似 Rust 的 move |
| `std::shared_ptr<T>` | **共享**所有权，引用计数到 0 才释放 | 最接近 Java 引用（但要防循环引用）|
| `T*`（裸指针）| **不拥有**，只是"借看"，绝不 `delete` | 一个临时引用，但**没有空安全兜底** |

```cpp
std::unique_ptr<Engine> e = std::make_unique<Engine>();  // 自动管理，离开作用域自动释放
e->run();                                                // 用 -> 访问
```

> **读代码的关键判断**：看到 `unique_ptr`/`shared_ptr` → 内存自动管、放心。看到裸 `T*` → 要问"谁拥有它、谁负责 `delete`、它现在还活着吗"。**端侧推理引擎为了性能常用裸指针**，这正是你要小心的地方。

### 3.4 值语义：赋值会"拷贝"，不是"指同一个" ★

```cpp
std::vector<int> a = {1,2,3};
std::vector<int> b = a;   // ← C++ 默认【拷贝】整个 vector！b 和 a 是两份
b[0] = 99;                // 不影响 a
```

**Java 类比**：在 Java 里 `b = a` 是两个引用指向同一个对象；**C++ 默认是把整个对象复制一份**。大对象这样拷会慢，所以 C++ 引入了**引用 `&`** 和**移动语义** `std::move`：

```cpp
void process(const std::vector<int>& tokens);  // & = 传引用，不拷贝（const = 只读）
process(a);                                     // 高效，不复制

auto c = std::move(a);   // 移动：把 a 的内容"搬"给 c，之后 a 不该再用
```

> 读代码看到 `const T&` 就是"高效只读传参"（最常见）；看到 `std::move` 就是"所有权搬走了，原变量作废"。这两个认下来，函数签名基本都看得懂了。

---

## 4. 读懂一个推理引擎的 C++（以 llama.cpp 为例）★★

你不用会写推理引擎，但要会**读它的公共 API、按需改、正确调用**。好消息：推理引擎的对外 API 几乎都是 **C 风格**（不是花哨的现代 C++），比想象的好读。

### 4.1 推理引擎的通用骨架（先记这个流程）

不管 llama.cpp / MNN / ONNX Runtime，端侧跑一个 LLM 的流程都长一个样：

```
加载模型 → 建上下文(ctx) → 把文本切成 token → 循环{ 喂token→前向→采样出下一个token } → 把token拼回文本 → 释放
```

对应到 llama.cpp 的 C API（**示意，版本会变，以仓库头文件为准**）：

```cpp
llama_backend_init();                                  // ① 初始化后端
llama_model* model = llama_model_load_from_file(path, mparams);  // ② 加载模型权重
llama_context* ctx = llama_init_from_model(model, cparams);      // ③ 建推理上下文

std::vector<llama_token> tokens = tokenize(ctx, prompt);         // ④ 文本→token
for (...) {
    llama_decode(ctx, batch);                          // ⑤ 前向计算
    llama_token next = sample(ctx);                    // ⑥ 采样出下一个 token
    std::string piece = token_to_piece(ctx, next);     // ⑦ token→文本片段（流式输出）
    if (next == EOS) break;
}

llama_free(ctx);                                       // ⑧ 释放（顺序与创建相反）
llama_model_free(model);
llama_backend_free();
```

> **看懂这 8 步，你就看懂了所有端侧 LLM 引擎**。这跟主线 [第2篇](llm-api-and-tool-calling.md) 的 agentic loop 是同构的——只不过这次"调模型"发生在本机 native 层，而不是 HTTP。

### 4.2 读引擎代码的"三步速读法"

1. **找入口 API**：头文件（`llama.h`）里 `llama_*` 开头的公共函数 → 引擎对外提供什么能力。
2. **找数据结构**：`llama_model` / `llama_context` / `llama_batch` 这些 `struct` → 你要持有和传递的"句柄"。
3. **找生命周期**：每个 `*_init` / `*_load` 配对的 `*_free` → 谁创建、谁释放（回扣 Part 3）。

> ★ **强烈建议**：llama.cpp 仓库里有官方 Android 示例 `examples/llama.android`（Kotlin + JNI + CMake，能直接在真机跑）。**别从语法书学起，直接 clone 它跑通、再逐行读**——这是性价比最高的入门方式，也正好对接端侧轨道的里程碑 M2。

---

## 5. JNI：让 Android 调到 native 推理 ★★★

这是你"把端侧能力接进 App"的**桥**，也是端侧岗最能体现你 Android 价值的地方——纯算法的人写不利索 JNI，你能。

### 5.1 JNI 是什么

JNI（Java Native Interface）= Java/Kotlin 代码 ↔ C/C++ 代码互相调用的标准桥。Kotlin 声明一个 `external` 方法，native 侧用一个**特定命名的 C 函数**实现它。

```kotlin
// Kotlin 侧
class LlamaBridge {
    external fun loadModel(path: String): Long      // 返回 native 指针(当 handle)
    external fun completion(handle: Long, prompt: String): String
    companion object { init { System.loadLibrary("llama-android") } }  // 加载 .so
}
```

```cpp
// C++ 侧：函数名 = Java_<包名下划线>_<类名>_<方法名>
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_LlamaBridge_loadModel(JNIEnv* env, jobject thiz, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);  // jstring → C 字符串
    llama_model* model = llama_model_load_from_file(p, ...);
    env->ReleaseStringUTFChars(path, p);                    // ★ 必须释放，否则泄漏
    return reinterpret_cast<jlong>(model);                  // 指针塞进 jlong 返回
}
```

### 5.2 必须记住的几条 JNI 纪律 ★

这些是 JNI 最容易出 bug 的点，**逐条对号**：

| 规则 | 说明 | 不遵守的后果 |
|---|---|---|
| `GetStringUTFChars` 后必须 `ReleaseStringUTFChars` | 取出的 C 字符串要还 | 内存泄漏 |
| native 指针用 `jlong` 当 **handle** 传回 Kotlin | Kotlin 持 `Long`，下次调用再传回来 `reinterpret_cast` 还原 | —（标准模式）|
| **不能跨线程缓存 `JNIEnv*`** | `JNIEnv*` 是每线程的 | 崩溃 |
| native 线程要回调 JVM，先 `AttachCurrentThread` | 比如流式推理在工作线程往 Kotlin 推 token | 崩溃 |
| 要长期持有 `jobject`（如回调对象），用 `NewGlobalRef` | 局部引用方法返回就失效 | 悬垂崩溃 |
| 数组用 `GetByteArrayElements` 后 `Release...` | 同字符串 | 泄漏 / 数据不同步 |

### 5.3 流式输出怎么回传（端侧 LLM 的高频需求）

LLM 是一个 token 一个 token 出的（回扣主线流式概念）。native 侧每生成一个 token，要回调 Kotlin 刷 UI。常见两种做法：

- **回调法**：Kotlin 传一个回调对象，native 用 `GetMethodID` + `CallVoidMethod` 每个 token 调一次（注意 `NewGlobalRef` + 线程 attach）。
- **轮询法**：native 把 token 推进一个线程安全队列，Kotlin 侧协程循环取——更简单、更好控，**推荐起步用这个**。

> **Android 类比**：这跟你用 `OkHttp` 的 SSE 流、或 native 回调（如播放器的进度回调）刷 UI 是同一套思路——native 产生事件、切回主线程更新。你做过，只是这次事件源是本机模型。

---

## 6. NDK / CMake：把 native 编进 APK

把 C++ 代码和第三方引擎（llama.cpp）编成 `.so` 打进 APK，靠 **NDK + CMake**。

```cmake
# CMakeLists.txt
add_library(llama-android SHARED native-lib.cpp)   # 编出 libllama-android.so
find_library(log-lib log)                          # Android 的日志库
target_link_libraries(llama-android llama ${log-lib})  # 链上 llama.cpp 和 log
```

```kotlin
// build.gradle(.kts)
android {
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }  // 真机基本只需 arm64-v8a
    }
}
```

要会看 / 会改的点：

- **`abiFilters`**：手机基本都是 `arm64-v8a`，限定它能大幅减小包体、加快编译。
- **`SHARED`（.so）vs `STATIC`（.a）**：动态库 vs 静态库，类似"运行时加载的 aar" vs "编进去的 jar"。
- **`System.loadLibrary("llama-android")`**：运行时加载你的 `.so`，对应 `add_library` 的名字。

> 这块对你不算全新——你配过 Gradle、可能碰过 NDK 依赖。把它当成"native 版的依赖与构建配置"即可。

---

## 7. 调试与排错：C++ 崩溃不像 Java 那样友好 ★

Java 崩了给你干净的异常栈；C++ 崩了可能是一行 `SIGSEGV`，甚至跑出错误结果也不崩（UB）。所以要靠工具：

| 工具 | 干什么 | 类比 |
|---|---|---|
| `logcat` + tombstone | 看 native crash（`SIGSEGV` 等）信号和地址 | 类似看 ANR/crash 日志 |
| `ndk-stack` | 把崩溃地址**符号化**成"哪个文件第几行" | 类似 ProGuard 反混淆栈 |
| **AddressSanitizer (ASan)** | 抓 use-after-free / 越界 / 泄漏，**端侧必备** | 类似一个超强的运行时检查器 |
| Android Studio LLDB | 断点调 native 代码 | 类似 Java 调试器 |
| `__android_log_print` | native 侧打日志 | 类似 `Log.d` |

> 新手最常踩的三个坑：**①** 忘了 `Release*Chars` / `delete`（泄漏）；**②** 用了已释放的指针（use-after-free，ASan 能抓）；**③** ABI 不匹配（`UnsatisfiedLinkError`，多半是 `abiFilters` 或函数名拼错）。遇到 `UnsatisfiedLinkError` 先核对：`.so` 名、JNI 函数名（包名/类名/方法名逐段对）、ABI。

---

## 8. 学习路线（边做边补，配合端侧轨道）★

**别按教科书顺序从头学 C++**，按"能跑、能用"的里程碑推进，和 [端侧轨道](on-device-ai-track.md) 的 M2 并行。

### C0 · 能读（约 1–2 周）
- 过一遍 Part 1–3：内存模型、指针/引用、RAII、智能指针、值语义。
- 验收：能读懂 `llama.h` 公共 API、说清每个句柄谁创建谁释放。

### C1 · 能跑（约 1 周）
- clone llama.cpp 的 `examples/llama.android`，真机跑通。
- 验收：飞行模式下 App 里出第一个 token（与端侧轨道 M0/M2 重合）。

### C2 · 能改（约 2–3 周）
- 在 JNI 层加一个自己的 native 方法：传入采样参数、把 token **流式**回传 Kotlin（Part 5.3）。
- 验收：能改 native 行为并在 App 里看到效果，会用 ndk-stack/ASan 定位一次崩溃。

### C3 · 能集成（持续）
- 把推理封装成一个 Kotlin 友好的模块（隐藏 JNI 细节），接进一个真实功能。
- 验收：对齐端侧轨道 M4——产品里落地端侧 / 端云协同功能。

> 期间随时查 [cppreference](https://en.cppreference.com/) 即可，**不必通读 C++ 教程**。以"读懂手上这段推理代码"为驱动，比系统学语法高效得多。

---

## 9. 明确"不用学"的（守住边界，别陷进去）★

C++ 的深坑很多，转端侧**初期一律先跳过**，需要时再回头：

- **模板元编程 / SFINAE / concepts**：读到了能跳过，自己别写。
- **自己实现推理 kernel / 写算子**：那是"推理引擎型"深水区的事，入门不碰。
- **C++20 协程 / ranges、复杂运算符重载、多重继承**：用到再说。
- **手写复杂模板库、Boost 全家桶**：不需要。
- **跨平台构建系统全套**：会用项目现成的 CMake 即可。

> **守住目标**：你要的是"能读懂、能改、能把 native 推理接进 App"，不是"成为 C++ 专家"。把精力花在 Part 3（内存）和 Part 5（JNI）上，回报最高。

---

## 参考链接 / 延伸阅读

> 链接随版本变动，**失效时按标题搜索**。优先看官方文档和能直接跑的示例工程。

**C++ 语言（按需查，不必通读）**
- cppreference（最权威的语法/库速查）：https://en.cppreference.com/
- learncpp.com（想系统补时用，模块化）：https://www.learncpp.com/

**Android NDK / JNI（官方，必看）**
- NDK 指南：https://developer.android.com/ndk/guides
- JNI 提示（Android 官方踩坑大全，强烈推荐）：https://developer.android.com/training/articles/perf-jni
- NDK 官方示例：https://github.com/android/ndk-samples

**端侧推理（直接对接实践）**
- llama.cpp（含 Android 示例 `examples/llama.android`）：https://github.com/ggml-org/llama.cpp
- MNN（阿里，C++ 推理引擎 + Android Demo）：https://github.com/alibaba/MNN
- ONNX Runtime（C/C++ API + Mobile）：https://onnxruntime.ai/

**调试**
- AddressSanitizer on Android：https://developer.android.com/ndk/guides/asan
- ndk-stack（崩溃符号化）：https://developer.android.com/ndk/guides/ndk-stack

---

## 下一步建议

最有效的启动方式和端侧轨道一致：**别先啃 C++ 语法书，直奔 C1**。

clone llama.cpp 的 `examples/llama.android`，真机跑通后，对着它的 JNI 层（`.cpp`）+ Kotlin 层逐行读——**带着 Part 3（内存）和 Part 5（JNI）的认知去读真实代码**，比任何教程都快。读顺了再做 C2（改 JNI、流式回传），你就具备了端侧岗 JD 里那句"精通 C++、熟悉 Android/Linux、有端侧部署经验"的真实底气。

> **一句话收尾**：C++ 是你转端侧绕不开的门槛，但门槛不高——你缺的不是"重学一门语言"，而是"补上内存所有权和 JNI 这两块"，然后用真实推理代码把它练熟。
