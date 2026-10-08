package 后端

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 提示词拼装 + 估 token + 填卡 —— 对齐 PC 版 `酒馆大脑.拼提示` / `酒馆工具.估token` / `酒馆协议.填卡`。
 */

// ── 估 token（永远高估，绝不低于真实值）────────────────────────────
fun 估token(文: String?): Int {
    val s = 文 ?: ""
    var 中 = 0
    for (c in s) {
        val o = c.code
        if (o in 0x4E00..0x9FFF || o in 0x3000..0x303F || o in 0xFF00..0xFFEF) 中++
    }
    return (中 / 1.4 + (s.length - 中) / 2.2).toInt() + 2
}

// ── 填卡：{{char}} / {{user}} 占位符替换（认不出的原样留着）────────
private val 占位表 = mapOf(
    "char" to "名字", "角色" to "名字", "角色名" to "名字", "name" to "名字",
    "user" to "用户", "用户" to "用户", "persona" to "用户", "用户名" to "用户",
    "description" to "简介", "desc" to "简介", "简介" to "简介",
    "personality" to "人设", "人设" to "人设",
    "greeting" to "开场白", "first_message" to "开场白", "开场白" to "开场白",
    "examples" to "示例对话", "example_dialogue" to "示例对话", "示例对话" to "示例对话",
    "scenario" to "场景", "场景" to "场景",
)
private val 占位正则 = Regex("""\{\{\s*([A-Za-z_\u4e00-\u9fff]+)\s*\}\}""")

fun 填卡(文: String?, 角色: 角色卡? = null, 用户: String = ""): String {
    if (文.isNullOrEmpty()) return 文 ?: ""
    val 值 = HashMap<String, String>()
    值["用户"] = 用户.trim()
    if (角色 != null) {
        值["名字"] = 角色.名字.trim(); 值["简介"] = 角色.简介.trim()
        值["人设"] = 角色.人设.trim(); 值["开场白"] = 角色.开场白.trim()
        值["示例对话"] = 角色.示例对话.trim()
    }
    return 占位正则.replace(文) { m ->
        val 键 = 占位表[m.groupValues[1].trim().lowercase()]
        if (键 == null || !值.containsKey(键)) m.value else 值[键]!!
    }
}

// ── 提示拼装 ────────────────────────────────────────────────────────
data class 聊天条(val role: String, val content: String)
data class 拼好的(val 系统: String, val 数组: List<聊天条>, val 要点: String)

const val 默认上下文条数 = 20
const val 默认字数上限 = 24000
const val 开场占位 = "……"
const val 本地默认上下文 = 4096
/** 会话预设里能覆盖的键（对齐 PC 版 `酒馆大脑.可覆盖参数`，**一模一样**）。 */
val 可覆盖参数 = listOf("temperature", "top_p", "max_tokens", "top_k", "repeat_penalty")

/** 本地扮演规矩（对齐 PC 版 `酒馆大脑.本地规矩`，**逐字一致**）。 */
fun 本地规矩(名字: String): String = """扮演规则（必须一直遵守）：
1. 始终以${名字}的身份和口吻说话，不要跳出角色。
2. 不要替用户写他说的话，也不要替用户做决定、描写用户的行为。
3. 不要自称 AI、语言模型、助手或程序。
4. 动作、神态、场景这类旁白，用圆括号包起来，比如（她转过头）或 *她转过头*；说的话直接写。
5. 按角色的性格和当前情景来回应，别写客套话，也别总结或复述前文。
6. 一次回复别太长，几句话把意思说到就行。"""

/**
 * 英文版扮演规矩（跟中文版逐条对应）。
 *
 * 给 llama / phi / gemma / mistral 这些**英文中心**的模型用 —— 它们对中文指令服从度差，
 * 经常不守规矩或者拿英文回你。用英文写规则，它才当回事。
 */
fun 本地规矩英(名字: String): String = """Roleplay rules (always follow):
1. Always speak as ${名字}, in character. Never break character.
2. Only write what ${名字} says and does. Never speak or decide for the user, and never describe the user's actions or thoughts.
3. Never call yourself an AI, a language model, an assistant or a program.
4. Put narration (actions, expressions, scenes) in parentheses, e.g. (she turns her head); write dialogue directly.
5. Respond naturally to the current scene. No pleasantries, and do not summarize or repeat earlier text.
6. Keep each reply short - a few sentences is enough."""

fun 设定对象(预设: String?): JsonObject =
    try { if (预设.isNullOrBlank()) JsonObject(emptyMap()) else (酒馆Json.parseToJsonElement(预设) as? JsonObject) ?: JsonObject(emptyMap()) }
    catch (e: Exception) { JsonObject(emptyMap()) }

fun 设定文本(设定: JsonObject, 键: String): String =
    (设定[键] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

fun 上下文设置(设定: JsonObject): Pair<Int, Int> {
    val 条 = (设定["上下文条数"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    val 上 = (设定["字数上限"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return Pair(maxOf(1, 条 ?: 默认上下文条数), maxOf(1, 上 ?: 默认字数上限))
}

fun 从尾部截(消息们: List<消息>, 字数上限: Int = 默认字数上限): List<消息> {
    val 攒 = ArrayList<消息>()
    var 总 = 0
    for (m in 消息们.asReversed()) {
        if (攒.isNotEmpty() && 总 + m.内容.length > 字数上限) break
        攒.add(m); 总 += m.内容.length
    }
    return 攒.asReversed()
}

fun 规整角色(提示: List<聊天条>): List<聊天条> {
    val 压 = ArrayList<聊天条>()
    for (m in 提示) {
        if (m.role != "user" && m.role != "assistant") continue
        val 文 = m.content.trim()
        if (文.isEmpty()) continue
        if (压.isNotEmpty() && 压.last().role == m.role) 压[压.size - 1] = 聊天条(m.role, 压.last().content + "\n\n" + 文)
        else 压.add(聊天条(m.role, 文))
    }
    if (压.isEmpty()) return listOf(聊天条("user", 开场占位))
    if (压.first().role != "user") 压.add(0, 聊天条("user", 开场占位))
    return 压
}

fun 本地协议(套: 接口配置?): Boolean = 套 != null && 套.协议 == "local"

/**
 * 系统块顺序：①身份 ②人设 ③示例 ④系统消息 ⑤会话系统提示 ⑤′记忆 ⑥开场白说明 ⑦本地规矩(仅 local)。
 */
fun 拼提示(
    角色: 角色卡?,
    消息们: List<消息>,
    设定: JsonObject,
    本地: Boolean,
    记忆: String = "",
    模型名: String = "",
): 拼好的 {
    val 系统块 = ArrayList<String>()
    val 名字 = 角色?.名字?.trim() ?: ""
    // 英文中心的族（llama/phi/gemma/mistral）→ 固定那几段"我们写的"文字走英文；
    // 角色卡本身的内容不动（那是用户自己写的）。
    val 英 = 本地 && 要英文(模型名)
    val 称呼 = 设定文本(设定, "用户称呼").ifEmpty { if (英) "you" else "你" }
    fun 填(t: String) = 填卡(t, 角色, 称呼)

    if (角色 != null) {
        val 简介 = 填(角色.简介.trim()); val 人设 = 填(角色.人设.trim())
        val 身份 = ArrayList<String>()
        身份.add(
            if (名字.isNotEmpty()) (if (英) "You are $名字." else "你就是$名字。")
            else (if (英) "You are playing a role." else "你正在扮演一个角色。"))
        if (简介.isNotEmpty()) 身份.add((if (英) "Character bio: " else "角色简介：") + 简介)
        系统块.add(身份.joinToString("\n"))
        if (人设.isNotEmpty()) 系统块.add(人设)
        val 示例 = 填(角色.示例对话.trim())
        if (示例.isNotEmpty()) 系统块.add(
            if (英)
                "Here are some speech examples for \"${名字.ifEmpty { "this character" }}\" " +
                    "(only to get a feel for the tone and word choice; do not copy them, and do not " +
                    "treat them as things that already happened):\n$示例"
            else
                "以下是「${名字.ifEmpty { "这个角色" }}」的说话示例（只用来体会语气和用词，" +
                    "不要照抄，也别当成已经发生过的对话）：\n$示例")
    }

    val 普通 = ArrayList<消息>()
    for (m in 消息们) {
        if (m.说话人 == "系统") { val v = 填(m.内容.trim()); if (v.isNotEmpty()) 系统块.add(v) }
        else 普通.add(m)
    }
    val 额外 = 填(设定文本(设定, "系统提示"))
    if (额外.isNotEmpty()) 系统块.add(额外)
    if (记忆.isNotBlank()) 系统块.add(记忆.trim())

    val 提示 = ArrayList<聊天条>()
    for (m in 普通) {
        if (m.说话人 == "用户") 提示.add(聊天条("user", m.内容))
        else if (m.说话人 == "角色") 提示.add(聊天条("assistant", m.内容))
    }
    if (提示.isNotEmpty() && 提示.first().role == "assistant") {
        val 头 = 提示.removeAt(0).content.trim()
        if (头.isNotEmpty()) 系统块.add(
            if (英) "You spoke first. You have already said this:\n$头"
            else "对话是由你先开口的。你已经说过这句：\n$头")
    }
    if (本地) 系统块.add(
        if (英) 本地规矩英(名字.ifEmpty { "the character you play" })
        else 本地规矩(名字.ifEmpty { "你扮演的角色" }))

    val 要点 = if (名字.isNotEmpty()) {
        val 简 = 填(角色?.简介?.trim() ?: "")
        (if (英) (if (简.isNotEmpty()) "You are $名字. $简" else "You are $名字.")
         else (if (简.isNotEmpty()) "你是$名字。$简" else "你是$名字。")).take(120)
    } else ""

    return 拼好的(系统块.joinToString("\n\n"), 规整角色(提示), 要点)
}

fun 本机窗口(套: 接口配置?): Int {
    if (!本地协议(套)) return 0
    val 窗口 = (设定对象(套!!.采样参数)["n_ctx"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    return if (窗口 > 0) 窗口 else 本地默认上下文
}

private fun 提示词数(拼: 拼好的): Int {
    var 词 = 估token(拼.系统)
    for (条 in 拼.数组) 词 += 估token(条.content)
    return 词
}

/** 裁到本机窗口内（云端不动）。返回：真正发的那几条 + 拼好的提示。 */
fun 窗口内(角色: 角色卡?, 候选: List<消息>, 设定: JsonObject, 套: 接口配置?, 记忆: String = ""):
    Pair<List<消息>, 拼好的> {
    val 尾巴 = 从尾部截(候选, 上下文设置(设定).second).toMutableList()
    val 窗口 = 本机窗口(套)
    val 本 = 本地协议(套)
    while (true) {
        val 拼 = 拼提示(角色, 尾巴, 设定, 本, 记忆, 套?.模型 ?: "")
        if (窗口 <= 0 || 尾巴.size <= 1) return Pair(尾巴, 拼)
        val 够吗 = 窗口 - 提示词数(拼) - maxOf(256, 窗口 / 4) - (窗口 * 0.06).toInt()
        if (够吗 >= 0) return Pair(尾巴, 拼)
        尾巴.removeAt(0)
    }
}

/** 接口配置的采样参数，被会话预设里的同名键覆盖。 */
fun 合并参数(配置: 接口配置, 设定: JsonObject): JsonObject {
    val 出 = LinkedHashMap<String, JsonElement>()
    出.putAll(设定对象(配置.采样参数))
    for (键 in 可覆盖参数) {
        val v = 设定[键]
        if (v != null && v !is JsonNull) 出[键] = v
    }
    return JsonObject(出)
}

/** 这套配置能不能发出去。返回缺什么（空 = 齐了）。 */
fun 校验配置(配置: 接口配置?): List<String> {
    if (配置 == null) return listOf("一套接口配置都没建")
    if (配置.协议 !in 协议们) return listOf("协议")
    if (配置.协议 == "local") return if (配置.模型.isBlank()) listOf("模型（GGUF 文件名）") else emptyList()
    val 缺 = ArrayList<String>()
    if (配置.地址.isBlank()) 缺.add("地址")
    if (配置.模型.isBlank()) 缺.add("模型")
    return 缺
}
