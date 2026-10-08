package dev.ffmpegkit.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 鸿胪寺对 `llama-android` 绑定的**增量扩展**（绑定原文件基本没动，只加了一个
 * native 方法 + 这一层 Kotlin 封装）。
 *
 * 绑定原版的 [Llama.complete] 只能「一个 system + 一个 user、整段返回、无停止词」。
 * 这一层补上 PC 版控制协议真正需要的能力：
 *
 *   · **多轮 messages** —— 走模型自带 `llama_chat_apply_template`，不再是压平的转录
 *   · **预填** —— 模板渲染完之后接字（真正的续写起点，模型不会从头"嗯让我想想"）
 *   · **停止串** —— 生成中检测（保留尾缓冲防跨 token），省 token
 *   · **禁吐** —— 文字层自定义采样器，把指定短语从生成里掐掉
 *   · **流式** —— 每段增量经 [令牌槽] 回调，可随时打断
 */
fun interface 令牌槽 {
    /** @return false 表示立刻打断。 */
    fun onPiece(段: String): Boolean
}

/** 一条对话消息。`role` 用 `system` / `user` / `assistant`。 */
data class 聊天消息(val role: String, val content: String)

/** 交给生成层的参数（对齐 PC 版控制协议里那部分）。 */
data class 生成选项(
    val 预填: String = "",
    val 停止: List<String> = emptyList(),
    val 禁吐: List<String> = emptyList(),
    val 上限: Int = 512,
    val 温度: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val 种子: Int = -1,
    /** 重复惩罚：压复读/绕圈。1.0 = 关。小模型角色扮演建议 1.1~1.3。 */
    val 重复惩罚: Float = 1.1f,
    /** 惩罚回看多少 token（<=0 = 用默认 64）。 */
    val 惩罚窗口: Int = 0,
    /** 出现惩罚 / 频率惩罚：0 = 关。 */
    val 出现惩罚: Float = 0f,
    val 频率惩罚: Float = 0f,
)

object 本地对话 {

    /**
     * 渲染：多轮消息 + 预填 → 模型真正吃掉的那一整串提示词。
     * （模板渲染 + 「system 装不进去就并进第一条 user」的安装补偿。）
     *
     * 和生成**分开**是为了对齐 PC 版：Kotlin 要先拿到这串，才能判「思维位有没有留开」
     * 来决定开不开分流（`该分流`）。
     */
    suspend fun 渲染(
        模: LlamaModel,
        消息们: List<聊天消息>,
        预填: String = "",
    ): String = withContext(Dispatchers.Default) {
        LlamaJNI.nativeRender(
            模.requireHandle(),
            Array(消息们.size) { 消息们[it].role },
            Array(消息们.size) { 消息们[it].content },
            预填,
        )
    }

    /** 设一个进程环境变量。给 OpenCL 的 ICD loader 指路用；要在第一次带 GPU 载入前调。 */
    fun 设环境变量(键: String, 值: String) = LlamaJNI.nativeSetEnv(键, 值)

    /** 读模型 GGUF 元数据（如 `general.architecture`）——认族要用真值，不能只看文件名。 */
    suspend fun 元数据(模: LlamaModel, 键: String): String = withContext(Dispatchers.Default) {
        LlamaJNI.nativeModelMeta(模.requireHandle(), 键)
    }

    /**
     * 多轮流式生成（提示词已渲染好）。**必须在后台线程调**（内部已切 [Dispatchers.Default]）。
     *
     * @return 整段结果的统计；`text` 是**生成正文**，不含预填 —— 预填是提示词的一部分、
     *         不在生成结果里。要不要把预填拼回去（PC 版叫"回吐"）由调用方决定。
     */
    suspend fun 流式(
        模: LlamaModel,
        提示词: String,
        选项: 生成选项,
        槽: 令牌槽,
    ): LlamaResult = withContext(Dispatchers.Default) {
        val json = LlamaJNI.nativeGenerate(
            模.requireHandle(), 提示词,
            选项.停止.toTypedArray(), 选项.禁吐.toTypedArray(),
            选项.上限, 选项.温度, 选项.topP, 选项.topK, 选项.种子,
            选项.重复惩罚, 选项.惩罚窗口, 选项.出现惩罚, 选项.频率惩罚,
            槽,
        )
        解析(json)
    }

    private fun 解析(json: String): LlamaResult {
        val o = JSONObject(json)
        if (o.has("error")) throw LlamaException.InferenceFailed(o.optString("error"))
        return LlamaResult(
            text = o.optString("text"),
            tokensGenerated = o.optInt("tokensGenerated"),
            tokensPerSecond = o.optDouble("tokensPerSecond", 0.0).toFloat(),
            promptEvalTimeMs = o.optLong("promptEvalTimeMs"),
            generateTimeMs = o.optLong("generateTimeMs"),
        )
    }
}
