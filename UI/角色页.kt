package UI

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

class 角色VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    var 列表 by mutableStateOf<List<角色卡>>(emptyList()); private set
    var 头像表 by mutableStateOf<Map<Int, ByteArray>>(emptyMap()); private set

    fun 刷新() {
        viewModelScope.launch {
            val 卡们 = withContext(Dispatchers.IO) { 角色库.列(库) }
            val 头 = withContext(Dispatchers.IO) {
                卡们.mapNotNull { 卡 -> 库.取头像(卡.编号)?.first?.let { 卡.编号 to it } }.toMap()
            }
            列表 = 卡们; 头像表 = 头
        }
    }

    fun 删(编号: Int) { viewModelScope.launch { withContext(Dispatchers.IO) { 角色库.删(库, 编号) }; 刷新() } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun 角色页(打开角色: (Int) -> Unit = {}, vm: 角色VM = viewModel()) {
    // 每次回到本页都刷新（详情页存完回来能看到新的）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.刷新() }
    var 要删 by remember { mutableStateOf<角色卡?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("角色") }) },
        floatingActionButton = { FloatingActionButton(onClick = { 打开角色(0) }) { Text("＋") } },
    ) { 内边距 ->
        Box(Modifier.fillMaxSize().padding(内边距)) {
            if (vm.列表.isEmpty()) {
                Text("还没有角色。点右下角「＋」建一个。",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.列表, key = { it.编号 }) { 卡 ->
                        Card(Modifier.fillMaxWidth().combinedClickable(
                            onClick = { 打开角色(卡.编号) },
                            onLongClick = { 要删 = 卡 },
                        )) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val 图 = vm.头像表[卡.编号]
                                    if (图 != null) {
                                        val 位 = remember(图) { BitmapFactory.decodeByteArray(图, 0, 图.size)?.asImageBitmap() }
                                        if (位 != null) Image(位, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        else Text(卡.名字.take(1))
                                    } else {
                                        Text(卡.名字.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(卡.名字, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (卡.简介.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(卡.简介, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton(onClick = { 要删 = 卡 }) { Text("删") }
                            }
                        }
                    }
                }
            }
        }
    }

    要删?.let { 卡 ->
        AlertDialog(
            onDismissRequest = { 要删 = null },
            title = { Text("删掉「${卡.名字}」？") },
            text = { Text("他的会话、消息、头像、背景都会一起删掉，不能恢复。") },
            confirmButton = { TextButton(onClick = { vm.删(卡.编号); 要删 = null }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { 要删 = null }) { Text("取消") } },
        )
    }
}
