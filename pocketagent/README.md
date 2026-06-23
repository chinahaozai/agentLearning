# PocketAgent — M0 脚手架（端云协同 Android Agent）

这是 [端云协同 Android Agent 收官蓝图](../docs/04-edge-cloud-agent-capstone.md) 的 **M0 起点工程**：一个能在 Android Studio 打开、接好了 **CMake + JNI + llama.cpp + 流式桥接** 的最小骨架。目标只有一个——**真机离线蹦出第一个 token**。

> ⚠️ 诚实说明：这份脚手架是**在文档仓库里生成的代码骨架，未经编译/真机验证**。构建和"第一个 token"需要你在本机 Android Studio + 真机完成。我把 Android 开发最不熟的那部分（native 接线）写好了，但 **llama.cpp 的 C API 会随版本变**，`native-lib.cpp` 可能需要按你 clone 的版本微调（文件头有说明，最权威参照是 llama.cpp 自带的 `examples/llama.android`）。

---

## 中国网络（已配镜像源）

本工程已把两层下载都换成国内镜像，避免 `services.gradle.org` / `dl.google.com` 超时：

- `gradle/wrapper/gradle-wrapper.properties` → **腾讯云**镜像下载 Gradle 发行版
- `settings.gradle.kts` → **阿里云**镜像优先解析 AGP / Kotlin / Compose / AndroidX 依赖

改完在 Android Studio 点 **File → Sync Project with Gradle Files** 重新同步即可。

> - Gradle 镜像备选（腾讯云不稳时）：`https://mirrors.huaweicloud.com/gradle/gradle-8.9-bin.zip`
> - 想让**所有**项目都走镜像（一劳永逸）：在 `~/.gradle/init.gradle` 里配全局仓库镜像，而不是每个项目改。
> - llama.cpp 从 GitHub clone 若慢：用国内加速镜像，或科学上网。

---

## 已经接好的部分

- Gradle（Kotlin DSL）+ Compose + NDK（`arm64-v8a`）配置
- `CMakeLists.txt`：`add_subdirectory` 链接 llama.cpp，编出 `libpocketagent.so`
- `native-lib.cpp`：JNI 实现 `加载 / 流式生成 / 释放`（JNI plumbing 完整；llama.cpp 调用按新版写、已标注对照点）
- `LlamaBridge.kt`：`external` 声明 + handle 管理 + 流式回调接口
- `ChatViewModel.kt`：IO 线程跑推理、token 切主线程更新 UI
- `MainActivity.kt`：极简 Compose UI（加载模型 / 输入 / 发送 / 看流式输出）

## 你要补的部分（3 步）

### 1. 放 llama.cpp 源码
在本目录（`pocketagent/`）下获取 llama.cpp（CMake 期望它在 `pocketagent/llama.cpp`）：

```bash
cd pocketagent
git clone https://github.com/ggml-org/llama.cpp
# 或作为 submodule： git submodule add https://github.com/ggml-org/llama.cpp
```

### 2. 准备一个 GGUF 模型
用 **Qwen2.5-1.5B-Instruct-GGUF**、`Q4_K_M`（约 1.1GB，对应推理篇 Part 3）。国内从 ModelScope（魔搭）下：
`https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF` → Files 标签 → 下名字带 `q4_k_m` 的 `.gguf`。

> 脚手架的 `ChatViewModel` 用 **ChatML** 模板（Qwen2.5 / Qwen3 通用）。Qwen2.5 无思考模式，直接用；若以后改 Qwen3 想关思考，在 `formatPrompt()` 用户内容后加 ` /no_think`。换别家模型要改模板（推理篇 Part 8）。

### 3. 把模型推到设备，构建运行
先在 Android Studio 里 **Open** 本 `pocketagent/` 目录，让它同步（会自动补 Gradle wrapper）。装到真机跑一次（让 App 创建外部目录），然后：

```bash
# 把模型推到 App 的外部私有目录（首次运行 App 后该目录才存在）
# 文件名换成你实际下到的那个 .gguf
adb push qwen2.5-1.5b-instruct-q4_k_m.gguf \
  /sdcard/Android/data/com.example.pocketagent/files/model.gguf
```

回到 App：点 **加载模型** → 等状态变"已加载" → 输入一句话 → **发送**。

✅ **验收（M0 完成标志）**：**打开飞行模式**再发送，仍能逐字蹦出 token = 推理真的在本机发生。

---

## 常见问题

- **`找不到 llama.cpp`**：第 1 步没做，或没 clone 到 `pocketagent/llama.cpp`。
- **CMake 里 `LLAMA_BUILD_*` 报未知选项**：llama.cpp 该版本改用了别的开关名（可能 `GGML_*`），去它的 `CMakeLists.txt` 查实际名字改一下。
- **`native-lib.cpp` 编译报错（找不到 `llama_xxx`）**：API 版本差异，对照 `llama.cpp/examples/llama.android/.../llama-android.cpp` 调整那几行调用（JNI 部分不用动）。
- **`UnsatisfiedLinkError`**：`.so` 没打进 / ABI 不符 / JNI 函数名拼错——核对包名 `com.example.pocketagent`、ABI `arm64-v8a`（C++ 篇 Part 7）。
- **首次构建很慢**：在编译整个 llama.cpp，正常。
- **OOM / 很慢**：模型太大或 `n_ctx` 太大，换更小模型 / 调小 `nCtx`（推理篇 Part 4.3 / Part 2.3）。

## 这之后（对应收官蓝图里程碑）

M0（本脚手架）→ **M1** 端侧 agent loop + 约束解码工具调用（[端侧 Agent/RAG 篇](../docs/07-on-device-agent-rag.md)）→ **M2** 设备指标 trace（[profiling 篇](../docs/06-on-device-profiling.md)）→ **M3** 端云路由（[路由篇](../docs/08-on-device-edge-cloud-routing.md)）→ **M4** 本地 RAG → **M5** 多配置 eval 对比。

> 版本说明：Gradle/AGP/Kotlin/Compose 版本为撰写时的稳定组合，用新版 Android Studio 打开若提示升级，按提示升即可。
