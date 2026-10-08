package UI

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import 后端.下载管家
import 后端.模型文件
import 后端.应用容器

private fun 大小(字节: Long): String =
    if (字节 >= 1_000_000_000L) "%.2f GB".format(字节 / 1e9) else "%.0f MB".format(字节 / 1e6)

class 模型管理VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    var 已下载 by mutableStateOf<List<Pair<String, Long>>>(emptyList()); private set
    var 没下完 by mutableStateOf<List<Pair<String, Long>>>(emptyList()); private set
    var 提示 by mutableStateOf<String?>(null); private set

    fun 刷新() = viewModelScope.launch {
        已下载 = withContext(Dispatchers.IO) { 模型文件.列本地(库) }
        没下完 = withContext(Dispatchers.IO) { 模型文件.列暂存(库) }
    }

    fun 删(名: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { 模型文件.删本地(库, 名) }
        刷新()
    }

    fun 删残件(名: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { 模型文件.删暂存(库, 名) }
        刷新()
    }

    /** 把「下了但没转正」的残件收下（校验 GGUF 魔数后改名进模型目录）。 */
    fun 收下(名: String) = viewModelScope.launch {
        提示 = try {
            withContext(Dispatchers.IO) { 模型文件.收下残件(库, 名) }
            "已收下：$名"
        } catch (e: Exception) { e.message ?: "收下失败" }
        刷新()
    }

    fun 总大小(): Long = 已下载.sumOf { it.second }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 模型管理页(返回: () -> Unit, vm: 模型管理VM = viewModel()) {
    var 分页 by remember { mutableStateOf(0) }   // 0=正在下载  1=已下载
    var 要删 by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.刷新() }
    // 下载一结束，就把"已下载"刷新出来
    LaunchedEffect(下载管家.进行中) { if (!下载管家.进行中) vm.刷新() }

    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("模型管理") }, navigationIcon = { TextButton(onClick = 返回) { Text("返回") } })
            TabRow(selectedTabIndex = 分页) {
                Tab(selected = 分页 == 0, onClick = { 分页 = 0 },
                    text = { Text(if (下载管家.进行中) "正在下载 ●" else "正在下载") })
                Tab(selected = 分页 == 1, onClick = { 分页 = 1 }, text = { Text("已下载（${vm.已下载.size}）") })
            }
        }
    }) { 内边距 ->
        Box(Modifier.fillMaxSize().padding(内边距)) {
            if (分页 == 0) {
                // ─────────── 正在下载 ───────────
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    if (下载管家.进行中) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(下载管家.名 ?: "下载中", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text(下载管家.仓库, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                if (下载管家.定进度) {
                                    LinearProgressIndicator(progress = { 下载管家.比例 }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(4.dp))
                                    Text("${大小(下载管家.已)} / ${大小(下载管家.总)}　（${(下载管家.比例 * 100).toInt()}%）",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(4.dp))
                                    Text("已下 ${大小(下载管家.已)}（对面没给总大小）",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = { 下载管家.取消() }) { Text("取消") }
                            }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("当前没有正在下载的任务。", style = MaterialTheme.typography.bodyMedium)
                                if (下载管家.说明.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(下载管家.说明, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("去「配置 → 本地模型」里搜或挑一个模型下载。",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (vm.没下完.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("没下完 / 没搬走的残件（不会出现在接口配置里）", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 4.dp))
                        Text("如果那个文件其实已经下完了，点「收下」就能转正。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        vm.没下完.forEach { (名, 大) ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(名, style = MaterialTheme.typography.bodyMedium)
                                        Text("已下 ${大小(大)}", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { vm.收下(名) }) { Text("收下") }
                                    TextButton(onClick = { vm.删残件(名) }) { Text("删") }
                                }
                            }
                        }
                    }
                    vm.提示?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                // ─────────── 已下载 ───────────
                if (vm.已下载.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有下过模型。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text("共 ${vm.已下载.size} 个，合计 ${大小(vm.总大小())}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(vm.已下载, key = { it.first }) { (名, 大) ->
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(名, style = MaterialTheme.typography.bodyLarge)
                                        Spacer(Modifier.height(2.dp))
                                        Text(大小(大), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { 要删 = 名 }) { Text("删") }
                                }
                            }
                        }
                        item {
                            Spacer(Modifier.height(6.dp))
                            Text("删掉的文件不可恢复；本地接口里用到它的那套配置会加载失败，记得改成别的模型。",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    要删?.let { 名 ->
        AlertDialog(
            onDismissRequest = { 要删 = null },
            title = { Text("删掉这个模型？") },
            text = { Text(名) },
            confirmButton = { TextButton(onClick = { vm.删(名); 要删 = null }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { 要删 = null }) { Text("取消") } },
        )
    }
}
