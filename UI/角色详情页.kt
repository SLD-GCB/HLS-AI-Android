package UI

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import 后端.角色卡
import 后端.角色库
import 后端.应用容器

/** 角色详情：可新建、可编辑。对齐 PC 版 `角色编辑框` 的字段。 */
class 角色详情VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)

    var 编号 by mutableStateOf(0); private set
    var 名字 by mutableStateOf(""); private set
    var 简介 by mutableStateOf(""); private set
    var 标签 by mutableStateOf(""); private set
    var 人设 by mutableStateOf(""); private set
    var 开场白 by mutableStateOf(""); private set
    var 示例对话 by mutableStateOf(""); private set
    var 头像图 by mutableStateOf<ByteArray?>(null); private set
    var 背景图 by mutableStateOf<ByteArray?>(null); private set

    fun 载入(角色编号: Int) {
        if (角色编号 <= 0) return
        编号 = 角色编号
        viewModelScope.launch {
            val 卡 = withContext(Dispatchers.IO) { 角色库.取(库, 角色编号) } ?: return@launch
            名字 = 卡.名字; 简介 = 卡.简介; 标签 = 卡.标签
            人设 = 卡.人设; 开场白 = 卡.开场白; 示例对话 = 卡.示例对话
            头像图 = withContext(Dispatchers.IO) { 库.取头像(角色编号)?.first }
            背景图 = withContext(Dispatchers.IO) { 库.取背景(角色编号)?.first }
        }
    }

    fun 设名字(v: String) { 名字 = v }
    fun 设简介(v: String) { 简介 = v }
    fun 设标签(v: String) { 标签 = v }
    fun 设人设(v: String) { 人设 = v }
    fun 设开场白(v: String) { 开场白 = v }
    fun 设示例对话(v: String) { 示例对话 = v }

    fun 设头像(字节: ByteArray) { 头像图 = 字节; if (编号 > 0) viewModelScope.launch { withContext(Dispatchers.IO) { 库.存头像(编号, 字节) } } }
    fun 设背景(字节: ByteArray) { 背景图 = 字节; if (编号 > 0) viewModelScope.launch { withContext(Dispatchers.IO) { 库.存背景(编号, 字节) } } }
    fun 去头像() { 头像图 = null; if (编号 > 0) viewModelScope.launch { withContext(Dispatchers.IO) { 库.删头像(编号) } } }
    fun 去背景() { 背景图 = null; if (编号 > 0) viewModelScope.launch { withContext(Dispatchers.IO) { 库.删背景(编号) } } }

    /** 存正文（新建或改）。返回角色编号。 */
    fun 存(完成后: (Int) -> Unit) {
        if (名字.isBlank()) return
        viewModelScope.launch {
            val 号 = withContext(Dispatchers.IO) {
                if (编号 <= 0) {
                    val 新 = 角色库.建(库, 名字, 简介, 人设, 开场白, 示例对话, 标签)
                    头像图?.let { 库.存头像(新.编号, it) }
                    背景图?.let { 库.存背景(新.编号, it) }
                    新.编号
                } else {
                    角色库.改(库, 编号) {
                        it.copy(名字 = 名字.trim(), 简介 = 简介, 标签 = 标签, 人设 = 人设, 开场白 = 开场白, 示例对话 = 示例对话)
                    }
                    if (头像图 != null) 库.存头像(编号, 头像图!!)
                    if (背景图 != null) 库.存背景(编号, 背景图!!)
                    编号
                }
            }
            完成后(号)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 角色详情页(
    角色编号: Int,
    返回: () -> Unit,
    vm: 角色详情VM = viewModel(),
) {
    val 上下文 = LocalContext.current
    LaunchedEffect(角色编号) { vm.载入(角色编号) }

    val 选头像 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val 字节 = 上下文.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (字节 != null) vm.设头像(字节)
        } catch (e: Exception) { }
    }
    val 选背景 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val 字节 = 上下文.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (字节 != null) vm.设背景(字节)
        } catch (e: Exception) { }
    }

    Scaffold(
        // 键盘弹起时把内容顶上去（简介/人设/示例对话这些长文本框才够得到）
        contentWindowInsets = WindowInsets.ime,
        topBar = {
            TopAppBar(
                title = { Text(if (角色编号 <= 0) "新建角色" else "编辑角色") },
                navigationIcon = { TextButton(onClick = 返回) { Text("返回") } },
                actions = {
                    TextButton(enabled = vm.名字.isNotBlank(), onClick = { vm.存 { 返回() } }) { Text("保存") }
                },
            )
        },
    ) { 内边距 ->
        Column(
            Modifier.fillMaxSize().padding(内边距).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ── 头像 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { 选头像.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    val 图 = vm.头像图
                    if (图 != null) {
                        val 位 = remember(图) { BitmapFactory.decodeByteArray(图, 0, 图.size)?.asImageBitmap() }
                        if (位 != null) Image(位, contentDescription = "头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("点头像", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    TextButton(onClick = { 选头像.launch("image/*") }) { Text("换头像") }
                    if (vm.头像图 != null) TextButton(onClick = { vm.去头像() }) { Text("去掉头像") }
                }
            }

            Spacer(Modifier.height(16.dp))
            字段("名字", vm.名字, { vm.设名字(it) }, 单行 = true)
            Spacer(Modifier.height(10.dp))
            字段("简介（一句话）", vm.简介, { vm.设简介(it) })
            Spacer(Modifier.height(10.dp))
            字段("标签（逗号分隔，可空）", vm.标签, { vm.设标签(it) }, 单行 = true)
            Spacer(Modifier.height(10.dp))
            字段("人设（这个角色是谁、什么性格、怎么说话）", vm.人设, { vm.设人设(it) }, 行数 = 5)
            Spacer(Modifier.height(10.dp))
            字段("开场白（对话开始的第一句）", vm.开场白, { vm.设开场白(it) }, 行数 = 3)
            Spacer(Modifier.height(10.dp))
            字段("示例对话（只用来体会语气，可空）", vm.示例对话, { vm.设示例对话(it) }, 行数 = 5)

            Spacer(Modifier.height(20.dp))
            Text("聊天背景图", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val 背 = vm.背景图
            if (背 != null) {
                val 位 = remember(背) { BitmapFactory.decodeByteArray(背, 0, 背.size)?.asImageBitmap() }
                if (位 != null) {
                    Image(位, contentDescription = "背景", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)))
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { 选背景.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) { Text("点这里选一张背景图", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { 选背景.launch("image/*") }) { Text("换背景") }
                if (vm.背景图 != null) TextButton(onClick = { vm.去背景() }) { Text("去掉背景") }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun 字段(标签: String, 值: String, 改: (String) -> Unit, 单行: Boolean = false, 行数: Int = 2) {
    OutlinedTextField(
        value = 值,
        onValueChange = 改,
        label = { Text(标签) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = 单行,
        minLines = if (单行) 1 else 行数,
    )
}
