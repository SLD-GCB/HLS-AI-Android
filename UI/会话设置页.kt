package UI

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import 后端.会话库
import 后端.应用容器
import 后端.设定对象

/**
 * 会话设置页 —— 对齐 PC 版的「会话预设」。存进 `会话.预设`（JSON 字符串）。
 *
 * 这里的字段分两类：
 *   ① **控制协议**：人格锁 / 预填 / 分流 / 中间层 / 掐思维 / 禁吐
 *      （由 `后端/控制.kt` 的 `取控制` 读、`本地引擎` 用）
 *   ② **提示与采样**：用户称呼 / 系统提示 / 上下文条数 / 字数上限 / 采样参数
 *      （由 `拼提示` / `窗口内` / `合并参数` 读）
 */

private fun 取文(设: kotlinx.serialization.json.JsonObject, 键: String) =
    (设[键] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

private fun 是开(s: String) = s.trim().lowercase() in listOf("开", "on", "1", "是", "true")

class 会话设置VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    private var 会号 = 0

    var 用户称呼 by mutableStateOf(""); private set
    var 系统提示 by mutableStateOf(""); private set
    var 上下文条数 by mutableStateOf("20"); private set
    var 字数上限 by mutableStateOf("24000"); private set
    var 人格锁 by mutableStateOf(false); private set
    var 预填 by mutableStateOf(""); private set
    var 分流 by mutableStateOf("自动"); private set
    var 中间层 by mutableStateOf("自动"); private set
    var 掐思维 by mutableStateOf(false); private set
    var 禁吐 by mutableStateOf(""); private set
    var 温度 by mutableStateOf(""); private set
    var topP by mutableStateOf(""); private set
    var 最大输出 by mutableStateOf(""); private set
    var topK by mutableStateOf(""); private set
    var 重复惩罚 by mutableStateOf(""); private set

    fun 打开(会话编号: Int) {
        会号 = 会话编号
        viewModelScope.launch {
            val 段 = withContext(Dispatchers.IO) { 会话库.取会话(库, 会话编号) } ?: return@launch
            val 设 = 设定对象(段.预设)
            用户称呼 = 取文(设, "用户称呼")
            系统提示 = 取文(设, "系统提示")
            上下文条数 = 取文(设, "上下文条数").ifEmpty { "20" }
            字数上限 = 取文(设, "字数上限").ifEmpty { "24000" }
            人格锁 = 是开(取文(设, "人格锁"))
            预填 = 取文(设, "预填")
            分流 = 取文(设, "分流").ifEmpty { "自动" }
            中间层 = 取文(设, "中间层").ifEmpty { "自动" }
            掐思维 = 是开(取文(设, "掐思维"))
            禁吐 = 取文(设, "禁吐")
            温度 = 取文(设, "temperature")
            topP = 取文(设, "top_p")
            最大输出 = 取文(设, "max_tokens")
            topK = 取文(设, "top_k")
            重复惩罚 = 取文(设, "repeat_penalty")
        }
    }

    fun 存(值: 会话设置值) {
        viewModelScope.launch {
            val 体 = buildJsonObject {
                if (值.用户称呼.isNotBlank()) put("用户称呼", 值.用户称呼.trim())
                if (值.系统提示.isNotBlank()) put("系统提示", 值.系统提示.trim())
                值.上下文条数.toIntOrNull()?.let { put("上下文条数", it) }
                值.字数上限.toIntOrNull()?.let { put("字数上限", it) }
                put("人格锁", if (值.人格锁) "开" else "关")
                if (值.预填.isNotBlank()) put("预填", 值.预填)
                put("分流", 值.分流)
                put("中间层", 值.中间层)
                put("掐思维", if (值.掐思维) "开" else "关")
                if (值.禁吐.isNotBlank()) put("禁吐", 值.禁吐.trim())
                值.温度.toFloatOrNull()?.let { put("temperature", it) }
                值.topP.toFloatOrNull()?.let { put("top_p", it) }
                值.最大输出.toIntOrNull()?.let { put("max_tokens", it) }
                值.topK.toIntOrNull()?.let { put("top_k", it) }
                值.重复惩罚.toFloatOrNull()?.let { put("repeat_penalty", it) }
            }
            withContext(Dispatchers.IO) { 会话库.改会话(库, 会号) { it.copy(预设 = 体.toString()) } }
        }
    }
}

/** 页面上的值一次打包，便于保存。 */
data class 会话设置值(
    val 用户称呼: String,
    val 系统提示: String,
    val 上下文条数: String,
    val 字数上限: String,
    val 人格锁: Boolean,
    val 预填: String,
    val 分流: String,
    val 中间层: String,
    val 掐思维: Boolean,
    val 禁吐: String,
    val 温度: String,
    val topP: String,
    val 最大输出: String,
    val topK: String,
    val 重复惩罚: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 会话设置页(会话编号: Int, 返回: () -> Unit, vm: 会话设置VM = viewModel()) {
    LaunchedEffect(会话编号) { vm.打开(会话编号) }

    // 本地可变副本，点保存才写库
    var 用户称呼 by remember(vm.用户称呼) { mutableStateOf(vm.用户称呼) }
    var 系统提示 by remember(vm.系统提示) { mutableStateOf(vm.系统提示) }
    var 上下文条数 by remember(vm.上下文条数) { mutableStateOf(vm.上下文条数) }
    var 字数上限 by remember(vm.字数上限) { mutableStateOf(vm.字数上限) }
    var 人格锁 by remember(vm.人格锁) { mutableStateOf(vm.人格锁) }
    var 预填 by remember(vm.预填) { mutableStateOf(vm.预填) }
    var 分流 by remember(vm.分流) { mutableStateOf(vm.分流) }
    var 中间层 by remember(vm.中间层) { mutableStateOf(vm.中间层) }
    var 掐思维 by remember(vm.掐思维) { mutableStateOf(vm.掐思维) }
    var 禁吐 by remember(vm.禁吐) { mutableStateOf(vm.禁吐) }
    var 温度 by remember(vm.温度) { mutableStateOf(vm.温度) }
    var topP by remember(vm.topP) { mutableStateOf(vm.topP) }
    var 最大输出 by remember(vm.最大输出) { mutableStateOf(vm.最大输出) }
    var topK by remember(vm.topK) { mutableStateOf(vm.topK) }
    var 重复惩罚 by remember(vm.重复惩罚) { mutableStateOf(vm.重复惩罚) }

    fun 保存() {
        vm.存(会话设置值(用户称呼, 系统提示, 上下文条数, 字数上限, 人格锁, 预填,
            分流, 中间层, 掐思维, 禁吐, 温度, topP, 最大输出, topK, 重复惩罚))
        返回()
    }

    Scaffold(
        // 键盘弹起时把内容顶上去（下面几栏表单才够得到）
        contentWindowInsets = WindowInsets.ime,
        topBar = {
        TopAppBar(
            title = { Text("会话设置") },
            navigationIcon = { TextButton(onClick = 返回) { Text("返回") } },
            actions = { TextButton(onClick = { 保存() }) { Text("保存", fontWeight = FontWeight.SemiBold) } },
        )
    }) { 内边距 ->
        Column(Modifier.fillMaxSize().padding(内边距).verticalScroll(rememberScrollState()).padding(12.dp)) {

            段("人格控制")

            开关行("人格锁", "把角色要点复述到最后一条消息末尾 —— 小模型更听话", 人格锁) { 人格锁 = it }
            Spacer(Modifier.height(6.dp))
            文本框("预填", 预填, "替模型开头几个字（留空＝不预填）。例：<think>\\n</think>\\n", 2) { 预填 = it }
            Spacer(Modifier.height(6.dp))
            选项行("分流", 分流, listOf("自动", "开", "关")) { 分流 = it }
            Text("自动＝只在认出推理模型时剥思维链；开＝强制剥；关＝不剥",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            选项行("中间层", 中间层, listOf("自动", "关")) { 中间层 = it }
            Text("自动＝开（会掐掉「作为AI」这类短语）；关＝全关",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            开关行("掐思维", "把 <think> 从生成里掐掉（实测会让部分模型复读，默认关）", 掐思维) { 掐思维 = it }
            Spacer(Modifier.height(6.dp))
            文本框("禁吐", 禁吐, "额外要掐掉的短语，一行一条（默认已含「作为AI」等）", 3) { 禁吐 = it }

            Spacer(Modifier.height(16.dp))
            段("提示与上下文")

            文本框("用户称呼", 用户称呼, "在提示里称呼你什么（默认「你」）", 1) { 用户称呼 = it }
            Spacer(Modifier.height(6.dp))
            文本框("系统提示", 系统提示, "附加到系统区块末尾的说明（可留空）", 3) { 系统提示 = it }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                数框("上下文条数", 上下文条数, Modifier.weight(1f)) { 上下文条数 = it }
                数框("字数上限", 字数上限, Modifier.weight(1f)) { 字数上限 = it }
            }

            Spacer(Modifier.height(16.dp))
            段("采样参数（留空＝按接口配置）")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                数框("temperature", 温度, Modifier.weight(1f)) { 温度 = it }
                数框("top_p", topP, Modifier.weight(1f)) { topP = it }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                数框("max_tokens", 最大输出, Modifier.weight(1f)) { 最大输出 = it }
                数框("top_k", topK, Modifier.weight(1f)) { topK = it }
            }
            Spacer(Modifier.height(6.dp))
            数框("repeat_penalty", 重复惩罚, Modifier.fillMaxWidth()) { 重复惩罚 = it }
            Text("repeat_penalty 压复读/绕圈：本地默认 1.1，绕圈就调到 1.2~1.3。留空＝走接口配置。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))
            Button(onClick = { 保存() }, modifier = Modifier.fillMaxWidth()) { Text("保存并返回") }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun 段(标题: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(标题, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun 开关行(标题: String, 说明: String, 值: Boolean, 改: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(标题, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(说明, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = 值, onCheckedChange = 改)
        }
    }
}

@Composable
private fun 文本框(标题: String, 值: String, 提示: String, 行: Int, 改: (String) -> Unit) {
    OutlinedTextField(
        值, 改,
        label = { Text(标题) },
        placeholder = { Text(提示, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 行, maxLines = maxOf(行, 6),
    )
}

@Composable
private fun 数框(标题: String, 值: String, 修饰: Modifier, 改: (String) -> Unit) {
    OutlinedTextField(值, 改, label = { Text(标题) }, modifier = 修饰, singleLine = true)
}

@Composable
private fun 选项行(标题: String, 当前: String, 选项们: List<String>, 改: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(标题, modifier = Modifier.width(72.dp), fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            选项们.forEach { 选 ->
                val 中 = 选 == 当前
                TextButton(
                    onClick = { 改(选) },
                    modifier = Modifier.background(
                        color = if (中) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ),
                ) { Text(选, color = if (中) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
