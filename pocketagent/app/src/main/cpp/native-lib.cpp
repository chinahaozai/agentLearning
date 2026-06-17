// ============================================================================
//  PocketAgent · JNI 桥（Kotlin LlamaBridge ↔ llama.cpp）
//
//  ⚠️ 重要：llama.cpp 的 C API 会随版本变化。本文件按【较新版本】(2024 下半年起
//     的 llama_sampler / llama_model_load_from_file 风格) 写成，函数名/签名可能和你
//     clone 的那版对不上。最权威的参照是 llama.cpp 仓库自带的：
//        examples/llama.android/llama/src/main/cpp/llama-android.cpp
//     编译报错时，对照它调整下面【调用 llama_* 的那几行】即可。
//
//  下面的【JNI plumbing】(字符串转换 / handle / 回调) 是稳定的，一般不用动。
//  对应文档：《C++ 补齐计划》Part 5、《端侧推理实战》Part 4。
// ============================================================================
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "pocketagent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 一次会话持有的 native 资源（RAII 思路：在 nativeFree 里成对释放）
struct LlamaSession {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
};

// ── 加载模型 ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_pocketagent_LlamaBridge_nativeLoadModel(
        JNIEnv* env, jobject /*thiz*/, jstring jpath, jint nCtx, jint nThreads) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);   // jstring -> C 串

    llama_backend_init();

    // —— 调用 llama.cpp：按你的版本核对函数名 ——
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // M0 全 CPU；要 GPU offload 再调大（见推理篇 Part 5）
    llama_model* model = llama_model_load_from_file(path, mparams);

    env->ReleaseStringUTFChars(jpath, path);                      // ★ 必须释放
    if (model == nullptr) { LOGE("load model failed"); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) nCtx;                    // 上下文长度，吃 KV cache 内存
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) { LOGE("init ctx failed"); llama_model_free(model); return 0; }

    auto* s = new LlamaSession{model, ctx};
    LOGI("model loaded, n_ctx=%d threads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(s);                            // native 指针 -> jlong handle
}

// ── 流式生成 ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketagent_LlamaBridge_nativeCompletion(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jprompt, jint maxTokens, jobject cb) {

    auto* s = reinterpret_cast<LlamaSession*>(handle);
    if (s == nullptr) { LOGE("null session"); return; }

    const llama_vocab* vocab = llama_model_get_vocab(s->model);

    const char* c_prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(c_prompt);
    env->ReleaseStringUTFChars(jprompt, c_prompt);

    // —— 1) tokenize（两次调用：先问长度，再填充）——
    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                                   nullptr, 0, /*add_special*/ true, /*parse_special*/ true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                       tokens.data(), (int)tokens.size(), true, true) < 0) {
        LOGE("tokenize failed"); return;
    }

    // —— 2) 准备回调 onToken(String) —— (JNI plumbing，稳定)
    jclass    cbClass = env->GetObjectClass(cb);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    // —— 3) sampler：M0 先用贪心；要更自然的输出再加 top-k/top-p/temp（推理篇 Part 4.2）——
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // —— 4) decode 循环：prefill 整个 prompt，然后逐 token 生成 ——
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());

    for (int generated = 0; generated < maxTokens; ++generated) {
        if (llama_decode(s->ctx, batch) != 0) { LOGE("decode failed"); break; }

        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;                 // 生成结束

        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, /*special*/ true);
        if (n > 0) {
            jstring piece = env->NewStringUTF(std::string(buf, n).c_str());
            env->CallVoidMethod(cb, onToken, piece);              // 回调给 Kotlin
            env->DeleteLocalRef(piece);                            // 防 local ref 表溢出
        }

        batch = llama_batch_get_one(&id, 1);                       // 下一步只喂刚生成的 token
    }

    llama_sampler_free(smpl);
}

// ── 释放 ────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketagent_LlamaBridge_nativeFree(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {

    auto* s = reinterpret_cast<LlamaSession*>(handle);
    if (s == nullptr) return;
    if (s->ctx)   llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
    // 注：llama_backend_free() 通常在整个 App 退出时调一次，这里不调。
}
