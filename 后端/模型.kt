package 后端

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 数据模型 —— 对齐 PC 版 `酒馆模型.py`。
 *
 * 时间一律 epoch 毫秒；JSON 列（采样参数/附加头/预设/元信息）按 PC 版一样用**字符串**存。
 * 字段名用 @SerialName 映射成 PC 的中文列名，保证与 PC 的 `酒馆数据/` 可互通。
 */

/** 全项目唯一的 Json 编解码器。 */
val 酒馆Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    isLenient = true
}

/** 说话人（对齐 `酒馆模型.说话人`）。 */
val 说话人们 = listOf("用户", "角色", "系统")

/** 接口协议（对齐 `酒馆模型.协议们`）。 */
val 协议们 = listOf("openai", "anthropic", "gemini", "local")

/** 接口用途（`""` = 老数据，两边都能用）。 */
val 用途们 = listOf("对话", "agent")

@Serializable
data class 角色卡(
    @SerialName("编号") val 编号: Int = 0,
    @SerialName("名字") val 名字: String = "",
    @SerialName("简介") val 简介: String = "",
    @SerialName("人设") val 人设: String = "",
    @SerialName("开场白") val 开场白: String = "",
    @SerialName("示例对话") val 示例对话: String = "",
    @SerialName("标签") val 标签: String = "",
    @SerialName("创建于") val 创建于: Long = 0,
    @SerialName("更新于") val 更新于: Long = 0,
)

@Serializable
data class 接口配置(
    @SerialName("编号") val 编号: Int = 0,
    @SerialName("名称") val 名称: String = "",
    @SerialName("协议") val 协议: String = "openai",
    @SerialName("地址") val 地址: String = "",
    @SerialName("密钥") val 密钥: String = "",
    @SerialName("模型") val 模型: String = "",
    @SerialName("用途") val 用途: String = "",
    @SerialName("工具格式") val 工具格式: String = "自动",
    @SerialName("采样参数") val 采样参数: String = "",
    @SerialName("附加头") val 附加头: String = "",
    @SerialName("创建于") val 创建于: Long = 0,
    @SerialName("更新于") val 更新于: Long = 0,
) {
    /** 归一化用途，认不出来回 `""`（对齐 `接口配置.归谁()`）。 */
    fun 归谁(): String = if (用途 in 用途们) 用途 else ""
}

@Serializable
data class 会话(
    @SerialName("编号") val 编号: Int = 0,
    @SerialName("角色编号") val 角色编号: Int = 0,
    @SerialName("标题") val 标题: String = "",
    @SerialName("接口编号") val 接口编号: Int = 0,
    @SerialName("预设") val 预设: String = "",
    @SerialName("创建于") val 创建于: Long = 0,
    @SerialName("更新于") val 更新于: Long = 0,
)

@Serializable
data class 消息(
    @SerialName("会话编号") val 会话编号: Int = 0,
    @SerialName("序号") val 序号: Int = 0,
    @SerialName("说话人") val 说话人: String = "用户",
    @SerialName("内容") val 内容: String = "",
    @SerialName("元信息") val 元信息: String = "",
    @SerialName("创建于") val 创建于: Long = 0,
)
