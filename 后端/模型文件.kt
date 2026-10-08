package 后端

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本地 GGUF 模型文件的管理与下载 —— 对齐 PC 版 `酒馆本地.py` 里的"模型文件"那半。
 *
 * 三个来源：ModelScope（国内最稳）/ HF 镜像 hf-mirror.com（最全，国内直连）/ HuggingFace 官方。
 * 下载落到 `酒馆数据/模型/_暂存/`，下完 `.gguf` 挪进 `酒馆数据/模型/`。
 */

/** 一个下载源。`系` 决定走哪套接口：`ms`=ModelScope，`hf`=HF 系。 */
data class 下载源(val 标: String, val 名: String, val 端: String, val 系: String)

val 下载源们 = listOf(
    下载源("modelscope", "ModelScope（国内直连）", "https://www.modelscope.cn", "ms"),
    下载源("aifasthub", "AI Fast Hub（HF 镜像·国内直连）", "https://aifasthub.com", "hf"),
    下载源("hf-mirror", "HF 镜像 hf-mirror.com", "https://hf-mirror.com", "hf"),
    下载源("huggingface", "HuggingFace 官方（要梯子）", "https://huggingface.co", "hf"),
)

const val 默认源 = "modelscope"

fun 取源(标: String): 下载源 = 下载源们.firstOrNull { it.标 == 标 } ?: 下载源们.first()

/**
 * 精选档位。**每条自带 `源`**——因为有的模型在 ModelScope，有的只在 HF 系。
 * 全是 1.5~3B（含 0.5B/1B 兜底档）；**每条都实测过"能列出 GGUF"**。
 *
 * 分组按 限制性/来源：
 *   国内·通用 / 国内·面壁 / 海外·通用 / 海外·编程   → 正常（有限制的）模型
 *   去审查·无限制 / 角色扮演·NSFW                    → 无限制/去审查模型
 */
data class 档位(val 组: String, val 名字: String, val 仓库: String, val 说明: String, val 源: String = "modelscope")

val 精选档位 = listOf(
    // ══════════ 国内·通用（ModelScope 直连 / 官方仓库）══════════
    档位("国内·通用（官方）", "Qwen2.5-0.5B", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "官方，最小最快，约 0.6G 内存"),
    档位("国内·通用（官方）", "Qwen2.5-1.5B", "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "官方，手机首选，约 1.5G 内存"),
    档位("国内·通用（官方）", "Qwen3-1.7B", "Qwen/Qwen3-1.7B-GGUF", "官方，新一代，约 2G 内存，带推理"),
    档位("国内·通用（官方）", "Qwen2.5-3B", "Qwen/Qwen2.5-3B-Instruct-GGUF", "官方，上限档，约 3G 内存"),
    档位("国内·通用（官方）", "InternLM2.5-1.8B", "internlm/internlm2_5-1_8b-chat-gguf", "官方（上海 AI 实验室），中文好，约 1.8G", "aifasthub"),
    档位("国内·通用（官方）", "Qwen3.5-2B", "unsloth/Qwen3.5-2B-GGUF", "较新，约 2G 内存，量化档全"),
    档位("国内·通用（官方）", "DeepSeek-R1-Distill-Qwen-1.5B", "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "带推理链，约 1.5G 内存（DeepSeek 无官方 GGUF，用 unsloth）"),

    // ══════════ 国内·面壁 MiniCPM（官方）══════════
    档位("国内·面壁 MiniCPM（官方）", "MiniCPM5-1B", "OpenBMB/MiniCPM5-1B-GGUF", "官方，小快，约 1G 内存"),
    档位("国内·面壁 MiniCPM（官方）", "MiniCPM5-2B", "OpenBMB/MiniCPM5-2B-gguf", "官方，约 2G 内存，综合素质好"),

    // ══════════ 海外·通用（官方优先）══════════
    档位("海外·通用", "Llama-3.2-3B-Instruct", "bartowski/Llama-3.2-3B-Instruct-GGUF", "英文强，约 3G（Meta 无官方 GGUF，用 bartowski）", "aifasthub"),
    档位("海外·通用", "gemma-2-2b-it", "bartowski/gemma-2-2b-it-GGUF", "谷歌小模型，约 2G", "aifasthub"),
    档位("海外·通用", "Phi-3-mini-4k-instruct", "microsoft/Phi-3-mini-4k-instruct-gguf", "微软官方，3.8B，约 3.5G", "aifasthub"),

    // ══════════ 海外·编程（官方）══════════
    档位("海外·编程", "Qwen2.5-Coder-1.5B", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF", "官方，写代码，约 1.5G"),
    档位("海外·编程", "Qwen2.5-Coder-3B", "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF", "官方，写代码，约 3G"),

    // ══════════ 去审查 / 无限制（FastHub）══════════
    档位("去审查 / 无限制", "Qwen2.5-1.5B abliterated", "mradermacher/Qwen2.5-1.5B-Instruct-abliterated-GGUF", "去审查，中文好，约 1.5G", "aifasthub"),
    档位("去审查 / 无限制", "Qwen2.5-3B abliterated", "mradermacher/Qwen2.5-3B-Instruct-abliterated-GGUF", "去审查，约 3G", "aifasthub"),
    档位("去审查 / 无限制", "Llama-3.2-1B abliterated", "mradermacher/Llama-3.2-1B-Instruct-abliterated-GGUF", "最小，约 1G", "aifasthub"),
    档位("去审查 / 无限制", "Llama-3.2-3B abliterated", "MaziyarPanahi/Llama-3.2-3B-Instruct-abliterated-GGUF", "英文，约 3G", "aifasthub"),
    档位("去审查 / 无限制", "Llama-3.2-3B uncensored", "MaziyarPanahi/Llama-3.2-3B-Instruct-uncensored-GGUF", "英文无限制，约 3G", "aifasthub"),
    档位("去审查 / 无限制", "Llama-3.2-3B uncensored（bartowski）", "bartowski/Llama-3.2-3B-Instruct-uncensored-GGUF", "同上，量化档更全，约 3G", "aifasthub"),
    档位("去审查 / 无限制", "gemma-2-2b abliterated", "bartowski/gemma-2-2b-it-abliterated-GGUF", "谷歌去审查，约 2G", "aifasthub"),
    档位("去审查 / 无限制", "MiniCPM5-2B heretic", "Abiray/MiniCPM5-2B-heretic-abliterated-GGUF", "面壁去审查版，约 2G", "aifasthub"),
    档位("去审查 / 无限制", "MiniCPM5-2B uncensored", "mondk/MiniCPM5-2B-Abliterated-Uncensored-GGUF", "去审查，约 2G", "aifasthub"),
    档位("去审查 / 无限制", "glm-edge-1.5b heretic", "mradermacher/heretic_glm-edge-1.5b-chat-GGUF", "智谱去审查版，约 1.5G", "aifasthub"),
    档位("去审查 / 无限制", "Qwen3.5-2B Uncensored", "HauhauCS/Qwen3.5-2B-Uncensored-HauhauCS-Aggressive", "激进无限制，约 2G", "aifasthub"),

    // ══════════ 角色扮演 / NSFW（FastHub）══════════
    档位("角色扮演 / NSFW", "Llama-3.2-3B roleplay-tuned", "mradermacher/Llama-3.2-3B-Instruct-roleplay-tuned-i1-GGUF", "角色扮演微调，约 3G", "aifasthub"),
    档位("角色扮演 / NSFW", "Qwen3.5-2b roleplay-tuned", "mradermacher/Qwen-3.5-2b-roleplay-tuned-GGUF", "中文角色扮演，约 2G", "aifasthub"),
    档位("角色扮演 / NSFW", "Luminus-1.5B Roleplay", "ReXeeD/Luminus-1.5B-Roleplay-GGUF", "专攻角色扮演，约 1.5G", "aifasthub"),
    档位("角色扮演 / NSFW", "Ministral-3B roleplay ERP", "mradermacher/Ministral-3-3B-Instruct-2512-roleplay-tuned-ERP-tolerant-GGUF", "角色扮演 + ERP 容忍，约 3G", "aifasthub"),
    档位("角色扮演 / NSFW", "DARK-LUST-ROLEPLAY-1B", "mradermacher/DARK-LUST-ROLEPLAY-3.2-1B-i1-GGUF", "NSFW 向，约 1G", "aifasthub"),
    档位("角色扮演 / NSFW", "Leviathan NSFW Roleplay-1B", "mradermacher/Leviathan_NSFW_Roleplay-3.2-1B-i1-GGUF", "NSFW 向，约 1G", "aifasthub"),
)

object 模型文件 {

    private fun 客户() = 网络.客户(读秒 = 600)
    private fun 列目录客户() = 网络.客户(读秒 = 30)

    fun 模型目录(库: TavernStore): File = File(库.目录, "模型").apply { mkdirs() }
    fun 暂存目录(库: TavernStore): File = File(模型目录(库), "_暂存").apply { mkdirs() }
    fun 定位(库: TavernStore, 名: String): File = File(模型目录(库), File(名).name)

    // ── 实时发现：从三个源拉"最近更新的 GGUF 仓库" ──────────────────

    // ── 搜索结果的"是不是能聊天"过滤 ──────────────────────────────
    // 搜索是全站原始结果：图生图 / 语音 / 视觉 / embedding 都会被搜出来，
    // 用户下回来要么加载不了、要么胡言乱语。这里按源站给的元数据先筛一遍。

    /** 名字或标签里出现这些，基本不是"能聊天的文本模型"。 */
    private val 非对话词 = listOf(
        "image-to-image", "text-to-image", "image-generation", "comfyui", "diffusion", "lora",
        "vision", "multimodal", "multi-modal", "image-text-to-text", "vl-", "-vl", "visual",
        "whisper", "audio", "speech", "asr", "tts", "voice",
        "embed", "embedding", "rerank", "bge", "gte", "e5-", "nomic", "minilm",
        "ocr", "segformer", "depth", "clip-", "sam-",
    )

    /** 一看就是对话模型的词。 */
    private val 对话词 = listOf(
        "instruct", "chat", "-it", "it-", "qwen", "llama", "gemma", "deepseek", "glm",
        "phi", "mistral", "ministral", "internlm", "minicpm", "yi-", "baichuan",
    )

    /** 可信发布者：官方组织 + 业界知名量化/转存者。搜索只保留这些人的仓库。 */
    private val 可信发布者 = setOf(
        // ── 官方组织 ──
        "qwen", "deepseek-ai", "deepseek", "google", "microsoft", "meta-llama", "mistralai",
        "openbmb", "internlm", "shanghai_ai_laboratory", "thudm", "zhipuai", "01-ai", "baichuan-inc",
        "moonshotai", "minimaxai", "nvidia", "apple", "allenai", "cohereforai", "ibm-granite",
        "huggingfacetb", "tencent", "stepfun", "opengvlab", "tiiuae",
        // ── 业界知名量化 / 转存 ──
        "unsloth", "bartowski", "mradermacher", "quantfactory", "maziyarpanahi", "thebloke",
        "lmstudio-community", "lmstudio", "ggml-org", "devquasar", "second-state",
    )

    private fun 可信(仓库: String): Boolean =
        可信发布者.contains(仓库.substringBefore('/').lowercase())

    /** 某个仓库看着像不像"能聊天的文本模型"。 */
    private fun 像对话模型(名字: String, 标签: List<String>, 任务们: List<String>): Boolean {
        val 低名 = 名字.lowercase()
        val 低标 = 标签.map { it.lowercase() }
        val 低任 = 任务们.map { it.lowercase() }
        // 黑名单：任务或标签里带非对话特征
        if (低任.any { t -> 非对话词.any { t.contains(it) } }) return false
        if (低标.any { t -> 非对话词.any { t.contains(it) } }) return false
        if (非对话词.any { 低名.contains(it) }) return false
        // 白名单：有明确对话信号，或名字一看就是对话模型
        if (低标.contains("conversational")) return true
        if (低任.contains("text-generation") && 对话词.any { 低名.contains(it) }) return true
        return 对话词.any { 低名.contains(it) }
    }

    /** 分卷文件（`xxx-00001-of-00003.gguf`）：本 App 只下单个文件，先不收。 */
    private val 分卷正则 = Regex("""-\d{5}-of-\d{5}""")
    private fun 是分卷(名: String): Boolean = 分卷正则.containsMatchIn(名)

    /**
     * 这个 GGUF 文件值不值得列出来。
     * 剔掉三类：
     *   · 分卷（下不了整套）
     *   · `mmproj`（视觉投影文件，不是聊天模型主体）
     *   · 压得太狠的量化（IQ1/IQ2/IQ3/Q2_K/Q3_K_S）——1.5B 的 Q2 只有几百 MB，
     *     跑起来就是胡言乱语，留着只会拉低体验
     */
    private val 烂量化 = Regex("""(?i)(IQ1|IQ2|IQ3|Q2_K|Q3_K_S)""")
    private fun 要列(名: String): Boolean {
        if (!名.lowercase().endsWith(".gguf")) return false
        if (是分卷(名)) return false
        if (名.contains("mmproj", ignoreCase = true)) return false
        if (烂量化.containsMatchIn(名)) return false
        return true
    }

    /** 一条发现结果。`体积` = 仓库里 GGUF 的 (最小, 最大) 字节数，惰性填；null 表示还没算。 */
    data class 发现条目(val 仓库: String, val 名字: String, val 下载: Long, val 更新: String, val 体积: Pair<Long, Long>? = null)

    /** 上一次搜索过滤掉了多少个（给界面提示用）。 */
    @Volatile var 上次滤掉: Int = 0; private set

    fun 热门模型(源: 下载源, 词: String = "", 限: Int = 30): List<发现条目> =
        if (源.系 == "ms") 热门ms(源, 词, 限) else 热门hf(源, 词, 限)

    private fun 热门hf(源: 下载源, 词: String, 限: Int): List<发现条目> {
        // 多抓一些再筛：过滤会砍掉不少非对话模型
        var 网 = "${源.端}/api/models?filter=gguf&sort=lastModified&direction=-1&limit=100"
        if (词.isNotBlank()) 网 += "&search=" + java.net.URLEncoder.encode(词, "UTF-8")
        val 文 = 取文本(网, 源)
        val 阵 = try { 酒馆Json.parseToJsonElement(文) as? JsonArray } catch (e: Exception) { null } ?: return emptyList()
        val 结果 = 阵.mapNotNull { o ->
            val jo = o as? JsonObject ?: return@mapNotNull null
            val id = jo["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val 标 = (jo["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val 任 = listOfNotNull(jo["pipeline_tag"]?.jsonPrimitive?.contentOrNull)
            if (!像对话模型(id.substringAfterLast('/'), 标, 任)) return@mapNotNull null
            if (!可信(id)) return@mapNotNull null
            发现条目(
                id, id.substringAfterLast('/'),
                (jo["downloads"] as? JsonPrimitive)?.longOrNull ?: 0L,
                jo["lastModified"]?.jsonPrimitive?.contentOrNull?.take(10) ?: "",
            )
        }
        上次滤掉 = 阵.size - 结果.size
        return 结果.take(限)
    }

    private fun 热门ms(源: 下载源, 词: String, 限: Int): List<发现条目> {
        // 注意：ModelScope 这个搜索接口要用 PUT（POST 会 404）。
        val 网 = "${源.端}/api/v1/dolphin/models"
        val 体文 = buildJsonObject {
            put("PageSize", 100); put("PageNumber", 1); put("SortBy", "Default")
            put("Name", 词); put("Target", ""); put("SingleCriterion", JsonArray(emptyList()))
            put("Criterion", JsonArray(emptyList()))
        }.toString()
        val 请求 = Request.Builder().url(网).header("User-Agent", "honglusi")
            .put(体文.toRequestBody("application/json".toMediaType())).build()
        val 文 = try {
            列目录客户().newCall(请求).execute().use {
                if (it.code != 200) throw 酒馆错误("ModelScope 回了 HTTP ${it.code}")
                it.body?.string() ?: "{}"
            }
        } catch (e: 酒馆错误) { throw e } catch (e: Exception) { throw 酒馆错误("连不上 $网：${e.message}") }
        val 对 = try { 酒馆Json.parseToJsonElement(文).jsonObject } catch (e: Exception) { return emptyList() }
        val 模 = ((对["Data"] as? JsonObject)?.get("Model") as? JsonObject)?.get("Models") as? JsonArray ?: return emptyList()
        val 结果 = 模.mapNotNull { o ->
            val jo = o as? JsonObject ?: return@mapNotNull null
            val 组 = jo["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val 名 = jo["Name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val 标 = (jo["Tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            // 这个页面是下 GGUF 的——把非 GGUF 的仓库滤掉
            val 是gguf = 标.any { it.equals("gguf", true) } || 名.contains("gguf", true)
            if (!是gguf) return@mapNotNull null
            // ModelScope 给了 Tasks（text-generation / image-text-to-text / text-to-image…），
            // 用它把图生图、视觉多模态这些踢掉
            val 任 = (jo["Tasks"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("Name")?.jsonPrimitive?.contentOrNull } ?: emptyList()
            if (!像对话模型(名, 标, 任)) return@mapNotNull null
            if (!可信("$组/$名")) return@mapNotNull null
            发现条目(
                "$组/$名", 名,
                (jo["Downloads"] as? JsonPrimitive)?.longOrNull ?: 0L,
                秒转日((jo["LastUpdatedTime"] as? JsonPrimitive)?.longOrNull),
            )
        }.take(限)
        上次滤掉 = 模.size - 结果.size
        return 结果
    }

    /** 秒级时间戳 → `yyyy-MM-dd`；无效返回空串。 */
    private fun 秒转日(秒: Long?): String {
        if (秒 == null || 秒 <= 0L) return ""
        return try {
            java.time.Instant.ofEpochSecond(秒).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        } catch (e: Exception) { "" }
    }

    /** 模型目录下的 GGUF 清单：`(文件名, 字节数)`，按名字排。 */
    fun 列本地(库: TavernStore): List<Pair<String, Long>> =
        (模型目录(库).listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.lowercase().endsWith(".gguf") }
            .map { it.name to it.length() }
            .sortedBy { it.first }

    fun 删本地(库: TavernStore, 名: String): Boolean {
        val f = 定位(库, 名)
        return f.isFile && f.delete()
    }

    /** 没下完的残件（`<名>.part`）：`(文件名, 字节数)`。模型目录和老的 _暂存 都扫。 */
    fun 列暂存(库: TavernStore): List<Pair<String, Long>> {
        val 处 = listOf(模型目录(库), 暂存目录(库))
        return 处.flatMap { d ->
            (d.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.endsWith(".part") }
                .map { it.name.removeSuffix(".part") to it.length() }
        }.distinctBy { it.first }.sortedBy { it.first }
    }

    /** 残件在哪儿（模型目录优先，其次老的 _暂存）。 */
    private fun 找残件(库: TavernStore, 名: String): File? =
        listOf(File(模型目录(库), "$名.part"), File(暂存目录(库), "$名.part")).firstOrNull { it.isFile }

    fun 删暂存(库: TavernStore, 名: String): Boolean = 找残件(库, 名)?.delete() ?: false

    /**
     * 把已经下完、但没来得及「转正」的残件收下：校验 GGUF 魔数后改名进模型目录。
     * 给那种"文件挺大、看着是完整的，却一直躺在 _暂存 里"的情况兜底。
     */
    fun 收下残件(库: TavernStore, 名: String): String {
        val 临时 = 找残件(库, 名) ?: throw 酒馆错误("找不到残件：$名.part")
        val 头 = ByteArray(4)
        try { 临时.inputStream().use { it.read(头) } } catch (_: Exception) { }
        if (String(头, Charsets.US_ASCII) != "GGUF") throw 酒馆错误("这个残件不是完整的 GGUF（开头不是「GGUF」），收不了。")
        val 目标 = File(模型目录(库), 名)
        if (目标.exists()) 目标.delete()
        if (!临时.renameTo(目标)) { 临时.copyTo(目标, overwrite = true); 临时.delete() }
        return 名
    }

    /** 列一个仓库里的 GGUF 文件：`(仓库内路径, 字节数)`。 */
    fun 列远程文件(仓库: String, 源: 下载源): List<Pair<String, Long>> {
        val 仓 = 仓库.trim().trim('/')
        if (仓.isEmpty()) throw 酒馆错误("仓库名不能空")
        return if (源.系 == "ms") 列ms(仓, 源) else 列hf(仓, 源)
    }

    /** 一个仓库里 GGUF 的体积范围：`(最小, 最大)` 字节。拿不到就 `(0, 0)`。 */
    fun 体积范围(仓库: String, 源: 下载源): Pair<Long, Long> {
        val 们 = try { 列远程文件(仓库, 源) } catch (e: Exception) { return 0L to 0L }
        val 正 = 们.map { it.second }.filter { it > 0 }
        if (正.isEmpty()) return 0L to 0L
        return 正.min() to 正.max()
    }

    private fun 列hf(仓: String, 源: 下载源): List<Pair<String, Long>> {
        // 用 tree 接口（带 size）；单模型接口的 siblings 不含 size。
        val 网 = "${源.端}/api/models/$仓/tree/main"
        val 文 = 取文本(网, 源)
        val 阵 = try { 酒馆Json.parseToJsonElement(文) as? JsonArray } catch (e: Exception) { throw 酒馆错误("$网 回来的不是 JSON") } ?: return emptyList()
        return 阵.mapNotNull { 条 ->
            val o = 条 as? JsonObject ?: return@mapNotNull null
            val 名 = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!要列(名)) return@mapNotNull null
            val 大 = ((o["lfs"] as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull
                ?: (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            名 to 大
        }.sortedBy { it.second }
    }

    private fun 列ms(仓: String, 源: 下载源): List<Pair<String, Long>> {
        val 网 = "${源.端}/api/v1/models/$仓/repo/files?Revision=master"
        val 文 = 取文本(网, 源)
        val 对 = try { 酒馆Json.parseToJsonElement(文).jsonObject } catch (e: Exception) { throw 酒馆错误("$网 回来的不是 JSON") }
        val 件们 = ((对["Data"] as? JsonObject)?.get("Files") as? JsonArray) ?: return emptyList()
        return 件们.mapNotNull { 条 ->
            val o = 条 as? JsonObject ?: return@mapNotNull null
            val 径 = o["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!要列(径)) return@mapNotNull null
            径 to ((o["Size"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
    }

    private fun 取文本(网: String, 源: 下载源): String {
        val 头 = if (源.标 == "huggingface") mapOf("User-Agent" to "honglusi") else mapOf("User-Agent" to "honglusi", "Accept" to "application/json")
        val 请求 = Request.Builder().url(网).apply { 头.forEach { (k, v) -> header(k, v) } }.get().build()
        return try {
            列目录客户().newCall(请求).execute().use { r ->
                if (r.code != 200) throw 酒馆错误("HTTP ${r.code} ${r.message}（$网）")
                r.body?.string() ?: "{}"
            }
        } catch (e: 酒馆错误) { throw e } catch (e: Exception) { throw 酒馆错误("连不上 $网：${e.message}") }
    }

    /**
     * 下载一个文件到模型目录。**阻塞**，给后台线程用。
     * `进度(已下, 总)` 每读到一块回调一次（总为 0 表示对面没给长度）。
     * `要停()` 返回 true 就中断。
     */
    fun 下载(库: TavernStore, 仓库: String, 文件: String, 源: 下载源, 进度: (Long, Long) -> Unit, 要停: () -> Boolean): String {
        val 仓 = 仓库.trim().trim('/')
        val 名 = File(文件).name
        val 目标 = File(模型目录(库), 名)
        // ⚠ `.part` 直接放在**模型目录里**（不是 _暂存子目录）：
        //   这样下完那一步只是「同目录改名」，最不容易出岔子；
        //   而且中途断了，用户一眼就能看到这个半成品。
        val 临时 = File(模型目录(库), "$名.part")
        临时.parentFile?.mkdirs()

        val 网 = if (源.系 == "ms")
            "${源.端}/api/v1/models/$仓/repo?Revision=master&FilePath=$文件"
        else
            "${源.端}/$仓/resolve/main/$文件"

        val 请求 = Request.Builder().url(网).header("User-Agent", "honglusi").get().build()
        val 应 = try { 客户().newCall(请求).execute() } catch (e: Exception) { throw 酒馆错误("连不上 $网：${e.message}") }
        var 总 = 0L
        var 已 = 0L
        应.use { r ->
            if (r.code != 200) throw 酒馆错误("HTTP ${r.code} ${r.message}（$网）")
            val 身 = r.body ?: throw 酒馆错误("响应体是空的")
            总 = 身.contentLength()
            val 源流 = 身.byteStream()
            临时.outputStream().use { o ->
                val 缓 = ByteArray(64 * 1024)
                while (true) {
                    if (要停()) throw 酒馆错误("已取消")
                    val n = 源流.read(缓)
                    if (n < 0) break
                    o.write(缓, 0, n); 已 += n
                    进度(已, if (总 > 0) 总 else 0)
                }
            }
        }

        // ① 完整性：对面给了长度就必须下满，否则是中途断掉的残文件
        if (总 > 0 && 已 != 总) {
            临时.delete()
            throw 酒馆错误("下载不完整：只收到 ${兆(已)} / 应有 ${兆(总)}（网络中断？）。已丢弃，请重下。")
        }
        // ② 太小：肯定不是模型
        if (已 < 1024L * 1024) {
            临时.delete()
            throw 酒馆错误("下下来的文件只有 $已 字节，不像模型。已丢弃。")
        }
        // ③ 魔数校验：真正的 GGUF 开头必须是 "GGUF"
        //    （搜索页能搜到图生图/语音/embedding 的 gguf，那些 llama.cpp 跑不了）
        val 头 = ByteArray(4)
        try { 临时.inputStream().use { it.read(头) } } catch (_: Exception) { }
        if (String(头, Charsets.US_ASCII) != "GGUF") {
            临时.delete()
            throw 酒馆错误("这个文件开头不是「GGUF」——不是能跑的文本模型（可能是图片/语音模型，或下到的是网页）。已丢弃。")
        }

        // 下完挪进模型目录（原子替换）
        if (目标.exists()) 目标.delete()
        if (!临时.renameTo(目标)) {
            临时.copyTo(目标, overwrite = true); 临时.delete()
        }
        return 名
    }

    private fun 兆(字节: Long): String = "%.1f MB".format(字节 / 1e6)
}
