package UI

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import 后端.接口库
import 后端.下载源们
import 后端.代理设置
import 后端.本地引擎
import 后端.模型文件
import 后端.应用容器

class 配置VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    var 接口数 by mutableStateOf(0); private set
    var 模型数 by mutableStateOf(0); private set
    var 代理开 by mutableStateOf(代理设置.启用); private set
    var 代理机 by mutableStateOf(代理设置.主机); private set
    var 代理口 by mutableStateOf(代理设置.端口.toString()); private set
    var 测中 by mutableStateOf(false); private set
    var 测果 by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set

    fun 刷新() {
        viewModelScope.launch {
            接口数 = withContext(Dispatchers.IO) { 接口库.列(库).size }
            模型数 = withContext(Dispatchers.IO) { 模型文件.列本地(库).size }
        }
    }

    fun 设代理开(v: Boolean) { 代理开 = v; 代理设置.启用 = v; 代理设置.保存(库) }
    fun 设代理机(v: String) { 代理机 = v; 代理设置.主机 = v; 代理设置.保存(库) }
    fun 设代理口(v: String) { 代理口 = v; v.toIntOrNull()?.let { 代理设置.端口 = it; 代理设置.保存(库) } }

    /** 用当前设置逐个源测一遍，看到底哪个通、报什么错。 */
    fun 测试() {
        if (测中) return
        测中 = true; 测果 = emptyList()
        viewModelScope.launch {
            测果 = withContext(Dispatchers.IO) {
                下载源们.map { s ->
                    val r = runCatching { 模型文件.热门模型(s, "gguf", 1) }
                    val 错 = r.exceptionOrNull()
                    s.名 to if (错 == null) "✓ 通（${r.getOrThrow().size} 条）" else "✗ ${错.message}"
                }
            }
            测中 = false
        }
    }

    fun 数据目录() = 库.说明()
    fun 引擎状态() = 本地引擎.状态()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 配置页(打开接口: () -> Unit, 打开本地模型: () -> Unit, 打开模型管理: () -> Unit = {}, vm: 配置VM = viewModel()) {
    LaunchedEffect(Unit) { vm.刷新() }
    var 亮暗 by remember { mutableStateOf(主题设置.暗色) }

    Scaffold(topBar = { TopAppBar(title = { Text("配置") }) }) { 内边距 ->
        Column(
            Modifier.fillMaxSize().padding(内边距).verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            条目(
                标题 = "接口配置",
                副标题 = "在线 API（DeepSeek / OpenAI / Gemini…）与本地接口，共 ${vm.接口数} 套",
                点击 = 打开接口,
            )
            Spacer(Modifier.height(10.dp))
            条目(
                标题 = "本地模型",
                副标题 = "搜索 / 精选，下载 GGUF 模型",
                点击 = 打开本地模型,
            )
            Spacer(Modifier.height(10.dp))
            条目(
                标题 = "模型管理",
                副标题 = "正在下载 / 已下载，共 ${vm.模型数} 个模型（可删）",
                点击 = 打开模型管理,
            )
            Spacer(Modifier.height(10.dp))

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("深色模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("柔和的暗色，晚上更护眼", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = 亮暗, onCheckedChange = { 亮暗 = it; 主题设置.暗色 = it })
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    var 说明开 by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("代理（走梯子）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("只影响需要代理的源（如 HuggingFace）；国内源照常直连。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = vm.代理开, onCheckedChange = { vm.设代理开(it) })
                    }
                    if (vm.代理开) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(vm.代理机, { vm.设代理机(it) }, label = { Text("主机") },
                                singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(vm.代理口, { vm.设代理口(it) }, label = { Text("端口") },
                                singleLine = true, modifier = Modifier.width(110.dp))
                        }

                        // 填法说明（折叠，免得把下面挤没了）
                        TextButton(onClick = { 说明开 = !说明开 }) { Text(if (说明开) "填法说明 ▴" else "填法说明 ▾") }
                        if (说明开) {
                            Text("· 手机连电脑的梯子：填「电脑局域网IP:端口」（如 192.168.2.100:7890）；Clash 要开「允许局域网」，手机和电脑同一 WiFi。\n· 手机自己装了梯子：填 127.0.0.1:端口。\n· 安卓模拟器：填 10.0.2.2:端口（模拟器访问电脑的地址）。\n· 注意：「127.0.0.1」指本机——手机/模拟器上填它连的是自己，不是电脑。",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }

                        // 测试按钮
                        TextButton(enabled = !vm.测中, onClick = { vm.测试() }) { Text(if (vm.测中) "测试中…" else "测试连接") }
                        if (vm.测果.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                            ) {
                                vm.测果.forEach { (名, 果) ->
                                    Text(
                                        "$名：$果",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (果.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("本地引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(vm.引擎状态(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("数据目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(vm.数据目录(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun 条目(标题: String, 副标题: String, 点击: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { 点击() }) {
        Column(Modifier.padding(16.dp)) {
            Text(标题, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(副标题, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
