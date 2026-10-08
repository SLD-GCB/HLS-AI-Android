package 后端

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 读写这一层出的岔子。 */
class 酒馆错误(message: String) : Exception(message)

/** 全项目唯一的取时间处：epoch 毫秒。 */
fun 毫秒(): Long = System.currentTimeMillis()

/**
 * 存储出口 —— **落地是本机 JSON 文件**，结构与 PC 版 `酒馆数据/` 一致。
 *
 * ```
 * 酒馆数据/
 *   配置/                 ← 所有"配置类"文件都归这里
 *     角色卡.json         {"<编号>": {每一列}}
 *     接口配置.json       {"<编号>": {每一列}}
 *     会话.json           {"<编号>": {每一列}}
 *     记忆.json           {"<编号>": {类型/键/回收水位}}   ← 只放索引
 *     代理.json           零散设置
 *   模型/                 ← GGUF 模型文件（见 `模型文件`）
 *   头像/<角色编号>.json   {"类型": "...", "图": "<base64>"}
 *   背景/<角色编号>.json
 *   消息/<会话编号>.json   [{每一列}, …]                   ← 按 `序号` 升序
 *   记忆/<记忆编号>.json   [{一条记忆}, …]
 * ```
 *
 * 关键保证：**写是原子的**（先写 `.tmp` 再改名）；**坏文件不让程序起不来**（读不出来当空的）；
 * 需要「读-改-写」整段原子时，调用方用 `synchronized(库) { ... }`。
 */
class TavernStore(val 目录: File) {

    private val 小表 = HashMap<String, LinkedHashMap<String, JsonObject>>()
    private var 开过 = false

    companion object {
        val 小表名 = listOf("角色卡", "接口配置", "会话", "记忆")
        val 主键列 = mapOf("角色卡" to "编号", "接口配置" to "编号", "会话" to "编号", "记忆" to "编号")

        /** `酒馆数据/` 默认位置：App 私有目录下。 */
        fun 默认目录(filesDir: File): File = File(filesDir, "酒馆数据")
    }

    @Synchronized
    fun 开(): TavernStore {
        if (开过) return this
        目录.mkdirs()
        配置目录().mkdirs()
        迁旧配置()          // 老版本把配置散在根目录，顺手挪进 配置/
        头像目录().mkdirs()
        背景目录().mkdirs()
        消息目录().mkdirs()
        记忆目录().mkdirs()
        开过 = true
        for (名 in 小表名) 装(名)
        return this
    }

    /** 把散在数据根目录的老配置挪进 `配置/`（只挪一次；已在 配置/ 里的不动）。 */
    private fun 迁旧配置() {
        val 老名字 = 小表名.map { "$it.json" } + listOf("代理.json", "主题.json", "设置.json")
        for (名 in 老名字) {
            val 旧 = File(目录, 名)
            val 新 = File(配置目录(), 名)
            if (旧.isFile && !新.exists()) {
                if (!旧.renameTo(新)) { try { 旧.copyTo(新, overwrite = true); 旧.delete() } catch (_: Exception) {} }
            }
        }
    }

    @Synchronized
    fun 关() { 小表.clear(); 开过 = false }

    fun 说明(): String = 目录.absolutePath

    /** 配置类文件都归这里（对齐「/配置」）。 */
    fun 配置目录() = File(目录, "配置")
    private fun 表路径(名: String) = File(配置目录(), "$名.json")
    fun 头像目录() = File(目录, "头像")
    fun 背景目录() = File(目录, "背景")
    fun 消息目录() = File(目录, "消息")
    fun 记忆目录() = File(目录, "记忆")
    private fun 头像路径(编号: Int) = File(头像目录(), "$编号.json")
    private fun 背景路径(编号: Int) = File(背景目录(), "$编号.json")
    private fun 消息路径(编号: Int) = File(消息目录(), "$编号.json")
    private fun 记忆路径(编号: Int) = File(记忆目录(), "$编号.json")

    private fun 认表(名: String): String {
        if (名 !in 小表名) throw 酒馆错误("不认识的表：$名")
        return 名
    }

    private fun 要开() { if (!开过) throw 酒馆错误("库还没开。先 `开()`。") }

    private fun 读(路径: File): String? =
        try { if (路径.isFile) 路径.readText(Charsets.UTF_8) else null } catch (e: Exception) { null }

    private fun 写(路径: File, 文本: String) {
        路径.parentFile?.mkdirs()
        val 临时 = File(路径.parentFile, 路径.name + ".tmp")
        临时.writeText(文本, Charsets.UTF_8)
        try {
            Files.move(临时.toPath(), 路径.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(临时.toPath(), 路径.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun 解析对象(文本: String?): JsonObject? =
        try { if (文本.isNullOrBlank()) null else 酒馆Json.parseToJsonElement(文本) as? JsonObject } catch (e: Exception) { null }

    private fun 解析数组(文本: String?): JsonArray? =
        try { if (文本.isNullOrBlank()) null else 酒馆Json.parseToJsonElement(文本) as? JsonArray } catch (e: Exception) { null }

    private fun 装(名: String): LinkedHashMap<String, JsonObject> {
        val 原始 = 解析对象(读(表路径(名))) ?: JsonObject(emptyMap())
        val 表 = LinkedHashMap<String, JsonObject>()
        for ((k, v) in 原始) (v as? JsonObject)?.let { 表[k] = it }
        小表[名] = 表
        return 表
    }

    private fun 拿(名: String): LinkedHashMap<String, JsonObject> { 要开(); 认表(名); return 小表[名] ?: 装(名) }

    @Synchronized
    fun 全都(名: String): List<JsonObject> {
        val 表 = 拿(名)
        val 键们 = try { 表.keys.sortedBy { it.toInt() } } catch (e: NumberFormatException) { 表.keys.sorted() }
        return 键们.map { 表[it]!! }
    }

    @Synchronized
    fun 取(名: String, 键: Any): JsonObject? = 拿(名)[键.toString()]

    @Synchronized
    fun 存(名: String, 行: JsonObject): JsonObject {
        认表(名)
        val 表 = 拿(名)
        val 键列 = 主键列[名]!!
        val 值 = (行[键列] as? JsonPrimitive)?.content ?: throw 酒馆错误("存 $名 的行没有主键 `$键列`")
        表[值] = 行
        写(表路径(名), 酒馆Json.encodeToString(JsonObject.serializer(), JsonObject(表)))
        return 行
    }

    @Synchronized
    fun 删(名: String, 键: Any): Boolean {
        认表(名)
        val 表 = 拿(名)
        if (表.remove(键.toString()) == null) return false
        写(表路径(名), 酒馆Json.encodeToString(JsonObject.serializer(), JsonObject(表)))
        return true
    }

    @Synchronized
    fun 发号(名: String): Int {
        var 最大 = 0
        for (k in 拿(名).keys) 最大 = maxOf(最大, k.toIntOrNull() ?: 0)
        return 最大 + 1
    }

    // ── 消息 ────────────────────────────────────────────────────────

    @Synchronized
    fun 消息们(会话编号: Int): List<JsonObject> {
        要开()
        val 数组 = 解析数组(读(消息路径(会话编号))) ?: return emptyList()
        return 数组.mapNotNull { it as? JsonObject }.sortedBy { (it["序号"] as? JsonPrimitive)?.intOrNull ?: 0 }
    }

    @Synchronized
    fun 存消息们(会话编号: Int, 行们: List<JsonObject>): Int {
        要开()
        val 排好 = 行们.sortedBy { (it["序号"] as? JsonPrimitive)?.intOrNull ?: 0 }
        写(消息路径(会话编号), 酒馆Json.encodeToString(JsonArray.serializer(), JsonArray(排好)))
        return 排好.size
    }

    @Synchronized
    fun 删消息们(会话编号: Int): Int {
        要开()
        val 条 = 消息们(会话编号).size
        val 路径 = 消息路径(会话编号)
        if (路径.isFile) 路径.delete()
        return 条
    }

    // ── 头像 ────────────────────────────────────────────────────────

    @Synchronized
    fun 存头像(角色编号: Int, 图: ByteArray, 类型: String = "image/png") {
        要开()
        val b64 = android.util.Base64.encodeToString(图, android.util.Base64.NO_WRAP)
        写(头像路径(角色编号), 酒馆Json.encodeToString(JsonObject.serializer(),
            JsonObject(mapOf("类型" to JsonPrimitive(类型), "图" to JsonPrimitive(b64)))))
    }

    @Synchronized
    fun 取头像(角色编号: Int): Pair<ByteArray, String>? {
        要开()
        val 对象 = 解析对象(读(头像路径(角色编号))) ?: return null
        val b64 = (对象["图"] as? JsonPrimitive)?.content ?: return null
        if (b64.isBlank()) return null
        return try {
            Pair(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), (对象["类型"] as? JsonPrimitive)?.content ?: "")
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun 删头像(角色编号: Int): Boolean {
        要开()
        val 路径 = 头像路径(角色编号)
        return if (路径.isFile) 路径.delete() else false
    }

    // ── 聊天背景（一个角色一个文件，base64）─────────────────────────

    @Synchronized
    fun 存背景(角色编号: Int, 图: ByteArray, 类型: String = "image/jpeg") {
        要开()
        val b64 = android.util.Base64.encodeToString(图, android.util.Base64.NO_WRAP)
        写(背景路径(角色编号), 酒馆Json.encodeToString(JsonObject.serializer(),
            JsonObject(mapOf("类型" to JsonPrimitive(类型), "图" to JsonPrimitive(b64)))))
    }

    @Synchronized
    fun 取背景(角色编号: Int): Pair<ByteArray, String>? {
        要开()
        val 对象 = 解析对象(读(背景路径(角色编号))) ?: return null
        val b64 = (对象["图"] as? JsonPrimitive)?.content ?: return null
        if (b64.isBlank()) return null
        return try {
            Pair(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), (对象["类型"] as? JsonPrimitive)?.content ?: "")
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun 删背景(角色编号: Int): Boolean {
        要开()
        val 路径 = 背景路径(角色编号)
        return if (路径.isFile) 路径.delete() else false
    }

    // ── 记忆条目 ────────────────────────────────────────────────────

    @Synchronized
    fun 存记忆们(编号: Int, 条目们: List<JsonObject>) {
        要开()
        写(记忆路径(编号), 酒馆Json.encodeToString(JsonArray.serializer(), JsonArray(条目们)))
    }

    @Synchronized
    fun 取记忆们(编号: Int): List<JsonObject>? {
        要开()
        val 文本 = 读(记忆路径(编号)) ?: return null
        return (解析数组(文本) ?: return emptyList()).mapNotNull { it as? JsonObject }
    }

    @Synchronized
    fun 删记忆们(编号: Int): Boolean {
        要开()
        val 路径 = 记忆路径(编号)
        if (!路径.isFile) return true
        return 路径.delete()
    }

    // ── 零散设置（一个名字一个小文件，如 `代理.json`）──────────────────

    private fun 设置路径(名: String) = File(配置目录(), "$名.json")

    @Synchronized
    fun 读设置(名: String): JsonObject? = try { 解析对象(读(设置路径(名))) } catch (e: Exception) { null }

    @Synchronized
    fun 写设置(名: String, 对象: JsonObject) {
        目录.mkdirs()
        写(设置路径(名), 酒馆Json.encodeToString(JsonObject.serializer(), 对象))
    }
}
