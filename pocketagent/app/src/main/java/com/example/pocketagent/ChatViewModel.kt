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
        output = ""
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                llama.complete(formatPrompt(userText)) { piece ->
                    // native 在 IO 线程回调；切主线程更新 UI 状态
                    viewModelScope.launch(Dispatchers.Main) { output += piece }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { output += "\n[错误] ${e.message}" }
            }
            withContext(Dispatchers.Main) { running = false }
        }
    }

    /**
     * chat 模板是【模型相关】的——用错会输出乱码/不停（见推理篇 Part 8）。
     * 下面是 Qwen2.5 / ChatML 风格示例；换模型时改成它要求的模板。
     */
    private fun formatPrompt(user: String): String =
        "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    override fun onCleared() {
        llama.close()
    }
}
