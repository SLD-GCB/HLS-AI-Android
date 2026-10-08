package dev.ffmpegkit.llama

/**
 * Internal bridge to the native libraries. Not part of the public API — use [Llama].
 * Loads ggml (+ split backends) → llama → llama_jni. Missing split libs are ignored
 * (some llama.cpp builds ship a single libggml.so, others split it).
 */
internal object LlamaJNI {

    init {
        // ggml-opencl 是可选的 GPU 后端（没编 OpenCL 时这个 .so 不存在；
        // libggml.so 其实也会 DT_NEEDED 它、自动带起）。
        for (lib in listOf("ggml-base", "ggml-cpu", "ggml-opencl", "ggml", "llama")) {
            runCatching { System.loadLibrary(lib) }
        }
        System.loadLibrary("llama_jni")
    }

    /** @return native handle, or 0 on failure. */
    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int, gpuLayers: Int): Long

    /** @return JSON: `{"text":..,"tokensGenerated":..,"tokensPerSecond":..,"promptEvalTimeMs":..,"generateTimeMs":..}`. */
    external fun nativeComplete(
        handle: Long, prompt: String, systemPrompt: String, maxTokens: Int,
        temp: Float, topP: Float, topK: Int, seed: Int,
    ): String

    /**
     * 鸿胪寺扩展一：渲染 —— roles/contents + 预填 → 模型真正吃掉的那一整串。
     * 模板不吃 system 时会**并进第一条 user**再渲染（安装补偿）。
     */
    external fun nativeRender(
        handle: Long,
        roles: Array<String>, contents: Array<String>, prefill: String,
    ): String

    /**
     * 鸿胪寺扩展二：生成（prompt 已是渲染好的整串）+ 停止串 + 禁吐 + 采样惩罚 + 流式回调。
     *
     * @param prompt 已渲染好的提示词（由 [nativeRender] 产出，含预填）
     * @param stops 生成中检测的停止串
     * @param bans 文字层禁吐（生成里压掉这些短语）
     * @param repeatPenalty 重复惩罚，1.0 = 关
     * @param penaltyLastN 惩罚回看多少 token（<=0 用默认 64）
     * @param presencePenalty / frequencyPenalty 出现/频率惩罚，0 = 关
     * @param sink 每段增量回调；`onPiece` 返回 false 立即打断
     * @return JSON：`{"text":..,"tokensGenerated":..,"cancelled":..,..}`
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        stops: Array<String>, bans: Array<String>,
        maxTokens: Int, temp: Float, topP: Float, topK: Int, seed: Int,
        repeatPenalty: Float, penaltyLastN: Int, presencePenalty: Float, frequencyPenalty: Float,
        sink: Any,
    ): String

    /** 设置进程环境变量（`setenv`）。给 OpenCL 的 ICD loader 指路（`OPENCL_VENDOR_PATH`）用。 */
    external fun nativeSetEnv(key: String, value: String)

    /** 读模型 GGUF 元数据（如 `general.architecture`）。取不到回空串。 */
    external fun nativeModelMeta(handle: Long, key: String): String

    external fun nativeEmbed(handle: Long, text: String): FloatArray

    external fun nativeReleaseModel(handle: Long)

    external fun nativeGetSystemInfo(): String
}
