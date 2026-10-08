package 后端

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.honglusi.tavern.R

/**
 * 模型下载通知 —— 让用户**退出 App 也能知道**在下载什么、下完没有。
 *
 * · 一条通知原地更新（`setOnlyAlertOnce`，不会反复响）；
 * · 有总长度就显示百分比进度，没有就转圈；
 * · 下完 / 失败会改成对应文案并允许划掉；
 * · 点一下回到 App。
 *
 * 权限：Android 13+ 要 `POST_NOTIFICATIONS`（在 MainActivity 里申请）；
 * 没授权时 notify 会被系统丢掉，这里一律 try/catch，绝不因为通知把下载搞挂。
 */
object 下载通知 {

    private const val 渠道号 = "模型下载"
    private const val 通知号 = 1001

    fun 建渠道(上下文: Context) {
        try {
            val 管 = 上下文.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (管.getNotificationChannel(渠道号) != null) return
            管.createNotificationChannel(
                NotificationChannel(渠道号, "模型下载", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "下载本地模型时的进度与结果提示"
                    setShowBadge(true)
                },
            )
        } catch (_: Exception) { }
    }

    private fun 回App(上下文: Context): PendingIntent? = try {
        PendingIntent.getActivity(
            上下文, 0,
            Intent().setClassName(上下文.packageName, "UI.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } catch (_: Exception) { null }

    private fun 发(上下文: Context, 标题: String, 正文: String, 进行中: Boolean, 比例: Int?) {
        try {
            val b = NotificationCompat.Builder(上下文, 渠道号)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(标题)
                .setContentText(正文)
                .setStyle(NotificationCompat.BigTextStyle().bigText(正文))
                .setOnlyAlertOnce(true)
                .setOngoing(进行中)
                .setAutoCancel(!进行中)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            回App(上下文)?.let { b.setContentIntent(it) }
            if (比例 != null && 比例 in 0..100) b.setProgress(100, 比例, false)
            else if (进行中) b.setProgress(0, 0, true)
            NotificationManagerCompat.from(上下文).notify(通知号, b.build())
        } catch (_: Exception) { }
    }

    /** 正在下载（比例 null = 不确定进度）。 */
    fun 进行中(上下文: Context, 名: String, 正文: String, 比例: Int?) {
        建渠道(上下文)
        发(上下文, "正在下载：$名", 正文, 进行中 = true, 比例 = 比例)
    }

    fun 完成(上下文: Context, 名: String, 大小文: String) {
        建渠道(上下文)
        发(上下文, "模型下载完成", "$名（$大小文）已就绪，可在「接口配置」里选它。", 进行中 = false, 比例 = null)
    }

    fun 失败(上下文: Context, 名: String, 原因: String) {
        建渠道(上下文)
        发(上下文, "模型下载失败", "$名：$原因", 进行中 = false, 比例 = null)
    }

    fun 清除(上下文: Context) {
        try { NotificationManagerCompat.from(上下文).cancel(通知号) } catch (_: Exception) { }
    }

    private fun 兆(字节: Long): String =
        if (字节 >= 1_000_000_000L) "%.2f GB".format(字节 / 1e9) else "%.0f MB".format(字节 / 1e6)

    /** 进度正文，如 `1.2 GB / 3.4 GB（35%）`。 */
    fun 正文(已: Long, 总: Long): String =
        if (总 > 0) "${兆(已)} / ${兆(总)}（${(已 * 100 / 总).toInt()}%）" else "已下 ${兆(已)}"
}
