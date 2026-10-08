package 后端

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 代理设置 —— 填一个 HTTP 代理（主机:端口），在线 API、模型列表、模型下载都走它。
 *
 * 典型用法：电脑上开着 Clash（默认 7890），手机和电脑同一 WiFi、Clash 开了「允许局域网」，
 * 手机上就填 `电脑IP:7890`，于是**手机也能连 HuggingFace**。
 */
object 代理设置 {
    @Volatile var 启用 = false
    @Volatile var 主机 = "127.0.0.1"
    @Volatile var 端口 = 7890

    fun 载入(库: TavernStore) {
        val o = 库.读设置("代理") ?: return
        启用 = (o["启用"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
        主机 = (o["主机"] as? JsonPrimitive)?.contentOrNull ?: "127.0.0.1"
        端口 = (o["端口"] as? JsonPrimitive)?.intOrNull ?: 7890
    }

    fun 保存(库: TavernStore) {
        库.写设置("代理", buildJsonObject {
            put("启用", 启用); put("主机", 主机); put("端口", 端口)
        })
    }

    fun 说明(): String = if (启用) "$主机:$端口" else "未启用"
}

/** 全局 OkHttp 客户端工厂（代理变了会自动重建）。 */
object 网络 {

    /**
     * 这些域名**直连就通**，开代理时也别走代理（走代理反而连不上）。
     * 只有 HuggingFace 官方那类才需要代理。
     */
    private val 直连域名 = listOf("modelscope.cn", "aifasthub.com", "hf-mirror.com", "localhost", "127.0.0.1")

    /** 按域名决定走不走代理：国内源直连，其余走代理。 */
    private class 择代理(主: String, 口: Int) : ProxySelector() {
        private val 代理 = Proxy(Proxy.Type.HTTP, InetSocketAddress(主, 口))
        override fun select(uri: URI): MutableList<Proxy> {
            val 名 = uri.host ?: ""
            val 直 = 直连域名.any { 名 == it || 名.endsWith(".$it") }
            return mutableListOf(if (直) Proxy.NO_PROXY else 代理)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    @Volatile private var 上次键 = ""
    @Volatile private var 缓: OkHttpClient? = null

    @Synchronized
    fun 客户(读秒: Long = 300, 连秒: Long = 15): OkHttpClient {
        val 键 = "${代理设置.启用}|${代理设置.主机}|${代理设置.端口}|$读秒|$连秒"
        缓?.let { if (上次键 == 键) return it }
        val b = OkHttpClient.Builder()
            .connectTimeout(连秒, TimeUnit.SECONDS)
            .readTimeout(读秒, TimeUnit.SECONDS)
            .followRedirects(true)
        if (代理设置.启用) {
            try {
                b.proxySelector(择代理(代理设置.主机, 代理设置.端口))
            } catch (_: Exception) { }
        }
        val c = b.build()
        缓 = c; 上次键 = 键
        return c
    }
}
