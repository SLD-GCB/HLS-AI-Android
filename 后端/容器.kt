package 后端

import android.content.Context

/** 极简依赖容器：全局只有一个 [TavernStore]，懒加载、只开一次。 */
object 应用容器 {
    @Volatile private var 单例: TavernStore? = null

    /** Application 上下文（下载通知这类"没有界面也要能干活"的地方用）。 */
    @Volatile var 应用上下文: Context? = null
        private set

    fun 库(context: Context): TavernStore {
        应用上下文 = context.applicationContext
        单例?.let { return it }
        return synchronized(this) {
            单例 ?: run {
                val 存 = TavernStore(TavernStore.默认目录(context.applicationContext.filesDir))
                // 先把 APK 里预置的「配置 / 模型」展开到数据目录（只补不覆盖），
                // 再开库 —— 这样预置的配置能被正常加载。
                预置数据.展开(context.applicationContext, 存.目录)
                存.开()
                代理设置.载入(存)
                // 把历史遗留的明文密钥/附加头一次性升级成密文
                try { 接口库.升级明文(存) } catch (_: Exception) { }
                单例 = 存
                存
            }
        }
    }
}
