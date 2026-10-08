package UI

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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

class 会话VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    var 角色 by mutableStateOf<角色卡?>(null); private set
    var 列表 by mutableStateOf<List<会话>>(emptyList()); private set

    fun 打开(角色编号: Int) {
        viewModelScope.launch {
            角色 = withContext(Dispatchers.IO) { 角色库.取(库, 角色编号) }
            刷新(角色编号)
        }
    }

    private suspend fun 刷新(角色编号: Int) { 列表 = withContext(Dispatchers.IO) { 会话库.列会话(库, 角色编号) } }

    fun 新建(角色编号: Int, 完成后: (Int) -> Unit) {
        viewModelScope.launch {
            val 新号 = withContext(Dispatchers.IO) {
                val 段 = 会话库.建会话(库, 角色编号)
                val 开 = 角色库.取(库, 角色编号)?.开场白?.trim().orEmpty()
                if (开.isNotEmpty()) 会话库.追加消息(库, 段.编号, "角色", 开)
                段.编号
            }
            刷新(角色编号); 完成后(新号)
        }
    }

    fun 删(角色编号: Int, 编号: Int) {
        viewModelScope.launch { withContext(Dispatchers.IO) { 会话库.删会话(库, 编号) }; 刷新(角色编号) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun 会话页(角色编号: Int, 打开会话: (Int) -> Unit, 返回: () -> Unit, vm: 会话VM = viewModel()) {
    LaunchedEffect(角色编号) { vm.打开(角色编号) }
    var 要删 by remember { mutableStateOf<会话?>(null) }

    Scaffold(
        topBar = { TopAppBar(
            title = { Text(vm.角色?.名字 ?: "会话") },
            navigationIcon = { TextButton(onClick = 返回) { Text("返回") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { vm.新建(角色编号) { 号 -> 打开会话(号) } }) { Text("＋") } },
    ) { 内边距 ->
        Box(Modifier.fillMaxSize().padding(内边距)) {
            if (vm.列表.isEmpty()) Text("还没有会话。点右下角「＋」开一段。", modifier = Modifier.align(Alignment.Center).padding(24.dp))
            else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.列表, key = { it.编号 }) { 段 ->
                    Card(Modifier.fillMaxWidth().combinedClickable(
                        onClick = { 打开会话(段.编号) }, onLongClick = { 要删 = 段 })) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(段.标题.ifBlank { "（未命名会话）" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("编号 ${段.编号}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { 要删 = 段 }) { Text("删") }
                        }
                    }
                }
            }
        }
    }

    要删?.let { 段 ->
        AlertDialog(
            onDismissRequest = { 要删 = null },
            title = { Text("删掉这段会话？") },
            text = { Text("里面的消息会一起删掉，不能恢复。") },
            confirmButton = { TextButton(onClick = { vm.删(角色编号, 段.编号); 要删 = null }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { 要删 = null }) { Text("取消") } },
        )
    }
}
