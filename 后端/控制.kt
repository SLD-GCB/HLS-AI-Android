package 后端

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 本地模型**控制协议层** —— 对齐 PC 版 `酒馆协议.py` 的那一层。
 *
 * 它管的是"模型听不听、说不说胡话、思维链露不露"，不是"设定进不进得去"
 * （那个在 `拼提示` 里）。所有环节：
 *
 *   ⓪ 控制  要点复述到离生成最近的位置（[锁人格]）—— 默认关
 *   ① 渲染  模型自带模板渲染多轮 messages（在 native 里做）
 *   ② 停止  按族补回合标记（[停止词]），防"答完接着演用户"
 *   ③ 中间层 文字层禁吐（[要禁的]）+ 掐思维 —— 默认开
 *   ④ 输出侧 思维链扣住不给用户看（[分流器]）—— 只有推理模型才开
 *   ⑤ 预填  替模型起手（在 native 里接在模板末尾）+ 回吐
 *
 * ⚠ **认族只能靠模型文件名**：安卓这边通过成熟绑定拿不到 GGUF 的
 *   `general.architecture` 元数据，所以退化用「模型字段里的文件名」去认。
 *   认不出回空串（**不瞎猜**），那就只靠模板自带的停止符。
 */

// ══ 认族 ═══════════════════════════════════════════════════════════

private val 族头 = listOf(
    "qwen" to "qwen", "glm" to "glm", "chatglm" to "glm",
    "phi" to "phi", "llama" to "llama", "gemma" to "gemma",
    "mistral" to "mistral", "mixtral" to "mistral",
    // 后面补的三族：架构名分别是 deepseek* / minicpm / seed_oss；
    // `doubao` 是豆包（Seed）的常见叫法，只在"看文件名"那条路上有用。
    "deepseek" to "deepseek", "minicpm" to "minicpm",
    "seed" to "seed", "doubao" to "seed",
)

/**
 * 英文中心的族 —— 这些模型对中文指令服从度差，扮演规则要给**英文版**。
 * （qwen / glm / deepseek / minicpm / seed 是中文强项，照旧用中文。）
 */
private val 英文族 = setOf("llama", "phi", "gemma", "mistral")

/** 这个模型该不该用英文版提示。认不出族就当中文（本 App 的用户大多是中文模型）。 */
fun 要英文(模型名: String, 架构: String = ""): Boolean = 认族(模型名, 架构) in 英文族

/**
 * 每个族的**回合标记** —— 模板不一定给全，补齐它防"答完不停、自己把用户的话也演了"。
 *
 * ⚠ 这些串模型**只会在开新回合时吐**，拿它们当停止串才安全。PC 版那张表在真模型上
 *   也没逐个验过，所以这里**只补认得出的族**，认不出就不补（宁可不补，别误伤）。
 */
private val 族停 = mapOf(
    "qwen" to listOf("<|im_end|>", "<|im_start|>"),
    "glm" to listOf("<|user|>", "<|system|>", "<|observation|>"),
    "phi" to listOf("<|end|>", "<|user|>", "<|system|>", "<|assistant|>"),
    "llama" to listOf("<|eot_id|>", "<|start_header_id|>", "<|end_header_id|>"),
    "gemma" to listOf("<end_of_turn>", "<start_of_turn>"),
    "mistral" to listOf("[INST]", "[/INST]"),
    // ↓ 这三族的标记是**从 llama.cpp 的内置模板源码里抄的**，不是猜的：
    //   deepseek2(V2) 用 "User: "/"Assistant: "；deepseek3(V3) 用全角 <｜User｜>/<｜Assistant｜>。
    //   ASCII 那两条带前缀换行，避免正文里正常出现的 "User:" 被误判成换人。
    "deepseek" to listOf(
        "<｜User｜>", "<｜Assistant｜>", "<｜end▁of▁sentence｜>", "\nUser:", "\nAssistant:",
    ),
    //   minicpm(旧 3B 格式)：<用户> / <AI>
    "minicpm" to listOf("<用户>", "<AI>"),
    //   seed(豆包/Seed-OSS)：<seed:bos>role\n … <seed:eos>
    "seed" to listOf("<seed:bos>", "<seed:eos>"),
)

/**
 * 认这个模型属于哪个族。认不出回 `""`（**不瞎猜**）。
 *
 * ⚠ 优先用 GGUF 元数据 `general.architecture`（= 对齐 PC `酒馆协议.族` 的做法，按**前缀**匹配，
 *   所以 `qwen35`/`qwen3`/`qwen2` 都落到 `qwen`）；拿不到元数据才退成**看文件名**（安卓早期只有文件名）。
 */
fun 认族(模型名: String, 架构: String = ""): String {
    if (架构.isNotBlank()) {
        val a = 架构.lowercase()
        for ((头, 族) in 族头) if (a.startsWith(头)) return 族
        return ""
    }
    val 名 = 模型名.lowercase()
    for ((头, 族) in 族头) if (名.contains(头)) return 族
    return ""
}

/** 这个族该补的回合标记（**不含**模板自带的停止符）。 */
fun 停止词(模型名: String, 架构: String = ""): List<String> = 族停[认族(模型名, 架构)].orEmpty()

/**
 * 防「替用户说话」：小模型答完常顺手接着写 `\n用户：……`（把用户接下来的话也演了）。
 * 把这种**回合格式**当停止串就能在最前面截住。
 *
 * ⚠ 只在「换行 + 称呼 + 冒号」这种**行首说话人格式**上停，不动正文里正常提到的「用户」。
 *   （改用多轮模板后，历史里不再出现这些标签，误伤面更小了。）
 */
fun 防替用户停止(称呼: String): List<String> {
    val 出 = mutableListOf("\n用户：", "\n用户:", "\n【用户】", "\n用户说：", "\n用户说:")
    val 名 = 称呼.trim()
    if (名.isNotEmpty() && 名 != "你" && 名 != "用户") {
        出.add("\n$名："); 出.add("\n$名:")
    }
    return 出
}

// ══ 中间层：默认要掐的短语 ═════════════════════════════════════════

/** 默认要从生成里**掐掉**的 —— 全是"自称 AI/出戏"的整句（兑现 `本地规矩` 第 3 条）。 */
val 默认禁吐 = listOf(
    "作为AI", "作为 AI", "我是一个AI", "我是一个 AI", "我是个AI", "作为一个AI",
    "作为人工智能", "我是一个人工智能", "我是个AI助手", "作为人工智能助手",
    "作为一个语言模型", "我是一个语言模型", "作为语言模型",
    "作为一个AI语言模型", "我是一个AI语言模型",
    "我是一个助手", "作为一个助手", "我是一个智能助手", "作为一个智能助手",
    "我是一个虚拟助手", "作为一个虚拟助手", "我是一个虚拟角色", "作为一个虚拟角色",
    "我是一个程序", "我是一个机器人", "我只是一个AI", "我只是一个人工智能",
)

/** 思维标记（`掐思维='开'` 时才加）。⚠ 实测它会让模型疯狂复读，默认不开。 */
val 思维标记 = listOf("<think>")

// ══ 输出侧：思维链分流 ═════════════════════════════════════════════

val 思考闭标记 = listOf("</think>", "<|end_of_thought|>", "<|/think|>", "<channel|>")
val 思考开标记 = listOf("<think>", "<|begin_of_thought|>", "<|think|>", "<|channel>thought")

/** 明确**不思考**的型号 —— 名字撞上一票否决。 */
private val 非推理型号 = listOf("instruct-2507")

/** 认推理模型用的**确切型号名单**（不是猜词根）：R1 蒸馏系模板里没有 `<think>` 却天生会想。 */
private val 推理型号 = listOf("deepseek-r1", "qwq", "glm-z1", "magistral")

/**
 * 提示词尾部有没有**未闭合**的 `<think>` —— 也就是"模板把思考位留开着"。
 * 这是比任何名字判据都硬的证据。
 */
fun 思考没闭合(提示词: String): Boolean {
    val 尾 = 提示词.takeLast(200)
    for (开 in 思考开标记) {
        val i = 尾.lastIndexOf(开)
        if (i < 0) continue
        val 后 = 尾.substring(i + 开.length)
        if (思考闭标记.none { 后.contains(it) }) return true
    }
    return false
}

/** 这次到底开不开分流：用户三态（自动/开/关）+ 自动判定。**默认不开**（有证据才开）。 */
fun 该分流(选: String, 提示词: String, 模型名: String): Boolean {
    when (选.trim()) {
        "关" -> return false
        "开" -> return true
    }
    val 名 = 模型名.lowercase()
    if (非推理型号.any { 名.contains(it) }) return false
    if (思考没闭合(提示词)) return true
    return 名.isNotEmpty() && 推理型号.any { 名.contains(it) }
}

/**
 * 流式分流器：**把思维链扣住不给用户看，只吐正文**。
 *
 * ⚠ 不要无条件开！先问 [该分流]。对不思考的模型，无条件开会让它一路扣着不放，
 *   用户盯着空白等 —— 比不剥还坏。
 *
 * ⚠ 收尾时还扣着的内容**放出来**（不是吞掉）：宁可看到异常，也不静默。
 */
class 分流器(上限: Int = 6000) {
    private val 限 = 上限
    private val 扣 = StringBuilder()
    private var 过了 = false
    private var 放了 = false

    /** 这次出了闭合标记、但一个字正文都没有（全是草稿）。 */
    val 只思维: Boolean get() = 过了 && !放了

    /** 吃一段，回这一段里该给用户看的（可能空）。 */
    fun 喂(段: String): List<String> {
        if (段.isEmpty()) return emptyList()
        if (过了) { 放了 = true; return listOf(段) }
        扣.append(段)
        val 位 = 第一个闭标记()
        if (位 >= 0) {
            过了 = true
            val 余 = 扣.substring(位)
            扣.setLength(0)
            if (余.isNotEmpty()) 放了 = true
            return if (余.isNotEmpty()) listOf(余) else emptyList()
        }
        if (扣.length > 限) {
            val 出 = 扣.toString()
            扣.setLength(0)
            过了 = true
            放了 = 出.isNotEmpty()
            return listOf(出)
        }
        return emptyList()
    }

    /** 收尾：还扣着的放出来。 */
    fun 完(): List<String> {
        if (扣.isEmpty()) return emptyList()
        val 余 = 扣.toString()
        扣.setLength(0)
        放了 = true
        return listOf(余)
    }

    /** 缓冲里最早出现的闭合标记**之后**的位置。没有回 -1。 */
    private fun 第一个闭标记(): Int {
        var 最早 = -1
        var 位 = -1
        for (记 in 思考闭标记) {
            val i = 扣.indexOf(记)
            if (i >= 0 && (最早 < 0 || i < 最早)) { 最早 = i; 位 = i + 记.length }
        }
        return 位
    }
}

// ══ 控制项的读取与装配 ═════════════════════════════════════════════

/**
 * 一次生成的人格控制（对齐 `酒馆协议.取控制`）。
 *
 *   `要点`   一两句角色核心，复述到**最后一条 user 消息末尾**（[锁人格]）。默认关，
 *            要用户在设定里写 `人格锁='开'` 才用。
 *   `预填`   替模型开头几个字。**默认空** —— 得用户自己写。
 *   `分流`   思维链剥不剥。`'自动'`（默认）交给 [该分流] 判。
 *   `中间层` 解码期干预总开关。`'自动'`（默认）**开**，写 `'关'` 全关。
 *   `掐思维` 默认**关**（它会让模型复读）。
 *   `禁吐`   用户额外要掐的短语。
 */
data class 控制(
    val 要点: String = "",
    val 预填: String = "",
    val 分流: String = "自动",
    val 中间层: String = "自动",
    val 掐思维: String = "关",
    val 禁吐: List<String> = emptyList(),
)

fun 取控制(设定: kotlinx.serialization.json.JsonObject, 要点: String): 控制 {
    fun 文(键: String): String = (设定[键] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
    val 锁 = 文("人格锁").lowercase()
    val 用要点 = when (锁) {
        "开", "on", "1", "是" -> 要点.trim()
        else -> ""                       // 默认关（跟要点/预填相反）
    }
    return 控制(
        要点 = 用要点,
        预填 = 文("预填"),
        分流 = 文("分流").ifEmpty { "自动" },
        中间层 = 文("中间层").ifEmpty { "自动" },
        掐思维 = 文("掐思维").ifEmpty { "关" },
        禁吐 = 拆禁吐(设定["禁吐"]),
    )
}

/** `禁吐` 可以是数组，也可以是"一行一条"的字符串。 */
private fun 拆禁吐(v: kotlinx.serialization.json.JsonElement?): List<String> {
    if (v == null) return emptyList()
    val 原 = when (v) {
        is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> v.contentOrNull?.split('\n', ',')
        else -> null
    } ?: return emptyList()
    return 原.map { it.trim() }.filter { it.isNotEmpty() }
}

/** 这次该掐哪些短语 = 默认禁吐 + 用户加的（除非 `中间层='关'`）。 */
fun 要禁的(控: 控制): List<String> {
    val 用户 = 控.禁吐
    val 关 = 控.中间层.trim().lowercase() in listOf("关", "off", "0", "否", "不")
    if (关) return 用户
    val 出 = ArrayList<String>()
    if (控.掐思维.trim().lowercase() in listOf("开", "on", "1", "是")) 出.addAll(思维标记)
    for (x in 默认禁吐 + 用户) if (x.isNotEmpty() && x !in 出) 出.add(x)
    return 出
}

/**
 * 把人格锁在**离生成最近的位置**：`要点` 追加到最后一条 user 消息末尾。
 *
 * ⚠ 跟"设定进不进得去"（`拼提示` 里那套）不是一回事：这个管"它**听不听**"。
 *   提示词里离生成越近，小模型的服从度越高。
 */
fun 锁人格(消息们: List<聊天条>, 要点: String): List<聊天条> {
    val 要 = 要点.trim()
    if (要.isEmpty()) return 消息们
    val 出 = 消息们.toMutableList()
    for (i in 出.indices.reversed()) {
        if (出[i].role == "user") {
            val 原 = 出[i].content.trimEnd()
            出[i] = 出[i].copy(content = if (原.isEmpty()) "（$要）" else "$原\n\n（$要）")
            return 出
        }
    }
    出.add(聊天条("user", "（$要）"))
    return 出
}
