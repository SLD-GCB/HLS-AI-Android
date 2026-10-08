package UI

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import 后端.下载通知

/** 程序入口 main。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 下载通知的渠道 + Android 13+ 的通知权限（拒绝也不影响用，只是收不到通知）
        下载通知.建渠道(this)
        申请通知权限()
        setContent {
            鸿胪寺主题 {
                导航()
            }
        }
    }

    private fun 申请通知权限() {
        if (Build.VERSION.SDK_INT < 33) return
        val 有 = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!有) {
            try { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001) } catch (_: Exception) { }
        }
    }
}
