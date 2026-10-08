package 后端

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 全局下载管家 —— 下载**不再绑在某个页面的 ViewModel 上**。
 *
 * 以前下载跑在「本地模型」页的 viewModelScope 里，你一离开那页，任务就被取消。
 * 现在放到这个单例里，离开页面照下不误，「模型管理」页也能看进度、能取消。
 *
 * 一次只下一个（保持简单）；状态用 Compose 的 mutableStateOf 暴露，界面直接读。
 */
object 下载管家 {

    private val 范围 = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正在下载的文件名；没有任务时为 null。 */
    var 名 by mutableStateOf<String?>(null); private set
    var 仓库 by mutableStateOf(""); private set
    var 已 by mutableStateOf(0L); private set
    var 总 by mutableStateOf(0L); private set
    var 进行中 by mutableStateOf(false); private set

    /** 最近一条说明（下载中 / 下好了 / 失败原因），给界面显示。 */
    var 说明 by mutableStateOf(""); private set

    private var 任务: Job? = null
    @Volatile private var 旗 = false

    /** 有总长度就显示百分比，没有就显示不确定进度条。 */
    val 定进度: Boolean get() = 总 > 0
    val 比例: Float get() = if (总 > 0) (已.toFloat() / 总).coerceIn(0f, 1f) else 0f

    fun 开始(库: TavernStore, 仓库名: String, 文件: String, 源: 下载源, 完成后: () -> Unit = {}) {
        if (进行中) return
        旗 = false
        名 = File(文件).name
        仓库 = 仓库名
        已 = 0; 总 = 0
        进行中 = true
        说明 = "开始下载…"
        val 上下文 = 应用容器.应用上下文
        上下文?.let { 下载通知.进行中(it, 名 ?: "", "准备中…", null) }
        var 上次通知 = 0L
        var 上次比例 = -1
        任务 = 范围.launch {
            try {
                val 真名 = 模型文件.下载(
                    库, 仓库名, 文件, 源,
                    { a, b ->
                        已 = a; 总 = b; 说明 = "下载中…"
                        // 通知别刷太勤：每 400ms 或百分比变了才更新一次
                        val 比例 = if (b > 0) (a * 100 / b).toInt() else -1
                        val 现在 = System.currentTimeMillis()
                        if (上下文 != null && (现在 - 上次通知 > 400 || 比例 != 上次比例)) {
                            上次通知 = 现在; 上次比例 = 比例
                            下载通知.进行中(上下文, 名 ?: "模型", 下载通知.正文(a, b), if (b > 0) 比例 else null)
                        }
                    },
                    { 旗 },
                )
                说明 = "下好了：$真名"
                上下文?.let { 下载通知.完成(it, 真名, 下载通知.正文(已, 总).substringBefore("（").trim()) }
            } catch (e: Exception) {
                val 原因 = if (旗) "已取消" else (e.message ?: "下载失败")
                说明 = 原因
                if (上下文 != null) {
                    if (旗) 下载通知.清除(上下文) else 下载通知.失败(上下文, 名 ?: "模型", 原因)
                }
            } finally {
                进行中 = false
                名 = null
                完成后()
            }
        }
    }

    fun 取消() { 旗 = true; 说明 = "取消中…" }

    /** 清掉那条"最近说明"（界面点掉它时用）。 */
    fun 清说明() { if (!进行中) 说明 = "" }
}
