package UI

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import 后端.会话
import 后端.会话库
import 后端.角色卡
import 后端.角色库
import 后端.应用容器

data class 对话条(val 会话: 会话, val 角色名: String)

class 对话VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    var 列表 by mutableStateOf<List<对话条>>(emptyList()); private set
    var 角色们 by mutableStateOf<List<角色卡>>(emptyList()); private set

    fun 刷新() {
        viewModelScope.launch {
            val 角们 = withContext(Dispatchers.IO) { 角色库.列(库) }
            val 名表 = 角们.associate { it.编号 to it.名字 }
            val 段们 = withContext(Dispatchers.IO) { 会话库.列会话(库, null) }
            列表 = 段们.map { 对话条(it, 名表[it.角色编号] ?: "（未知角色）") }
            角色们 = 角们
        }
    }

    /** 新开一段对话：建会话 + 落开场白，然后回到聊天。 */
    fun 建会话(角色编号: Int, 完成后: (Int) -> Unit) {
        viewModelScope.launch {
            val 会号 = withContext(Dispatchers.IO) {
                val 段 = 会话库.建会话(库, 角色编号)
                val 开 = 角色库.取(库, 角色编号)?.开场白?.trim().orEmpty()
                if (开.isNotEmpty()) 会话库.追加消息(库, 段.编号, "角色", 开)
                段.编号
            }
            刷新()
            完成后(会号)
        }
    }

    fun 删(编号: Int) { viewModelScope.launch { withContext(Dispatchers.IO) { 会话库.删会话(库, 编号) }; 刷新() } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun 对话页(打开会话: (Int, Int) -> Unit, vm: 对话VM = viewModel()) {
    LaunchedEffect(Unit) { vm.刷新() }
    var 要删 by remember { mutableStateOf<对话条?>(null) }
    var 选角色 by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("对话") }) },
        floatingActionButton = { FloatingActionButton(onClick = { 选角色 = true }) { Text("＋") } },
    ) { 内边距 ->
        Box(Modifier.fillMaxSize().padding(内边距)) {
            if (vm.列表.isEmpty()) {
                Text("还没有对话。点右下角「＋」，选一个角色开始。",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.列表, key = { it.会话.编号 }) { 条 ->
                        Card(Modifier.fillMaxWidth().combinedClickable(
                            onClick = { 打开会话(条.会话.角色编号, 条.会话.编号) },
                            onLongClick = { 要删 = 条 },
                        )) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        条.会话.标题.ifBlank { "（未命名对话）" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "与「${条.角色名}」",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { 要删 = 条 }) { Text("删") }
                            }
                        }
                    }
                }
            }
        }
    }

    要删?.let { 条 ->
        AlertDialog(
            onDismissRequest = { 要删 = null },
            title = { Text("删掉这段对话？") },
            text = { Text("里面的消息会一起删掉，不能恢复。") },
            confirmButton = { TextButton(onClick = { vm.删(条.会话.编号); 要删 = null }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { 要删 = null }) { Text("取消") } },
        )
    }

    if (选角色) {
        AlertDialog(
            onDismissRequest = { 选角色 = false },
            title = { Text("和谁对话？") },
            text = {
                Column {
                    if (vm.角色们.isEmpty()) {
                        Text("还没有角色。先去「角色」页建一个。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    vm.角色们.forEach { 卡 ->
                        Text(
                            卡.名字,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    选角色 = false
                                    vm.建会话(卡.编号) { 会号 -> 打开会话(卡.编号, 会号) }
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { 选角色 = false }) { Text("取消") } },
        )
    }
}
