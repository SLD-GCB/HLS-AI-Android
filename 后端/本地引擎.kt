package 后端

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import dev.ffmpegkit.llama.令牌槽
import dev.ffmpegkit.llama.本地对话
import dev.ffmpegkit.llama.聊天消息
import dev.ffmpegkit.llama.生成选项
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 本地模型引擎 —— **成熟绑定 `llama-android`（`模型层/`）+ 我们加的一层控制协议**。
 *
 * 模型框架是绑定的（llama.cpp 官方 C API 的封装），我们只写「胶水 + 协议」：
 *
 *   取控制 → 锁人格 → 组装多轮 messages → 停止词/禁吐 → native 多轮渲染 + 预填
 *          → 流式 → 分流器剥思维链 → 吐给界面
 *
 * ⚠ 跟旧版的区别：不再把对话压成「用户：/角色：」一段，而是把**真正的多轮数组**
 *   交给 native 用模型自带模板渲染（`llama_chat_apply_template`），质量对齐 PC 版。
 */
object 本地引擎 {

    private var 当前模型: LlamaModel? = null
    private var 当前路径: String = ""
    /** 当前模型的 `general.architecture`（认族用真值，对齐 PC；拿不到才退回看文件名）。 */
    private var 当前架构: String = ""

    fun 支持(): Boolean = true

    fun 状态(): String = "本地引擎：llama-android 绑定 + 控制协议（多轮/预填/停止/禁吐/分流）"

    private suspend fun 确保载入(径: String, 配置: LlamaConfig): LlamaModel {
        val 有 = 当前模型
        if (有 != null && 当前路径 == 径 && 有.isLoaded) return 有
        释放()
        val 模 = Llama.loadModel(径, 配置)
        当前模型 = 模
        当前路径 = 径
        当前架构 = try { 本地对话.元数据(模, "general.architecture") } catch (_: Throwable) { "" }
        return 模
    }

    @Synchronized
    fun 释放() {
        val 模 = 当前模型 ?: return
        try { Llama.releaseModel(模) } catch (_: Throwable) { }
        当前模型 = null
        当前路径 = ""
        当前架构 = ""
    }

    private var ocl已备 = false

    /**
     * 让打包进来的 OpenCL ICD loader 找到设备厂商的驱动。
     *
     * Android 上 `libOpenCL.so` **不是公开 NDK 库**，应用不能直接链它；所以我们自己编了
     * 一份 Khronos ICD loader 打进包 —— 找不到驱动时它只会报「没有平台」，
     * llama.cpp 就退回 CPU，**不会崩**。
     *
     * ICD loader 默认只在 `/system/vendor/Khronos/OpenCL/vendors/` 里那些 .icd 文件里
     * 找驱动，那儿通常没有。所以我们在 App 私有目录写几份 `.icd`（每份指向一个可能的高通
     * 驱动路径），再把 `OPENCL_VENDOR_PATH` 指过去。路径猜不中也无妨 —— loader 会跳过
     * 加载失败的那几份。
     *
     * ⚠ 必须在**第一次 OpenCL 调用之前**做，所以只在第一次带 GPU 载入时跑一次；
     *    改了 GPU 开关要退出重进 App。
     */
    private fun 准备OpenCL() {
        if (ocl已备) return
        ocl已备 = true
        try {
            val ctx = 应用容器.应用上下文 ?: return
            val 目录 = File(ctx.filesDir, "opencl_vendors").apply { mkdirs() }
            目录.listFiles()?.forEach { it.delete() }
            候选.forEachIndexed { i, p -> File(目录, "honglusi$i.icd").writeText(p + "\n") }
            本地对话.设环境变量("OPENCL_VENDOR_PATH", 目录.absolutePath)
        } catch (_: Throwable) { }
    }

    /** 高通设备上 Adreno 的 OpenCL 实现可能在这几个路径（64 位优先）。 */
    private val 候选 = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/odm/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
    )

    /** 跑一次本地生成（流式）。形状仍是 `Flow<String>`，聊天链路不用改。 */
    fun 流式(
        库: TavernStore,
        套: 接口配置,
        系统: String,
        消息们: List<聊天条>,
        设定: JsonObject,
        要点: String,
        旗: 停旗,
    ): Flow<String> = channelFlow {
        val 径 = 模型文件.定位(库, 套.模型).absolutePath
        if (!File(径).isFile) throw 错配置("找不到模型文件「${套.模型}」——去「本地模型」页下载。")

        val 参 = 合并参数(套, 设定)
        fun 数f(键: String, 默: Float) = (参[键] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 默
        fun 数i(键: String, 默: Int) = (参[键] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 默

        val 窗口 = 本机窗口(套).let { if (it > 0) it else 本地默认上下文 }
        val gpu层 = 数i("n_gpu_layers", 0)
        if (gpu层 > 0) 准备OpenCL()
        val 配置 = LlamaConfig(
            contextSize = 窗口,
            threads = 数i("n_threads", 4).coerceAtLeast(1),
            gpuLayers = gpu层,
        )

        // ── ① 取控制 + 锁人格 ──
        val 控 = 取控制(设定, 要点)
        val 锁过 = 锁人格(消息们, 控.要点)

        // ── ② 组装多轮 messages（system 在前，模型自带模板会渲染它）──
        val 消息 = ArrayList<聊天消息>(锁过.size + 1)
        if (系统.isNotBlank()) 消息.add(聊天消息("system", 系统))
        for (m in 锁过) if (m.role == "user" || m.role == "assistant")
            消息.add(聊天消息(m.role, m.content))
        if (消息.isEmpty() || 消息.last().role != "user")
            消息.add(聊天消息("user", 开场占位))

        val 模 = 确保载入(径, 配置)

        // ── ③ 停止词（按族补回合标记 + 防"替用户说话"）+ 禁吐 ──
        //    认族优先用 GGUF 元数据 `general.architecture`（对齐 PC），拿不到才退回看文件名。
        val 称呼 = 设定文本(设定, "用户称呼").ifEmpty { "你" }
        val 停 = (停止词(套.模型, 当前架构) + 常用停止 + 防替用户停止(称呼)).distinct()
        val 禁 = 要禁的(控)

        // ── ④ 渲染（含预填）→ 拿到模型真正吃的那串，才能判「思维位有没有留开」──
        //    对齐 PC 版：`该分流` 看的是**渲染后提示词尾部**有没有未闭合的 `<think>`，
        //    这是比"查型号名"硬得多的证据（Qwen3 思考模式那种模板留开思维位的，就靠它认）。
        val 提示词 = 本地对话.渲染(模, 消息, 控.预填)
        val 开分流 = 该分流(控.分流, 提示词, 套.模型)

        生成选项(
            预填 = 控.预填,
            停止 = 停,
            禁吐 = 禁,
            上限 = 数i("max_tokens", 1024).coerceAtLeast(1),
            温度 = 数f("temperature", 0.7f),
            topP = 数f("top_p", 0.9f),
            topK = 数i("top_k", 40).coerceAtLeast(1),
            种子 = 数i("seed", -1),
            // 重复惩罚是小模型"不出戏"的关键：默认开 1.1，接口配置里填了就用填的
            重复惩罚 = 数f("repeat_penalty", 1.1f),
            惩罚窗口 = 数i("penalty_last_n", 0),
            出现惩罚 = 数f("presence_penalty", 0f),
            频率惩罚 = 数f("frequency_penalty", 0f),
        ).let { 选项 ->
            // 预填要「回吐」：它是提示词的一部分、不在生成结果里 —— 不先吐一次，
            // 界面上这条回复就缺开头。
            if (控.预填.isNotEmpty()) trySend(控.预填)

            val 分流 = if (开分流) 分流器() else null
            val 槽 = 令牌槽 { 段 ->
                if (分流 != null) {
                    for (出 in 分流.喂(段)) trySend(出)
                } else {
                    trySend(段)
                }
                !旗.isSet          // 置旗 = 立刻打断
            }

            本地对话.流式(模, 提示词, 选项, 槽)

            if (分流 != null) for (出 in 分流.完()) trySend(出)
        }
    }.buffer(Channel.UNLIMITED)

    /** 常见的回合/结束标记 —— 模板自带的 EOG 之外再兜一层，防"演完自己接话"。 */
    private val 常用停止 = listOf(
        "<|im_end|>", "<|eot_id|>", "<|endoftext|>", "</s>", "<|end|>", "<end_of_turn>",
    )

}
