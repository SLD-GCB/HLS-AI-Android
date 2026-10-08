package 后端

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** 数据类 ↔ 表里的 JSON 行。 */
fun <T> T.到行(s: KSerializer<T>): JsonObject = 酒馆Json.encodeToJsonElement(s, this).jsonObject
fun <T> JsonObject.从行(s: KSerializer<T>): T = 酒馆Json.decodeFromJsonElement(s, this)

// ══ 角色（对齐 `酒馆角色.py`）═════════════════════════════════════
object 角色库 {
    private const val 表 = "角色卡"

    fun 建(库: TavernStore, 名字: String, 简介: String = "", 人设: String = "",
           开场白: String = "", 示例对话: String = "", 标签: String = ""): 角色卡 {
        val 名 = 名字.trim()
        if (名.isEmpty()) throw 酒馆错误("角色得有个名字")
        val 现在 = 毫秒()
        val 卡 = 角色卡(编号 = 库.发号(表), 名字 = 名, 简介 = 简介, 人设 = 人设,
            开场白 = 开场白, 示例对话 = 示例对话, 标签 = 标签, 创建于 = 现在, 更新于 = 现在)
        库.存(表, 卡.到行(角色卡.serializer()))
        return 卡
    }

    fun 取(库: TavernStore, 编号: Int): 角色卡? = 库.取(表, 编号)?.从行(角色卡.serializer())
    fun 列(库: TavernStore): List<角色卡> = 库.全都(表).map { it.从行(角色卡.serializer()) }

    fun 改(库: TavernStore, 编号: Int, 变换: (角色卡) -> 角色卡): Boolean = synchronized(库) {
        val 现 = 取(库, 编号) ?: return@synchronized false
        val 新 = 变换(现)
        if (新.名字.trim().isEmpty()) throw 酒馆错误("角色名字不能改成空的")
        库.存(表, 新.copy(编号 = 编号, 创建于 = 现.创建于, 更新于 = 毫秒()).到行(角色卡.serializer()))
        true
    }

    /** 删角色连带会话、消息、头像。 */
    fun 删(库: TavernStore, 编号: Int): Boolean = synchronized(库) {
        val 会话号 = 会话库.列会话(库).filter { it.角色编号 == 编号 }.map { it.编号 }
        for (n in 会话号) 库.删消息们(n)
        for (n in 会话号) 库.删("会话", n)
        库.删头像(编号)
        库.删背景(编号)
        库.删(表, 编号)
    }
}

// ══ 会话 / 消息（对齐 `酒馆对话.py`）══════════════════════════════
object 会话库 {
    private const val 表 = "会话"

    fun 建会话(库: TavernStore, 角色编号: Int, 标题: String = "", 接口编号: Int = 0, 预设: String = ""): 会话 {
        val 现在 = 毫秒()
        val 段 = 会话(编号 = 库.发号(表), 角色编号 = 角色编号, 标题 = 标题,
            接口编号 = 接口编号, 预设 = 预设, 创建于 = 现在, 更新于 = 现在)
        库.存(表, 段.到行(会话.serializer()))
        return 段
    }

    fun 取会话(库: TavernStore, 编号: Int): 会话? = 库.取(表, 编号)?.从行(会话.serializer())

    fun 列会话(库: TavernStore, 角色编号: Int? = null): List<会话> =
        库.全都(表).map { it.从行(会话.serializer()) }
            .filter { 角色编号 == null || it.角色编号 == 角色编号 }
            .sortedByDescending { it.更新于 }

    fun 改标题(库: TavernStore, 会话编号: Int, 标题: String): Boolean = synchronized(库) {
        val 段 = 取会话(库, 会话编号) ?: return@synchronized false
        库.存(表, 段.copy(标题 = 标题, 更新于 = 毫秒()).到行(会话.serializer()))
        true
    }

    fun 改会话(库: TavernStore, 编号: Int, 变换: (会话) -> 会话): Boolean = synchronized(库) {
        val 段 = 取会话(库, 编号) ?: return@synchronized false
        库.存(表, 变换(段).copy(编号 = 编号, 创建于 = 段.创建于, 更新于 = 毫秒()).到行(会话.serializer()))
        true
    }

    fun 删会话(库: TavernStore, 编号: Int): Boolean = synchronized(库) {
        库.删消息们(编号)
        库.删(表, 编号)
    }

    private fun 查说话人(s: String): String {
        val v = s.trim()
        if (v !in 说话人们) throw 酒馆错误("说话人只能是 ${说话人们.joinToString("、")}")
        return v
    }

    private fun 序号of(行: JsonObject): Int = (行["序号"]?.toString()?.trim('"')?.toIntOrNull()) ?: 0

    /** 追加一条（用户发送 / 落开场白 / 占位都走它）。 */
    fun 追加消息(库: TavernStore, 会话编号: Int, 说话人: String, 内容: String, 元信息: String = ""): 消息 =
        synchronized(库) {
            val 人 = 查说话人(说话人)
            val 行们 = 库.消息们(会话编号).toMutableList()
            val 序号 = if (行们.isEmpty()) 1 else 序号of(行们.last()) + 1
            val 条 = 消息(会话编号, 序号, 人, 内容, 元信息, 毫秒())
            行们.add(条.到行(消息.serializer()))
            库.存消息们(会话编号, 行们)
            改会话(库, 会话编号) { it }
            条
        }

    /** 覆盖同序号（重说 / 生成结束落库走它）。 */
    fun 覆盖消息(库: TavernStore, 会话编号: Int, 序号: Int, 说话人: String, 内容: String, 元信息: String = ""): 消息 =
        synchronized(库) {
            val 人 = 查说话人(说话人)
            val 行们 = 库.消息们(会话编号).toMutableList()
            var 原时间 = 毫秒()
            var 换过 = false
            for (i in 行们.indices) {
                if (序号of(行们[i]) == 序号) {
                    原时间 = (行们[i]["创建于"]?.toString()?.trim('"')?.toLongOrNull()) ?: 原时间
                    换过 = true
                    break
                }
            }
            val 条 = 消息(会话编号, 序号, 人, 内容, 元信息, 原时间)
            if (换过) 行们.replaceAll { if (序号of(it) == 序号) 条.到行(消息.serializer()) else it }
            else 行们.add(条.到行(消息.serializer()))
            库.存消息们(会话编号, 行们)
            改会话(库, 会话编号) { it }
            条
        }

    fun 删消息(库: TavernStore, 会话编号: Int, 序号: Int): Boolean = synchronized(库) {
        val 行们 = 库.消息们(会话编号)
        val 留 = 行们.filter { 序号of(it) != 序号 }
        if (留.size == 行们.size) return@synchronized false
        库.存消息们(会话编号, 留)
        改会话(库, 会话编号) { it }
        true
    }

    fun 取消息(库: TavernStore, 会话编号: Int, 上限: Int? = null): List<消息> {
        var 行们 = 库.消息们(会话编号)
        if (上限 != null) 行们 = 行们.takeLast(maxOf(0, 上限))
        return 行们.map { it.从行(消息.serializer()) }
    }
}

// ══ 接口配置（对齐 `酒馆接口.py`）══════════════════════════════════
object 接口库 {
    private const val 表 = "接口配置"

    fun 建(库: TavernStore, 名称: String, 协议: String = "openai", 地址: String = "",
           密钥: String = "", 模型: String = "", 采样参数: String = "", 附加头: String = ""): 接口配置 {
        val 名 = 名称.trim()
        if (名.isEmpty()) throw 酒馆错误("接口配置得有个名字")
        if (协议 !in 协议们) throw 酒馆错误("协议只认 ${协议们.joinToString("、")}")
        val 现在 = 毫秒()
        val 套 = 接口配置(编号 = 库.发号(表), 名称 = 名, 协议 = 协议, 地址 = 地址, 密钥 = 密钥,
            模型 = 模型, 用途 = "对话", 采样参数 = 采样参数, 附加头 = 附加头, 创建于 = 现在, 更新于 = 现在)
        // 落盘时把密钥/附加头加密（硬件密钥库），返回给调用方的仍是明文。
        库.存(表, 加密套(套).到行(接口配置.serializer()))
        return 套
    }

    /** 落盘前加密敏感字段（幂等：已是密文的不会重复加密）。 */
    private fun 加密套(套: 接口配置) = 套.copy(
        密钥 = 密钥保管.加密(套.密钥),
        附加头 = 密钥保管.加密(套.附加头),
    )

    /** 读出后解密敏感字段（明文原样返回，兼容从 PC 拷来的文件）。 */
    private fun 解密套(套: 接口配置) = 套.copy(
        密钥 = 密钥保管.解密(套.密钥),
        附加头 = 密钥保管.解密(套.附加头),
    )

    fun 取(库: TavernStore, 编号: Int): 接口配置? =
        库.取(表, 编号)?.从行(接口配置.serializer())?.let { 解密套(it) }

    fun 列(库: TavernStore): List<接口配置> =
        库.全都(表).map { 行 -> 解密套(行.从行(接口配置.serializer())) }

    fun 改(库: TavernStore, 编号: Int, 变换: (接口配置) -> 接口配置): Boolean = synchronized(库) {
        val 现 = 取(库, 编号) ?: return@synchronized false
        val 新 = 变换(现)
        if (新.名称.trim().isEmpty()) throw 酒馆错误("名字不能改成空的")
        val 要写 = 新.copy(编号 = 编号, 创建于 = 现.创建于, 更新于 = 毫秒())
        库.存(表, 加密套(要写).到行(接口配置.serializer()))
        true
    }

    /**
     * 把历史遗留的**明文**密钥/附加头一次性升级成密文（启动时调一次）。
     * 已是密文的跳过；不是密文的才重写。返回升级了几条。
     */
    fun 升级明文(库: TavernStore): Int = synchronized(库) {
        var 改过 = 0
        库.全都(表).forEach { 行 ->
            val 原 = 行.从行(接口配置.serializer())
            val 新 = 加密套(原)
            if (新.密钥 != 原.密钥 || 新.附加头 != 原.附加头) {
                库.存(表, 新.到行(接口配置.serializer()))
                改过++
            }
        }
        改过
    }

    fun 删(库: TavernStore, 编号: Int): Boolean = 库.删(表, 编号)

    fun 默认(库: TavernStore): 接口配置? =
        列(库).firstOrNull { it.归谁() == "对话" || it.归谁() == "" }

    fun 取或默认(库: TavernStore, 编号: Int): 接口配置? {
        if (编号 != 0) {
            val 套 = 取(库, 编号)
            if (套 != null && (套.归谁() == "对话" || 套.归谁() == "")) return 套
        }
        return 默认(库)
    }
}
