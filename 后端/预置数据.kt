package 后端

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * 预置数据 —— 把**打包进 APK 的「配置 / 模型」**在首次启动时展开到数据目录。
 *
 * 「打包时带上」是怎么做的：
 *   工程根目录下的 `预置数据/` 被当作 assets 源（见 app/build.gradle.kts 的 assets.srcDirs），
 *   里面 `配置/`、`模型/` 两个子目录会原样进 APK；
 *   装好后第一次打开 App，这里把它们复制到「酒馆数据/配置」「酒馆数据/模型」。
 *
 * 规则只有一条：**只补不覆盖** —— 目标已存在的文件一律不动，用户自己改过的配置不会被冲掉。
 * 每次启动都扫一遍（资产目录很小，开销可忽略），这样你往 `预置数据/` 里新加的文件、
 * 重新打包装上去就能补出来，不用非得升版本号或者清数据。
 */
object 预置数据 {

    /** 只会从 assets 里展开这两个目录。 */
    private val 要展开的 = listOf("配置", "模型")

    /** 展开到 `根` 下。应在 `TavernStore.开()` **之前**调用，好让库能加载展开出来的配置。 */
    fun 展开(context: Context, 根: File) {
        根.mkdirs()
        for (名 in 要展开的) {
            try { 复制树(context.assets, 名, File(根, 名)) } catch (_: Exception) { }
        }
    }

    /** 递归复制：目录往下走，文件只写"目标不存在"的那些。 */
    private fun 复制树(资产: AssetManager, 资产路径: String, 目标: File) {
        val 子: Array<String> = try { 资产.list(资产路径) } catch (e: Exception) { null } ?: emptyArray()
        if (子.isEmpty()) {
            // assets.list 对"文件"和"空目录"都返回空数组 —— 用 open 试一下区分
            if (!目标.exists()) {
                try {
                    目标.parentFile?.mkdirs()
                    资产.open(资产路径).use { 入 -> 目标.outputStream().use { 入.copyTo(it) } }
                } catch (_: Exception) { /* 是目录，忽略 */ }
            }
            return
        }
        目标.mkdirs()
        for (名 in 子) 复制树(资产, "$资产路径/$名", File(目标, 名))
    }
}
