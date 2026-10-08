package UI

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import 后端.下载源们
import 后端.默认源
import 后端.取源
import 后端.代理设置
import 后端.下载管家
import 后端.模型文件
import 后端.精选档位
import 后端.应用容器

private fun 大小(字节: Long): String =
    if (字节 >= 1_000_000_000L) "%.1f GB".format(字节 / 1e9) else "%.0f MB".format(字节 / 1e6)

private fun 下载量(数: Long): String =
    when {
        数 >= 1_000_000 -> "%.1fM".format(数 / 1e6)
        数 >= 1_000 -> "%.1fk".format(数 / 1e3)
        else -> 数.toString()
    }

private fun 源简称(标: String): String = when (标) {
    "modelscope" -> "ModelScope"
    "aifasthub" -> "FastHub"
    "hf-mirror" -> "HF镜像"
    else -> "HF官方"
}

/** 体积范围显示：`0.4–1.2 GB`；没算出来显示 `体积…`。 */
private fun 体积文字(范: Pair<Long, Long>?): String = when {
    范 == null -> "体积…"
    范.first <= 0L -> ""
    范.second > 范.first -> "${大小(范.first)}–${大小(范.second)}"
    else -> 大小(范.first)
}

/** 按量化档给个提示（压得太狠的档已经在后端被过滤掉了）。 */
private fun 量化提示(名: String): String {
    val n = 名.uppercase()
    return when {
        listOf("Q4_K_M", "Q5_K_M", "Q6_K").any { n.contains(it) } -> "　✅ 推荐"
        listOf("Q3_K_M", "Q4_K_S", "Q4_0", "Q4_1", "Q5_K_S", "Q5_0", "Q5_1", "Q8_0", "F16").any { n.contains(it) } -> "　（可以）"
        else -> ""
    }
}

class 本地模型VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)

    var 源标 by mutableStateOf(默认源); private set
    var 词 by mutableStateOf(""); private set
    var 手填 by mutableStateOf(""); private set
    var 发现 by mutableStateOf<List<模型文件.发现条目>>(emptyList()); private set
    var 拉取中 by mutableStateOf(false); private set
    var 状态 by mutableStateOf(""); private set

    var 选文件仓库 by mutableStateOf<String?>(null); private set
    var 待选文件们 by mutableStateOf<List<Pair<String, Long>>>(emptyList()); private set
    var 选文件加载中 by mutableStateOf(false); private set

    private var 填充任务: Job? = null

    fun 换源(s: String) { 源标 = s }
    fun 换词(s: String) { 词 = s }
    fun 换手填(s: String) { 手填 = s }
    fun 代理说明() = 代理设置.说明()

    fun 拉发现() {
        if (拉取中) return
        拉取中 = true; 状态 = "正在从「${取源(源标).名}」搜…"
        viewModelScope.launch {
            try {
                val 们 = withContext(Dispatchers.IO) { 模型文件.热门模型(取源(源标), 词.trim(), 40) }
                val 滤 = 模型文件.上次滤掉
                发现 = 们
                状态 = when {
                    们.isEmpty() && 滤 > 0 -> "没剩下合适的模型（滤掉了 $滤 个：非对话 / 来源不明）。换个关键词或源试试。"
                    们.isEmpty() -> "这个源没搜到。换个关键词/源试试。"
                    滤 > 0 -> "搜到 ${们.size} 个（另滤掉 $滤 个：非对话 / 来源不明），正在算体积…"
                    else -> "搜到 ${们.size} 个，正在算体积…"
                }
                填充体积(们, 取源(源标))
            } catch (e: Exception) {
                发现 = emptyList()
                状态 = (e.message ?: "搜索失败") + "（HF 系需要开代理；ModelScope/FastHub 国内直连）"
            } finally { 拉取中 = false }
        }
    }

    /** 搜索结果里每条惰性补上"体积范围"（并发 6 个，边算边更新）。 */
    private fun 填充体积(初: List<模型文件.发现条目>, 源: 后端.下载源) {
        填充任务?.cancel()
        填充任务 = viewModelScope.launch {
            val 表 = 初.toMutableList()
            val 闸 = Semaphore(6)
            表.mapIndexed { i, 条 ->
                launch {
                    闸.withPermit {
                        val 范 = withContext(Dispatchers.IO) {
                            runCatching { 模型文件.体积范围(条.仓库, 源) }.getOrDefault(0L to 0L)
                        }
                        if (范.first > 0) {
                            表[i] = 条.copy(体积 = 范)
                            发现 = 表.toList()
                        }
                    }
                }
            }.joinAll()
            状态 = "搜到 ${表.size} 个。"
        }
    }

    fun 打开仓库(仓库: String, 用源: String = 源标) {
        源标 = 用源
        选文件仓库 = 仓库; 待选文件们 = emptyList(); 选文件加载中 = true
        viewModelScope.launch {
            try {
                待选文件们 = withContext(Dispatchers.IO) { 模型文件.列远程文件(仓库, 取源(用源)) }
            } catch (e: Exception) { 状态 = e.message ?: "列文件失败" }
            finally { 选文件加载中 = false }
        }
    }

    fun 快捷搜(关键词: String, 源: String) { 源标 = 源; 词 = 关键词; 拉发现() }

    fun 列手填() {
        val 仓 = 手填.trim()
        if (仓.isEmpty()) { 状态 = "先填仓库，如 Qwen/Qwen2.5-1.5B-Instruct-GGUF"; return }
        打开仓库(仓)
    }

    fun 关仓库() { 选文件仓库 = null; 待选文件们 = emptyList() }

    fun 下载(仓库: String, 文件: String) {
        关仓库()
        状态 = "已开始下载 ${文件.substringAfterLast('/')}（进度看「配置 → 模型管理」）"
        下载管家.开始(库, 仓库, 文件, 取源(源标))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 本地模型页(返回: () -> Unit, vm: 本地模型VM = viewModel()) {
    var 源展开 by remember { mutableStateOf(false) }
    var 分页 by remember { mutableStateOf(0) }   // 0=搜索  1=精选

    Scaffold(
        // 键盘弹起时搜索结果能滚上来（不然输入时看不到下面搜到了什么）
        contentWindowInsets = WindowInsets.ime,
        topBar = {
        Column {
            TopAppBar(title = { Text("本地模型") }, navigationIcon = { TextButton(onClick = 返回) { Text("返回") } })
            TabRow(selectedTabIndex = 分页) {
                Tab(selected = 分页 == 0, onClick = { 分页 = 0 }, text = { Text("搜索") })
                Tab(selected = 分页 == 1, onClick = { 分页 = 1 }, text = { Text("精选") })
            }
        }
    }, bottomBar = {
        // 下载进度 / 最近一次结果，都只在页脚这一处显示（不再散在各个标签里）
        if (下载管家.进行中 || 下载管家.说明.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth().padding(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (下载管家.进行中) {
                        Text("正在下载：${下载管家.名 ?: ""}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        if (下载管家.定进度) {
                            LinearProgressIndicator(progress = { 下载管家.比例 }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(3.dp))
                            Text("${大小(下载管家.已)} / ${大小(下载管家.总)}（${(下载管家.比例 * 100).toInt()}%）",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(3.dp))
                            Text("已下 ${大小(下载管家.已)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("下完才会出现在「接口配置」里；进度详情看「配置 → 模型管理」",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(下载管家.说明, style = MaterialTheme.typography.labelMedium,
                                color = if (下载管家.说明.startsWith("下好了")) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { 下载管家.清说明() }) { Text("知道了") }
                        }
                    }
                }
            }
        }
    }) { 内边距 ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(内边距),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (分页 == 0) {
                // ═══════════ 搜索页 ═══════════
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("搜索模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Box {
                                    TextButton(onClick = { 源展开 = true }) { Text("源：${源简称(vm.源标)} ▾") }
                                    DropdownMenu(expanded = 源展开, onDismissRequest = { 源展开 = false }) {
                                        下载源们.forEach { s ->
                                            DropdownMenuItem(text = { Text(s.名) }, onClick = { vm.换源(s.标); 源展开 = false })
                                        }
                                    }
                                }
                            }
                            if (vm.代理说明() != "未启用") {
                                Text("代理：${vm.代理说明()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = vm.词, onValueChange = { vm.换词(it) },
                                label = { Text("关键词（如 abliterated / roleplay / qwen）") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { vm.快捷搜("abliterated", "aifasthub") }) { Text("去审查") }
                                TextButton(onClick = { vm.快捷搜("uncensored", "aifasthub") }) { Text("无限制") }
                                TextButton(onClick = { vm.快捷搜("roleplay", "aifasthub") }) { Text("角色扮演") }
                                TextButton(onClick = { vm.快捷搜("gguf", "aifasthub") }) { Text("全部") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(enabled = !vm.拉取中, onClick = { vm.拉发现() }) { Text(if (vm.拉取中) "搜索中…" else "搜索") }
                                if (vm.状态.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(vm.状态, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // 搜索结果
                items(vm.发现, key = { "发-${it.仓库}" }) { 条 ->
                    Card(
                        Modifier.fillMaxWidth().clickable { vm.打开仓库(条.仓库, vm.源标) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(条.名字, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text(条.仓库, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("↓ ${下载量(条.下载)}　${条.更新}　${体积文字(条.体积)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { vm.打开仓库(条.仓库, vm.源标) }) { Text("选文件") }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            } else {
                // ═══════════ 精选页 ═══════════
                item {
                    Text("精选（点一下选量化）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                精选档位.groupBy { it.组 }.forEach { (组, 档们) ->
                    item(key = "组-$组") {
                        Text(组, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(档们, key = { "精-${it.仓库}-${it.名字}" }) { 档 ->
                        Card(
                            Modifier.fillMaxWidth().clickable { vm.打开仓库(档.仓库, 档.源) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(档.名字, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(档.说明, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { vm.打开仓库(档.仓库, 档.源) }) { Text("选文件") }
                            }
                        }
                    }
                }

                // 自己填仓库
                item {
                    Text("自己填仓库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.手填, onValueChange = { vm.换手填(it) },
                        label = { Text("owner/名字，如 mradermacher/Qwen2.5-1.5B-Instruct-abliterated-GGUF") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.列手填() }) { Text("列出文件") }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // 选文件弹窗
    vm.选文件仓库?.let { 仓 ->
        AlertDialog(
            onDismissRequest = { vm.关仓库() },
            title = { Text("挑一个 GGUF 文件") },
            text = {
                Column {
                    Text(仓, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    if (vm.选文件加载中) Text("正在列文件…")
                    else if (vm.待选文件们.isEmpty())
                        Text("这个仓库没有可用的单文件 GGUF（可能只有分卷、mmproj 或压得太狠的量化档）。换个仓库吧。")
                    else LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(vm.待选文件们, key = { it.first }) { (径, 大) ->
                            val 太大 = if (大 > 2_500_000_000L) "　⚠ 手机多半跑不动" else ""
                            Text(
                                "${径.substringAfterLast('/')}  (${大小(大)})$太大${量化提示(径)}",
                                modifier = Modifier.fillMaxWidth().clickable { vm.下载(仓, 径) }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.关仓库() }) { Text("取消") } },
        )
    }
}
