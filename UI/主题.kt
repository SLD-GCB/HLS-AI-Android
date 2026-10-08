package UI

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.honglusi.tavern.R

/**
 * 鸿胪寺主题 —— **柔和的暖色（纸感 + 焦糖）+ 柔和文楷字**，进来就放松、沉浸。
 * 亮 / 暗两套，可在「配置」页切换。
 */

/** 全局主题开关（配置页切，App 读）。 */
object 主题设置 {
    var 暗色 by mutableStateOf(false)
}

/** 霞鹜文楷·屏幕阅读版（笔画加粗、为屏幕优化，比标准版更清楚）——全 App 默认字体。 */
private val 文楷 = FontFamily(Font(R.font.wenkai_screen))

/** 柔和排版：行距大、字距略松、**整体加粗**（更清楚）。 */
private val 柔和排版 = Typography(
    bodyLarge = TextStyle(fontFamily = 文楷, fontSize = 17.sp, lineHeight = 30.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Bold),
    bodyMedium = TextStyle(fontFamily = 文楷, fontSize = 15.sp, lineHeight = 26.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Bold),
    bodySmall = TextStyle(fontFamily = 文楷, fontSize = 13.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = 文楷, fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = 文楷, fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    titleSmall = TextStyle(fontFamily = 文楷, fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = 文楷, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = 文楷, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = 文楷, fontSize = 11.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    headlineSmall = TextStyle(fontFamily = 文楷, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    displaySmall = TextStyle(fontFamily = 文楷, fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
)

// 亮色：米纸底 + 焦糖主色，文字用暖褐（不用纯黑）
private val 亮色 = lightColorScheme(
    primary = Color(0xFFA67C52),
    onPrimary = Color(0xFFFFFDF8),
    primaryContainer = Color(0xFFF0E2D0),
    onPrimaryContainer = Color(0xFF4A3826),
    secondary = Color(0xFF7E8B7A),
    onSecondary = Color(0xFFFFFDF8),
    secondaryContainer = Color(0xFFE4E8DE),
    onSecondaryContainer = Color(0xFF3A4038),
    tertiary = Color(0xFF9C7B7B),
    tertiaryContainer = Color(0xFFEFE1E0),
    onTertiaryContainer = Color(0xFF463231),
    background = Color(0xFFF6F1E7),
    onBackground = Color(0xFF443E36),
    surface = Color(0xFFFBF7EF),
    onSurface = Color(0xFF443E36),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF7A7166),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF8F3E9),
    surfaceContainer = Color(0xFFF3EDE2),
    surfaceContainerHigh = Color(0xFFEDE7DB),
    surfaceContainerHighest = Color(0xFFE7E0D3),
    outline = Color(0xFFE0D8C9),
    outlineVariant = Color(0xFFEBE4D6),
    error = Color(0xFFB4533A),
    onError = Color(0xFFFFFDF8),
)

// 暗色：暖黑 + 沙金主色，文字柔和不刺眼
private val 暗色 = darkColorScheme(
    primary = Color(0xFFD4B08C),
    onPrimary = Color(0xFF2A2119),
    primaryContainer = Color(0xFF4A3B2A),
    onPrimaryContainer = Color(0xFFF0E2D0),
    secondary = Color(0xFF9AA694),
    onSecondary = Color(0xFF1E241C),
    secondaryContainer = Color(0xFF3A4038),
    onSecondaryContainer = Color(0xFFDCE4D6),
    tertiary = Color(0xFFC0A0A0),
    tertiaryContainer = Color(0xFF43322F),
    onTertiaryContainer = Color(0xFFEFE1E0),
    background = Color(0xFF17150F),
    onBackground = Color(0xFFDDD5C8),
    surface = Color(0xFF1E1B15),
    onSurface = Color(0xFFDDD5C8),
    surfaceVariant = Color(0xFF2C2820),
    onSurfaceVariant = Color(0xFFA79E90),
    surfaceContainerLowest = Color(0xFF121009),
    surfaceContainerLow = Color(0xFF1B1812),
    surfaceContainer = Color(0xFF211E18),
    surfaceContainerHigh = Color(0xFF2A261F),
    surfaceContainerHighest = Color(0xFF332E26),
    outline = Color(0xFF3A352C),
    outlineVariant = Color(0xFF2C2820),
    error = Color(0xFFD98A72),
    onError = Color(0xFF2A140E),
)

@Composable
fun 鸿胪寺主题(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (主题设置.暗色) 暗色 else 亮色,
        typography = 柔和排版,
        content = content,
    )
}
