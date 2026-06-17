package com.example.pocketagent

/**
 * 端侧推理的 JNI 桥：Kotlin ↔ native(llama.cpp)。
 *
 * 设计要点（对应《C++ 补齐计划》Part 5）：
 *  - native 指针用 [handle]（Long）持有，跨调用传回 native 还原，Kotlin 侧不碰裸指针。
 *  - 流式输出走 [TokenCallback]：native 每生成一个 token 回调一次。
 *  - 所有 native 方法都在 [ChatViewModel] 的 IO 线程上调用（别在主线程跑推理）。
 */
class LlamaBridge {

    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    /** 加载 GGUF 模型；失败抛异常。nCtx 越大越吃内存（KV cache，见推理篇 Part 4.3）。 */
    fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4) {
        check(handle == 0L) { "模型已加载，先 close()" }
        handle = nativeLoadModel(modelPath, nCtx, nThreads)
        require(handle != 0L) { "模型加载失败：$modelPath" }
    }

    /** 流式生成。onToken 在调用线程（native）上回调——更新 UI 记得切主线程。 */
    fun complete(prompt: String, maxTokens: Int = 256, onToken: (String) -> Unit) {
        check(handle != 0L) { "模型未加载" }
        nativeCompletion(handle, prompt, maxTokens, object : TokenCallback {
            override fun onToken(piece: String) = onToken(piece)
        })
    }

    fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    /** native 回调接口：JNI 侧用 GetMethodID("onToken") 调它。 */
    interface TokenCallback {
        fun onToken(piece: String)
    }

    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeCompletion(handle: Long, prompt: String, maxTokens: Int, cb: TokenCallback)
    private external fun nativeFree(handle: Long)

    companion object {
        init {
            // 对应 CMake 里 add_library(pocketagent SHARED ...) 的名字
            System.loadLibrary("pocketagent")
        }
    }
}
