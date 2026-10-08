package UI

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import 后端.上下文设置
import 后端.会话库
import 后端.接口库
import 后端.本机窗口
import 后端.窗口内
import 后端.角色卡
import 后端.角色库
import 后端.记忆
import 后端.记忆角色
import 后端.设定对象
import 后端.消息
import 后端.应用容器

private data class 预览(val 角: 角色卡?, val 消: List<消息>, val 系统: String, val 发送序号: Set<Int>, val 条: List<JsonObject>, val 接口名: String)

class 上下文VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    private var 角色编号 = 0
    var 消息们 by mutableStateOf<List<消息>>(emptyList()); private set
    var 系统 by mutableStateOf(""); private set
    var 发送序号 by mutableStateOf<Set<Int>>(emptySet()); private set
    var 记忆条 by mutableStateOf<List<JsonObject>>(emptyList()); private set
    var 接口名 by mutableStateOf("（无）"); private set

    fun 打开(角号: Int, 会话编号: Int) {
        角色编号 = 角号
        viewModelScope.launch {
            val 数据 = withContext(Dispatchers.IO) {
                val 角 = 角色库.取(库, 角号)
                val 段 = 会话库.取会话(库, 会话编号) ?: return@withContext null
                val 消 = 会话库.取消息(库, 段.编号)
                val 设 = 设定对象(段.预设)
                val 套 = 接口库.取或默认(库, 段.接口编号)
                val 条 = 记忆.取(库, 记忆角色, 角号.toString())
                val 文 = 记忆.聊天块(条, 记忆.预算(本机窗口(套))).文本
                val (尾巴, 拼) = 窗口内(角, 消.takeLast(上下文设置(设).first), 设, 套, 文)
                预览(角, 消, 拼.系统, 尾巴.map { it.序号 }.toSet(), 条, 套?.名称 ?: "（无）")
            } ?: return@launch
            消息们 = 数据.消; 系统 = 数据.系统; 发送序号 = 数据.发送序号; 记忆条 = 数据.条; 接口名 = 数据.接口名
        }
    }

    private fun 存记忆(条们: List<JsonObject>) {
        记忆条 = 条们
        viewModelScope.launch { withContext(Dispatchers.IO) { 记忆.存(库, 记忆角色, 角色编号.toString(), 条们) } }
    }

    fun 加(文: String) = 存记忆(记忆条 + buildJsonObject { put("文", 文); put("开", true); put("来源", "手写") })
    fun 删(i: Int) = 存记忆(记忆条.filterIndexed { j, _ -> j != i })
    fun 切换(i: Int) {
        val 现在 = (记忆条[i]["开"] as? JsonPrimitive)?.booleanOrNull ?: false
        存记忆(记忆条.mapIndexed { j, x ->
            if (j != i) x else buildJsonObject { x.forEach { (k, v) -> put(k, v) }; put("开", !现在) }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 上下文页(角色编号: Int, 会话编号: Int, 返回: () -> Unit, vm: 上下文VM = viewModel()) {
    LaunchedEffect(角色编号, 会话编号) { vm.打开(角色编号, 会话编号) }
    var 加文中 by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("上下文预览") }, navigationIcon = { TextButton(onClick = 返回) { Text("返回") } }) }) { 内边距 ->
        Column(Modifier.fillMaxSize().padding(内边距).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("这次会发给模型什么（接口：${vm.接口名}）", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("系统提示", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(vm.系统.ifBlank { "（空）" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("消息（[发]=这次会发，[截]=被截掉）", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            vm.消息们.forEach { m ->
                val 发 = vm.发送序号.contains(m.序号)
                Text("${if (发) "[发]" else "[截]"} ${m.说话人}#${m.序号}：${m.内容.take(120)}",
                    color = if (发) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("长期记忆", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { 加文中 = true }) { Text("加一条") }
            }
            if (vm.记忆条.isEmpty()) Text("（还没有记忆）", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            vm.记忆条.forEachIndexed { i, x ->
                val 开 = (x["开"] as? JsonPrimitive)?.booleanOrNull ?: false
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = 开, onCheckedChange = { vm.切换(i) })
                    Text((x["文"] as? JsonPrimitive)?.contentOrNull ?: "", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.删(i) }) { Text("删") }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (加文中) {
        var 文 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { 加文中 = false },
            title = { Text("加一条记忆") },
            text = { OutlinedTextField(文, { 文 = it }, label = { Text("内容（最多 2000 字）") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = 文.isNotBlank(), onClick = { vm.加(文.trim()); 加文中 = false }) { Text("加") } },
            dismissButton = { TextButton(onClick = { 加文中 = false }) { Text("取消") } },
        )
    }
}
