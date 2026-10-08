package 后端

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * 长期记忆 —— 对齐 PC 版 `酒馆记忆.py`。
 * 索引在 `记忆.json`（类型/键/回收水位），条目在 `记忆/<编号>.json`。
 * 条目形状：`{文, 开, 来源(手写/自动), 会话, 序号, 指纹}`。
 */

const val 记忆角色 = "角色"
const val 每条上限 = 2000

object 记忆 {

    fun 指纹(文: String?): String {
        val 净 = (文 ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val d = MessageDigest.getInstance("SHA-1").digest(净.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun 正条目(条: JsonObject): JsonObject {
        val 开值 = 条["开"]
        val 开 = if (开值 is JsonPrimitive) (开值.booleanOrNull ?: (开值.contentOrNull?.lowercase() in listOf("1", "true", "是"))) else false
        val 来 = (条["来源"] as? JsonPrimitive)?.contentOrNull ?: "手写"
        val 来源 = if (来 == "自动") "自动" else "手写"
        val 文 = (条["文"] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
        return buildJsonObject {
            put("文", 文); put("开", 开); put("来源", 来源)
            if (来源 == "自动") {
                put("会话", (条["会话"] as? JsonPrimitive)?.intOrNull ?: 0)
                put("序号", (条["序号"] as? JsonPrimitive)?.intOrNull ?: 0)
                put("指纹", (条["指纹"] as? JsonPrimitive)?.contentOrNull ?: 指纹(文))
            }
        }
    }

    private fun 找行(库: TavernStore, 类型: String, 键: String): JsonObject? =
        库.全都("记忆").firstOrNull {
            ((it["类型"] as? JsonPrimitive)?.contentOrNull == 类型) && ((it["键"] as? JsonPrimitive)?.contentOrNull == 键)
        }

    private fun 新行(库: TavernStore, 类型: String, 键: String): JsonObject {
        val 现在 = 毫秒()
        return buildJsonObject {
            put("编号", 库.发号("记忆")); put("类型", 类型); put("键", 键)
            put("创建于", 现在); put("更新于", 现在)
        }
    }

    fun 取(库: TavernStore, 类型: String, 键: String): List<JsonObject> {
        val 行 = 找行(库, 类型, 键) ?: return emptyList()
        val 编号 = (行["编号"] as? JsonPrimitive)?.intOrNull ?: return emptyList()
        return (库.取记忆们(编号) ?: emptyList()).map { 正条目(it) }
    }

    fun 存(库: TavernStore, 类型: String, 键: String, 条目们: List<JsonObject>): List<JsonObject> = synchronized(库) {
        val 干净 = 条目们.map { 正条目(it) }.filter { (it["文"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }
        干净.forEach {
            val 长 = (it["文"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
            if (长 > 每条上限) throw IllegalArgumentException("有一条记忆 $长 字，超过上限 $每条上限 字")
        }
        val 行 = 找行(库, 类型, 键) ?: 新行(库, 类型, 键)
        val 编号 = (行["编号"] as? JsonPrimitive)?.intOrNull!!
        库.存记忆们(编号, 干净)
        库.存("记忆", buildJsonObject { 行.forEach { (k, v) -> put(k, v) }; put("更新于", 毫秒()) })
        干净
    }

    fun 删(库: TavernStore, 类型: String, 键: String): Boolean = synchronized(库) {
        val 行 = 找行(库, 类型, 键) ?: return@synchronized false
        val 编号 = (行["编号"] as? JsonPrimitive)?.intOrNull ?: return@synchronized false
        库.删记忆们(编号); 库.删("记忆", 编号)
    }

    fun 取水位(库: TavernStore, 类型: String, 键: String): MutableMap<Int, Int> {
        val 行 = 找行(库, 类型, 键) ?: return mutableMapOf()
        val 原始 = (行["回收水位"] as? JsonPrimitive)?.contentOrNull ?: return mutableMapOf()
        return try {
            val o = 酒馆Json.parseToJsonElement(原始) as? JsonObject ?: return mutableMapOf()
            val 出 = mutableMapOf<Int, Int>()
            for ((k, v) in o) { val kk = k.toIntOrNull() ?: continue; val vv = (v as? JsonPrimitive)?.intOrNull ?: continue; 出[kk] = vv }
            出
        } catch (e: Exception) { mutableMapOf() }
    }

    /** 把一批自动候选并进记忆，返回真收了几条（一律 开=false）。 */
    fun 收待定(库: TavernStore, 类型: String, 键: String, 候选们: List<JsonObject>, 水位: Map<Int, Int>? = null): Int =
        synchronized(库) {
            val 干净 = 候选们.map { 正条目(it) }.filter { (it["文"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true }
            var 行 = 找行(库, 类型, 键)
            val 新挂 = 行 == null
            if (行 == null) 行 = 新行(库, 类型, 键)
            val 编号 = (行["编号"] as? JsonPrimitive)?.intOrNull!!
            val 现有 = if (新挂) mutableListOf() else (库.取记忆们(编号) ?: emptyList()).map { 正条目(it) }.toMutableList()
            var 收了 = 0
            for (候 in 干净) {
                val 候指纹 = (候["指纹"] as? JsonPrimitive)?.contentOrNull
                val 候会 = (候["会话"] as? JsonPrimitive)?.intOrNull ?: 0
                val 候序 = (候["序号"] as? JsonPrimitive)?.intOrNull ?: 0
                val 同名 = 现有.indices.filter {
                    (现有[it]["来源"] as? JsonPrimitive)?.contentOrNull == "自动" &&
                        ((现有[it]["会话"] as? JsonPrimitive)?.intOrNull ?: 0) == 候会 &&
                        ((现有[it]["序号"] as? JsonPrimitive)?.intOrNull ?: 0) == 候序
                }
                if (同名.any { (现有[it]["指纹"] as? JsonPrimitive)?.contentOrNull == 候指纹 }) continue
                var 换过 = false
                if (同名.size == 1) {
                    val 旧开 = (现有[同名[0]]["开"] as? JsonPrimitive)?.booleanOrNull ?: false
                    if (!旧开) { 现有[同名[0]] = 候; 换过 = true }
                }
                if (!换过) 现有.add(候)
                收了++
            }
            if (收了 > 0 || 水位 != null) {
                var 新行对象 = 行
                if (水位 != null) {
                    val 水 = buildJsonObject { 水位.forEach { (k, v) -> put(k.toString(), v) } }
                    新行对象 = buildJsonObject { 行.forEach { (k, v) -> put(k, v) }; put("回收水位", 水.toString()) }
                }
                库.存记忆们(编号, 现有)
                库.存("记忆", buildJsonObject { 新行对象.forEach { (k, v) -> put(k, v) }; put("更新于", 毫秒()) })
            }
            收了
        }

    fun 预算(窗口: Int, 上限: Int = 1200): Int = if (窗口 <= 0) maxOf(0, 上限) else minOf(上限, maxOf(200, 窗口 / 8))

    data class 渲染结果(val 文本: String, val 带了: Int, val 没带: Int, val token: Int)

    fun 渲染(条目们: List<JsonObject>, 上限: Int = 0, 标题: String = ""): 渲染结果 {
        val 条 = 条目们.filter {
            ((it["开"] as? JsonPrimitive)?.booleanOrNull ?: false) && !((it["文"] as? JsonPrimitive)?.contentOrNull?.trim().isNullOrEmpty())
        }
        if (条.isEmpty()) return 渲染结果("", 0, 0, 0)
        val 行 = ArrayList<String>(); var 共 = 0; var 没带 = 0
        for ((i, x) in 条.withIndex()) {
            val 块 = "- " + ((x["文"] as? JsonPrimitive)?.contentOrNull ?: "").trim().replace("\n", "\n  ")
            val 大 = 估token(块) + 1
            if (上限 > 0 && 行.isNotEmpty() && 共 + 大 > 上限) { 没带 = 条.size - i; break }
            行.add(块); 共 += 大
        }
        var 文本 = if (标题.isEmpty()) 行.joinToString("\n") else "$标题\n${行.joinToString("\n")}"
        if (没带 > 0) 文本 += "\n（还有 $没带 条这次没带上 —— 记忆太长，窗口装不下。）"
        return 渲染结果(文本, 行.size, 没带, 共)
    }

    fun 聊天块(条目们: List<JsonObject>, 上限: Int = 0): 渲染结果 = 渲染(
        条目们, 上限,
        标题 = "【长期记忆】下面是之前发生过、现在已经不在上面的对话里了的事。请当作你本来就知道，不要复述它们，也不要问\"你怎么知道的\"：",
    )
}
