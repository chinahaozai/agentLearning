package com.example.pocketagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

/**
 * M0 UI：加载模型 → 输入 → 发送 → 看流式输出。
 * 极简，目的是验证"端侧出第一个 token"。后续里程碑再加路由/trace/RAG。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 模型路径：App 外部私有目录下的 model.gguf（见 README 的 adb push 步骤）
        val modelFile = File(getExternalFilesDir(null), "model.gguf")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(modelFile)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun ChatScreen(modelFile: File, vm: ChatViewModel = viewModel()) {
    var input by rememberSaveable { mutableStateOf("你好，用一句话介绍你自己") }
    val scroll = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "PocketAgent · M0", style = MaterialTheme.typography.titleLarge)
        Text(text = vm.status, style = MaterialTheme.typography.bodySmall)

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { vm.loadModel(modelFile) }, enabled = !vm.modelLoaded && !vm.running) {
                Text("加载模型")
            }
            Button(onClick = { vm.send(input) }, enabled = vm.modelLoaded && !vm.running) {
                Text(if (vm.running) "生成中…" else "发送")
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("输入") },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = vm.output.ifEmpty { "（输出会显示在这里。建议开飞行模式验证是真·端侧）" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .verticalScroll(scroll),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
