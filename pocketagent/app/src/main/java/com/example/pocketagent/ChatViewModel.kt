package com.example.pocketagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * M0 的最小逻辑：加载模型 → 发 prompt → 流式收 token。
 * 推理全部在 IO 线程，token 回到主线程更新 Compose 状态。
 */
class ChatViewModel : ViewModel() {

    private val llama = LlamaBridge()

    var status by mutableStateOf("未加载模型")
        private set
    var output by mutableStateOf("")
        private set

    // 累积的【原始】输出（含 <think>）；显示时再过滤，见 stripThinking()
    private var rawOutput = ""
    var modelLoaded by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set

    /** 模型默认放在 App 外部私有目录：/sdcard/Android/data/<包名>/files/model.gguf（README 有 adb push 命令）。 */
    fun loadModel(modelFile: File) {
        if (modelLoaded || running) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { status = "加载中…" }
            val ok = runCatching {
                require(modelFile.exists()) { "找不到模型文件：${modelFile.absolutePath}" }
                llama.load(modelFile.absolutePath)
            }
            withContext(Dispatchers.Main) {
                modelLoaded = ok.isSuccess
                status = if (ok.isSuccess) "已加载：${modelFile.name}"
                         else "加载失败：${ok.exceptionOrNull()?.message}"
            }
        }
    }

    fun send(userText: String) {
        if (!modelLoaded || running || userText.isBlank()) return
        running = true
        rawOutput = ""
        output = ""
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                llama.complete(formatPrompt(userText)) { piece ->
                    // native 在 IO 线程回调；切主线程更新 UI（过滤掉 <think> 思考块）
                    viewModelScope.launch(Dispatchers.Main) {
                        rawOutput += piece
                        output = stripThinking(rawOutput)
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { output += "\n[错误] ${e.message}" }
            }
            withContext(Dispatchers.Main) { running = false }
        }
    }

    /**
     * ChatML 模板，Qwen2.5 / Qwen3 通用。
     * 注：Qwen2.5 无思考模式，直接用即可；若改用 Qwen3 想关思考，在用户内容后加 " /no_think"。
     * 换别家模型时改成它要求的模板（见推理篇 Part 8 / Agent 篇）。
     */
    private fun formatPrompt(user: String): String =
        "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    /** 去掉思考块 <think>…</think>（Qwen3 才有；Qwen2.5 无此块，本函数对它是 no-op，留着不碍事）。 */
    private fun stripThinking(s: String): String {
        var r = Regex("(?s)<think>.*?</think>").replace(s, "")
        val open = r.indexOf("<think>")
        if (open >= 0) r = r.substring(0, open)
        return r.trimStart()
    }

    override fun onCleared() {
        llama.close()
    }
}
