package 后端

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 协议引擎 —— 对齐 PC 版 `酒馆大脑.py`。三家在线协议共用的 SSE 解析 + 各自建请求。 */

class 已打断 : Exception("已打断")
class 错配置(message: String) : Exception(message)
class 错模型(message: String) : Exception(message)

val 本地专属参数 = setOf("n_ctx", "n_gpu_layers")

/** 停止信号。`set()` 置旗并顺手取消在途的 HTTP 调用。 */
class 停旗 {
    private val 旗 = AtomicBoolean(false)
    @Volatile var 取消: (() -> Unit)? = null
    fun set() { 旗.set(true); try { 取消?.invoke() } catch (_: Exception) {} }
    val isSet: Boolean get() = 旗.get()
}

private val JSON类型 = "application/json".toMediaType()

class 规格(val 地址: String, val 头: Map<String, String>, val 体: JsonObject, val 取段: (JsonElement) -> String?)

object 聊天引擎 {

    private fun 客户() = 网络.客户(读秒 = 300)

    private fun 列模型客户() = 网络.客户(读秒 = 30)

    fun 头们(配置: 接口配置): LinkedHashMap<String, String> {
        val 头 = LinkedHashMap<String, String>()
        头["Content-Type"] = "application/json"
        if (配置.密钥.isNotEmpty()) {
            when (配置.协议) {
                "anthropic" -> 头["x-api-key"] = 配置.密钥
                "gemini" -> 头["x-goog-api-key"] = 配置.密钥
                else -> 头["Authorization"] = "Bearer ${配置.密钥}"
            }
        }
        if (配置.协议 == "anthropic") 头["anthropic-version"] = "2023-06-01"
        for ((k, v) in 设定对象(配置.附加头)) 头[k] = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
        return 头
    }

    fun 查头(头: Map<String, String>) {
        for ((名, 值) in 头) for ((i, 字) in 值.withIndex()) if (字.code > 255)
            throw 错配置("请求头「$名」第 ${i + 1} 个字符是「$字」—— HTTP 头只认 latin-1，装不下中文。")
    }

    private fun 取openai(载: JsonElement): String? = try {
        载.jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")
            ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) { null }

    private fun 取anthropic(载: JsonElement): String? = try {
        val o = 载.jsonObject
        if (o["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta")
            o["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull else null
    } catch (e: Exception) { null }

    private fun 取gemini(载: JsonElement): String? = try {
        载.jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) { null }

    private fun 建请求(配置: 接口配置, 系统: String, 消息们: List<聊天条>, 设定: JsonObject): 规格 {
        val 参数 = 合并参数(配置, 设定).toMutableMap()
        for (k in 本地专属参数) 参数.remove(k)
        val 地址 = 配置.地址.trim().trimEnd('/')
        val 头 = 头们(配置)
        return when (配置.协议) {
            "openai" -> {
                val 全部 = ArrayList<JsonElement>()
                if (系统.isNotEmpty()) 全部.add(buildJsonObject { put("role", "system"); put("content", 系统) })
                for (m in 消息们) 全部.add(buildJsonObject { put("role", m.role); put("content", m.content) })
                val 体 = buildJsonObject {
                    put("model", 配置.模型); put("messages", JsonArray(全部)); put("stream", true)
                    for ((k, v) in 参数) put(k, v)
                }
                规格("$地址/chat/completions", 头, 体, ::取openai)
            }
            "anthropic" -> {
                val 最大 = (参数.remove("max_tokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 4096
                val 全部 = 消息们.map { buildJsonObject { put("role", it.role); put("content", it.content) } }
                val 体 = buildJsonObject {
                    put("model", 配置.模型); put("messages", JsonArray(全部)); put("stream", true)
                    put("max_tokens", 最大)
                    if (系统.isNotEmpty()) put("system", 系统)
                    for ((k, v) in 参数) put(k, v)
                }
                规格("$地址/messages", 头, 体, ::取anthropic)
            }
            "gemini" -> {
                val 配置段 = LinkedHashMap<String, JsonElement>()
                参数.remove("temperature")?.let { 配置段["temperature"] = it }
                参数.remove("top_p")?.let { 配置段["topP"] = it }
                参数.remove("max_tokens")?.let { 配置段["maxOutputTokens"] = it }
                val 内容 = 消息们.map { m ->
                    buildJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", JsonArray(listOf(buildJsonObject { put("text", m.content) })))
                    }
                }
                val 体 = buildJsonObject {
                    put("contents", JsonArray(内容)); put("generationConfig", JsonObject(配置段))
                    if (系统.isNotEmpty()) put("systemInstruction", buildJsonObject {
                        put("parts", JsonArray(listOf(buildJsonObject { put("text", 系统) })))
                    })
                }
                规格("$地址/models/${配置.模型}:streamGenerateContent?alt=sse", 头, 体, ::取gemini)
            }
            else -> throw 错配置("HTTP 只认 openai / anthropic / gemini，这套是「${配置.协议}」")
        }
    }

    /** 跑一次生成，逐段吐字。旗置了抛 [已打断]。 */
    fun 流式(配置: 接口配置, 系统: String, 消息们: List<聊天条>, 设定: JsonObject, 旗: 停旗): Flow<String> = flow {
        val 缺 = 校验配置(配置)
        if (缺.isNotEmpty()) throw 错配置("这套接口配置发不出去，缺：${缺.joinToString("、")}")
        val 规格 = 建请求(配置, 系统, 消息们, 设定)
        查头(规格.头)

        val 请求 = Request.Builder().url(规格.地址)
            .apply { 规格.头.forEach { (k, v) -> header(k, v) } }
            .post(规格.体.toString().toRequestBody(JSON类型)).build()
        val 调用 = 客户().newCall(请求)
        旗.取消 = { 调用.cancel() }
        try {
            val 响应 = try { 调用.execute() } catch (e: Exception) {
                if (旗.isSet) throw 已打断()
                throw 错模型("连不上 ${规格.地址}：${e.message}")
            }
            响应.use { 应 ->
                if (应.code != 200) {
                    val 身 = try { 应.body?.string()?.trim()?.take(800) ?: "" } catch (_: Exception) { "" }
                    throw 错模型("HTTP ${应.code} ${应.message}\n$身")
                }
                val 源 = 应.body?.source() ?: throw 错模型("响应体是空的")
                val 缓 = ByteArrayOutputStream()
                try {
                    while (true) {
                        if (旗.isSet) throw 已打断()
                        val b = 源.readByte().toInt() and 0xFF
                        缓.write(b)
                        if (b == 0x0A) {
                            val 行 = String(缓.toByteArray(), Charsets.UTF_8).trim()
                            缓.reset()
                            if (!行.startsWith("data:")) continue
                            val 正文 = 行.substring(5).trim()
                            if (正文 == "[DONE]") return@use
                            if (正文.isEmpty()) continue
                            val 帧 = try { 酒馆Json.parseToJsonElement(正文) } catch (_: Exception) { continue }
                            val 段 = 规格.取段(帧)
                            if (!段.isNullOrEmpty()) emit(段)
                        }
                    }
                } catch (_: EOFException) {
                } catch (e: Exception) {
                    if (旗.isSet) throw 已打断()
                    throw 错模型("读流断了：${e.message}")
                }
            }
        } finally { 旗.取消 = null }
    }.flowOn(Dispatchers.IO)

    private fun 站根(地址: String): String {
        val 起 = 地址.indexOf("://").let { if (it >= 0) it + 3 else 0 }
        val 斜 = 地址.indexOf('/', 起)
        return if (斜 >= 0) 地址.substring(0, 斜) else 地址
    }

    /** 问这套接口有哪些模型（「发现模型」）。别在主线程调。 */
    fun 列模型(配置: 接口配置): List<String> {
        val 协议 = 配置.协议.trim()
        if (协议 !in 协议们) throw 错配置("协议得是 ${协议们.joinToString("、")} 里的一个")
        if (协议 == "local") throw 错配置("本地模型的清单在「本地模型」页里。")
        val 地址 = 配置.地址.trim()
        if (地址.isEmpty()) throw 错配置("地址还空着。")
        val 头 = 头们(配置); 查头(头)

        val 基 = 地址.trimEnd('/')
        val 候选 = mutableListOf("$基/models")
        val 根 = 站根(基).trimEnd('/')
        if (根 != 基) 候选.add("$根/models")

        for ((i, 网) in 候选.withIndex()) {
            val 请求 = Request.Builder().url(网).apply { 头.forEach { (k, v) -> header(k, v) } }.get().build()
            val 应 = try { 列模型客户().newCall(请求).execute() } catch (e: Exception) { throw 错模型("连不上 $网：${e.message}") }
            应.use { r ->
                if (r.code == 404 && i + 1 < 候选.size) return@use
                if (r.code == 404) throw 错模型("$网 回了 404 —— 没有模型清单接口。不影响聊天，模型名手打就行。")
                if (r.code != 200) throw 错模型("HTTP ${r.code} ${r.message}")
                val 文 = r.body?.string() ?: "{}"
                val 对 = try { 酒馆Json.parseToJsonElement(文).jsonObject } catch (e: Exception) { throw 错模型("$网 回来的不是 JSON") }
                val 名们 = if (协议 == "gemini") {
                    (对["models"] as? JsonArray)?.mapNotNull { 条 ->
                        val o = 条 as? JsonObject ?: return@mapNotNull null
                        val 会 = (o["supportedGenerationMethods"] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull }
                        if (会 != null && 会.isNotEmpty() && !会.contains("generateContent")) return@mapNotNull null
                        var 名 = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        if (名.startsWith("models/")) 名 = 名.removePrefix("models/")
                        名.ifEmpty { null }
                    } ?: emptyList()
                } else {
                    (对["data"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull } ?: emptyList()
                }
                if (名们.isEmpty()) throw 错模型("$网 通了，但没列出模型。")
                return 名们.distinct().sortedBy { it.lowercase() }
            }
        }
        throw 错模型("没问出清单来。")
    }
}
