package UI

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import 后端.会话
import 后端.会话库
import 后端.停旗
import 后端.接口配置
import 后端.接口库
import 后端.合并参数
import 后端.拼好的
import 后端.本机窗口
import 后端.本地引擎
import 后端.窗口内
import 后端.聊天引擎
import 后端.角色卡
import 后端.角色库
import 后端.记忆
import 后端.记忆角色
import 后端.设定对象
import 后端.上下文设置
import 后端.消息
import 后端.应用容器
import 后端.已打断
import 后端.错配置

private data class 准备(val 拼: 拼好的, val 套: 接口配置?, val 设: JsonObject, val 尾巴: List<消息>, val 全部: List<消息>)

class 聊天VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)

    var 角色 by mutableStateOf<角色卡?>(null); private set
    var 会话 by mutableStateOf<会话?>(null); private set
    var 消息列表 by mutableStateOf<List<消息>>(emptyList()); private set
    var 接口们 by mutableStateOf<List<接口配置>>(emptyList()); private set
    var 当前接口编号 by mutableStateOf(0); private set
    var 流式中 by mutableStateOf(false); private set
    var 流式文本 by mutableStateOf(""); private set
    var 错误 by mutableStateOf<String?>(null); private set
    var 背景图 by mutableStateOf<ByteArray?>(null); private set

    private var 生成任务: Job? = null
    private var 旗: 停旗? = null

    fun 打开(角色编号: Int, 会话编号: Int) {
        viewModelScope.launch {
            val 角 = withContext(Dispatchers.IO) { 角色库.取(库, 角色编号) }
            val 段 = withContext(Dispatchers.IO) { 会话库.取会话(库, 会话编号) }
            val 消 = if (段 != null) withContext(Dispatchers.IO) { 会话库.取消息(库, 段.编号) } else emptyList()
            val 接 = withContext(Dispatchers.IO) { 接口库.列(库) }
            角色 = 角; 会话 = 段; 消息列表 = 消; 接口们 = 接; 当前接口编号 = 段?.接口编号 ?: 0
            背景图 = withContext(Dispatchers.IO) { 库.取背景(角色编号)?.first }
        }
    }

    private suspend fun 装载消息() { 会话?.let { 消息列表 = withContext(Dispatchers.IO) { 会话库.取消息(库, it.编号) } } }

    fun 换接口(编号: Int) {
        当前接口编号 = 编号
        val 段 = 会话 ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { 会话库.改会话(库, 段.编号) { it.copy(接口编号 = 编号) } } }
    }

    fun 发送(文: String) {
        val 段 = 会话 ?: return
        if (文.isBlank() || 流式中) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                会话库.追加消息(库, 段.编号, "用户", 文)
                if (段.标题.isBlank()) 会话库.改标题(库, 段.编号, 文.trim().take(20))
            }
            会话 = withContext(Dispatchers.IO) { 会话库.取会话(库, 段.编号) }
            装载消息()
            起生成(null)
        }
    }

    fun 重说(序号: Int) { if (!流式中) 起生成(序号) }

    fun 编辑(序号: Int, 新文: String) {
        val 段 = 会话 ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { 会话库.覆盖消息(库, 段.编号, 序号, "角色", 新文) }; 装载消息() }
    }

    fun 删(序号: Int) {
        val 段 = 会话 ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { 会话库.删消息(库, 段.编号, 序号) }; 装载消息() }
    }

    fun 停止() { 旗?.set(); 生成任务?.cancel() }

    private fun 起生成(覆盖序号: Int?) {
        val 段0 = 会话 ?: return
        val 角 = 角色
        生成任务 = viewModelScope.launch {
            流式中 = true; 错误 = null; 流式文本 = ""
            var 目标序号 = 覆盖序号 ?: -1
            val 缓冲 = StringBuilder()
            // 生成前重读会话：设置页可能刚改过预设，重新读一遍才生效
            val 段 = withContext(Dispatchers.IO) { 会话库.取会话(库, 段0.编号) } ?: 段0
            会话 = 段
            try {
                val 准备 = withContext(Dispatchers.IO) { 准备好(角, 段) }
                val 套 = 准备.套 ?: throw 错配置("一套接口配置都没建——去「接口」里建一个。")
                withContext(Dispatchers.IO) { 收记忆(段, 准备.尾巴, 准备.全部) }
                if (覆盖序号 == null) {
                    val 占 = withContext(Dispatchers.IO) { 会话库.追加消息(库, 段.编号, "角色", "") }
                    目标序号 = 占.序号
                    装载消息()
                }
                val 旗 = 停旗(); this@聊天VM.旗 = 旗
                // 本地走成熟绑定（llama-android），在线走 HTTP——两条路同一个形状
                val 流 = if (套.协议 == "local")
                    本地引擎.流式(库, 套, 准备.拼.系统, 准备.拼.数组, 准备.设, 准备.拼.要点, 旗)
                else
                    聊天引擎.流式(套, 准备.拼.系统, 准备.拼.数组, 准备.设, 旗)
                流.collect { 块 ->
                    缓冲.append(块); 流式文本 = 缓冲.toString()
                }
                withContext(Dispatchers.IO) { 会话库.覆盖消息(库, 段.编号, 目标序号, "角色", 缓冲.toString(), 元信息(套.模型, false)) }
            } catch (e: 已打断) {
                withContext(Dispatchers.IO) { 会话库.覆盖消息(库, 段.编号, 目标序号, "角色", 缓冲.toString(), 元信息("", true)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (目标序号 > 0) withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { 会话库.覆盖消息(库, 段.编号, 目标序号, "角色", 缓冲.toString(), 元信息("", true)) }
                }
            } catch (e: Exception) {
                错误 = e.message ?: e.toString()
                if (目标序号 > 0 && 缓冲.isNotEmpty())
                    withContext(Dispatchers.IO) { 会话库.覆盖消息(库, 段.编号, 目标序号, "角色", 缓冲.toString(), 元信息("", false)) }
            } finally {
                流式中 = false; 流式文本 = ""; 旗 = null; 装载消息()
            }
        }
    }

    private suspend fun 准备好(角: 角色卡?, 段: 会话): 准备 {
        val 设 = 设定对象(段.预设)
        val 套 = 接口库.取或默认(库, 段.接口编号)
        val 条数 = 上下文设置(设).first
        val 全部 = 会话库.取消息(库, 段.编号)
        val 记忆条 = 记忆.取(库, 记忆角色, 段.角色编号.toString())
        val 记忆文本 = 记忆.聊天块(记忆条, 记忆.预算(本机窗口(套))).文本
        val (尾巴, 拼) = 窗口内(角, 全部.takeLast(条数), 设, 套, 记忆文本)
        return 准备(拼, 套, 设, 尾巴, 全部)
    }

    private fun 收记忆(段: 会话, 尾巴: List<消息>, 全部: List<消息>) {
        val 最小幸存 = 尾巴.minOfOrNull { it.序号 } ?: return
        val 水 = 记忆.取水位(库, 记忆角色, 段.角色编号.toString())
        val 现水 = 水[段.编号] ?: 0
        val 候选 = 全部.filter { it.序号 < 最小幸存 && it.序号 > 现水 && (it.说话人 == "用户" || it.说话人 == "角色") }
            .sortedBy { it.序号 }.take(20)
        if (候选.isEmpty()) return
        val 条们 = 候选.map { m ->
            buildJsonObject {
                put("文", "【${m.说话人}】${m.内容}"); put("开", false); put("来源", "自动")
                put("会话", 段.编号); put("序号", m.序号); put("指纹", 记忆.指纹(m.内容))
            }
        }
        val 新水 = HashMap(水); 新水[段.编号] = 候选.maxOf { it.序号 }
        记忆.收待定(库, 记忆角色, 段.角色编号.toString(), 条们, 新水)
    }

    private fun 元信息(模型: String, 打断: Boolean): String =
        buildJsonObject { put("模型", 模型); put("打断", 打断) }.toString()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun 聊天页(角色编号: Int, 会话编号: Int, 返回: () -> Unit, 打开上下文: () -> Unit, 打开设置: () -> Unit, vm: 聊天VM = viewModel()) {
    LaunchedEffect(角色编号, 会话编号) { vm.打开(角色编号, 会话编号) }
    var 输入 by remember { mutableStateOf("") }
    var 菜单目标 by remember { mutableStateOf<消息?>(null) }
    var 编辑目标 by remember { mutableStateOf<消息?>(null) }
    var 接口展开 by remember { mutableStateOf(false) }
    val 状态 = rememberLazyListState()

    LaunchedEffect(vm.消息列表.size, vm.流式文本.length) {
        val 总 = 状态.layoutInfo.totalItemsCount
        if (总 > 0) 状态.animateScrollToItem(总 - 1)
    }

    Scaffold(
        // 键盘弹起时，把内容顶上去（输入框就不会被挡住）。
        // 系统栏那部分已经由外层 `导航` 的 padding 处理并 consume 掉了，这里只补键盘。
        contentWindowInsets = WindowInsets.ime,
        topBar = {
        TopAppBar(
            title = { Text(vm.角色?.名字 ?: "聊天") },
            navigationIcon = { TextButton(onClick = 返回) { Text("返回") } },
            actions = {
                TextButton(onClick = 打开设置) { Text("设置") }
                TextButton(onClick = 打开上下文) { Text("上下文") }
                Box {
                    TextButton(onClick = { 接口展开 = true }) {
                        val 名 = vm.接口们.firstOrNull { it.编号 == vm.当前接口编号 }?.名称 ?: vm.接口们.firstOrNull()?.名称 ?: "无接口"
                        Text("接口：$名")
                    }
                    DropdownMenu(expanded = 接口展开, onDismissRequest = { 接口展开 = false }) {
                        if (vm.接口们.isEmpty()) DropdownMenuItem(text = { Text("还没有接口配置") }, onClick = { 接口展开 = false })
                        vm.接口们.forEach { 接 ->
                            DropdownMenuItem(text = { Text("${接.名称}（${接.协议}）") }, onClick = { vm.换接口(接.编号); 接口展开 = false })
                        }
                    }
                }
            },
        )
    }) { 内边距 ->
        Box(Modifier.fillMaxSize().padding(内边距)) {
            val 背 = vm.背景图
            if (背 != null) {
                val 位 = remember(背) { BitmapFactory.decodeByteArray(背, 0, 背.size)?.asImageBitmap() }
                if (位 != null) {
                    Image(位, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.30f))
                }
            }
            Column(Modifier.fillMaxSize()) {
            vm.错误?.let { 错 ->
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("出错了：$错", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp))
                }
            }
            LazyColumn(state = 状态, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(vm.消息列表, key = { it.序号 }) { 条 -> 气泡(条) { 菜单目标 = 条 } }
                if (vm.流式中) item(key = "stream") { 流式气泡(vm.流式文本) }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(输入, { 输入 = it }, modifier = Modifier.weight(1f), placeholder = { Text("说点什么…") }, maxLines = 5)
                if (vm.流式中) TextButton(onClick = { vm.停止() }, modifier = Modifier.padding(start = 6.dp)) { Text("停止") }
                else TextButton(onClick = { vm.发送(输入); 输入 = "" }, enabled = 输入.isNotBlank(), modifier = Modifier.padding(start = 6.dp)) { Text("发送") }
            }
            }
        }
    }

    菜单目标?.let { 条 ->
        AlertDialog(
            onDismissRequest = { 菜单目标 = null },
            title = { Text("这条消息") }, text = { Text("要做什么？") },
            confirmButton = { TextButton(onClick = { vm.重说(条.序号); 菜单目标 = null }) { Text("重说") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { 编辑目标 = 条; 菜单目标 = null }) { Text("编辑") }
                    TextButton(onClick = { vm.删(条.序号); 菜单目标 = null }) { Text("删除") }
                    TextButton(onClick = { 菜单目标 = null }) { Text("取消") }
                }
            },
        )
    }
    编辑目标?.let { 条 -> 编辑框(条.内容, { vm.编辑(条.序号, it); 编辑目标 = null }, { 编辑目标 = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun 气泡(条: 消息, onLongClick: () -> Unit) {
    val 用户 = 条.说话人 == "用户"
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (用户) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(
                containerColor = if (用户) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!用户) Text(条.说话人, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(条.内容.ifEmpty { "…" })
            }
        }
    }
}

@Composable
private fun 流式气泡(文: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.Start) {
        Card(
            Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(文.ifEmpty { "思考中…" }, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun 编辑框(原文: String, 完成: (String) -> Unit, 取消: () -> Unit) {
    var 文 by remember { mutableStateOf(原文) }
    AlertDialog(
        onDismissRequest = 取消,
        title = { Text("编辑这条消息") },
        text = { OutlinedTextField(文, { 文 = it }, modifier = Modifier.fillMaxWidth(), maxLines = 12) },
        confirmButton = { TextButton(onClick = { 完成(文) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = 取消) { Text("取消") } },
    )
}
