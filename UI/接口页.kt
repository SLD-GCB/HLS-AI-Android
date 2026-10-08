package UI

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import 后端.接口配置
import 后端.接口库
import 后端.协议们
import 后端.模型文件
import 后端.聊天引擎
import 后端.应用容器
import 后端.酒馆Json

/** 从采样参数 JSON 串里取一个键（取不到返回空串）。 */
private fun 参取(文: String, 键: String): String {
    if (文.isBlank()) return ""
    return try {
        val o = 酒馆Json.parseToJsonElement(文).jsonObject
        (o[键] as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) { "" }
}

/** 把表单各格拼成采样参数 JSON 串。**云端**只收 temperature/max_tokens；**本地**还收 top_k/repeat_penalty/n_ctx/n_threads/n_gpu_layers。 */
private fun 拼参数(
    协议: String,
    温度: String, 上限: String, 顶K: String, 复读: String, 上下文: String, gpu层: String, 线程: String,
): String {
    val o = buildJsonObject {
        if (协议 == "local") {
            温度.toDoubleOrNull()?.let { put("temperature", it) }
            上限.toIntOrNull()?.let { put("max_tokens", it) }
            顶K.toIntOrNull()?.let { put("top_k", it) }
            复读.toDoubleOrNull()?.let { put("repeat_penalty", it) }
            上下文.toIntOrNull()?.let { put("n_ctx", it) }
            线程.toIntOrNull()?.let { put("n_threads", it) }
            gpu层.toIntOrNull()?.let { put("n_gpu_layers", it) }
        } else {
            温度.toDoubleOrNull()?.let { put("temperature", it) }
            上限.toIntOrNull()?.let { put("max_tokens", it) }
        }
    }
    return if (o.isEmpty()) "" else o.toString()
}

class 接口VM(app: Application) : AndroidViewModel(app) {
    private val 库 = 应用容器.库(app)
    var 列表 by mutableStateOf<List<接口配置>>(emptyList()); private set
    var 本地模型 by mutableStateOf<List<String>>(emptyList()); private set
    var 提示 by mutableStateOf<String?>(null); private set

    fun 装载() {
        viewModelScope.launch {
            列表 = withContext(Dispatchers.IO) { 接口库.列(库) }
            本地模型 = withContext(Dispatchers.IO) { 模型文件.列本地(库).map { it.first } }
        }
    }

    fun 存(旧: 接口配置?, 名称: String, 协议: String, 地址: String, 密钥: String, 模型: String, 采样参数: String, 附加头: String, 完成后: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (旧 == null) 接口库.建(库, 名称, 协议, 地址, 密钥, 模型, 采样参数, 附加头)
                    else 接口库.改(库, 旧.编号) { it.copy(名称 = 名称, 协议 = 协议, 地址 = 地址, 密钥 = 密钥, 模型 = 模型, 采样参数 = 采样参数, 附加头 = 附加头) }
                }
                装载(); 完成后()
            } catch (e: Exception) { 提示 = e.message }
        }
    }

    fun 刷新本地模型() {
        viewModelScope.launch { 本地模型 = withContext(Dispatchers.IO) { 模型文件.列本地(库).map { it.first } } }
    }

    fun 删(编号: Int) { viewModelScope.launch { withContext(Dispatchers.IO) { 接口库.删(库, 编号) }; 装载() } }

    fun 发现(协议: String, 地址: String, 密钥: String, 完成后: (List<String>) -> Unit) {
        viewModelScope.launch {
            提示 = "正在问有哪些模型…"
            try {
                val 名们 = withContext(Dispatchers.IO) { 聊天引擎.列模型(接口配置(名称 = "临时", 协议 = 协议, 地址 = 地址, 密钥 = 密钥)) }
                提示 = if (名们.isEmpty()) "没问出模型" else null
                完成后(名们)
            } catch (e: Exception) { 提示 = e.message }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 接口页(返回: () -> Unit, 打开本地模型: () -> Unit = {}, vm: 接口VM = viewModel()) {
    LaunchedEffect(Unit) { vm.装载() }
    var 编辑目标 by remember { mutableStateOf<接口配置?>(null) }
    var 新建中 by remember { mutableStateOf(false) }
    var 要删 by remember { mutableStateOf<接口配置?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("接口配置") }, navigationIcon = { TextButton(onClick = 返回) { Text("返回") } }) },
        floatingActionButton = { FloatingActionButton(onClick = { 新建中 = true }) { Text("＋") } },
    ) { 内边距 ->
        Column(Modifier.fillMaxSize().padding(内边距)) {
            vm.提示?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary) }
            if (vm.列表.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有接口配置。点「＋」加一个：\n云端填地址+密钥+模型；本地选协议 local 再挑一个本地 GGUF。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.列表, key = { it.编号 }) { 接 ->
                    Card(
                        Modifier.fillMaxWidth().clickable { 编辑目标 = 接 },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(接.名称, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (接.协议 == "local") "本地模型　${接.模型.ifBlank { "（未选模型）" }}"
                                    else "协议 ${接.协议}　模型 ${接.模型.ifBlank { "（未填）" }}\n地址 ${接.地址.ifBlank { "（未填）" }}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { 编辑目标 = 接 }) { Text("改") }
                            TextButton(onClick = { 要删 = 接 }) { Text("删") }
                        }
                    }
                }
            }
        }
    }

    if (新建中) 接口表单(null, vm, 打开本地模型, { 新建中 = false }, { 新建中 = false })
    编辑目标?.let { 旧 -> 接口表单(旧, vm, 打开本地模型, { 编辑目标 = null }, { 编辑目标 = null }) }
    要删?.let { 接 ->
        AlertDialog(
            onDismissRequest = { 要删 = null },
            title = { Text("删掉「${接.名称}」？") },
            confirmButton = { TextButton(onClick = { vm.删(接.编号); 要删 = null }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { 要删 = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun 接口表单(旧: 接口配置?, vm: 接口VM, 打开本地模型: () -> Unit, 取消: () -> Unit, 完成: () -> Unit) {
    var 名称 by remember { mutableStateOf(旧?.名称 ?: "") }
    var 协议 by remember { mutableStateOf(旧?.协议 ?: "openai") }
    var 地址 by remember { mutableStateOf(旧?.地址 ?: "") }
    var 密钥 by remember { mutableStateOf(旧?.密钥 ?: "") }
    var 模型 by remember { mutableStateOf(旧?.模型 ?: "") }
    var 附加头 by remember { mutableStateOf(旧?.附加头 ?: "") }
    // 采样（两头共用）
    var 温度 by remember { mutableStateOf(参取(旧?.采样参数 ?: "", "temperature")) }
    var 上限 by remember { mutableStateOf(参取(旧?.采样参数 ?: "", "max_tokens")) }
    // 本地专属
    var 顶K by remember { mutableStateOf(参取(旧?.采样参数 ?: "", "top_k")) }
    var 复读 by remember { mutableStateOf(参取(旧?.采样参数 ?: "", "repeat_penalty")) }
    var 上下文 by remember { mutableStateOf(参取(旧?.采样参数 ?: "", "n_ctx")) }
    var gpu层 by remember { mutableStateOf(参取(旧?.采样参数 ?: "", "n_gpu_layers")) }
    // 线程数：空白表单按这台机器的大核数填一个合理值（本地推理的提速主要靠它）
    var 线程 by remember {
        mutableStateOf(
            参取(旧?.采样参数 ?: "", "n_threads").ifBlank {
                Runtime.getRuntime().availableProcessors().coerceIn(2, 6).toString()
            },
        )
    }
    var 协议展开 by remember { mutableStateOf(false) }
    var 本地展开 by remember { mutableStateOf(false) }
    var 模型展开 by remember { mutableStateOf(false) }
    var 看密钥 by remember { mutableStateOf(false) }
    var 发现结果 by remember { mutableStateOf<List<String>>(emptyList()) }

    val 本地 = 协议 == "local"

    AlertDialog(
        onDismissRequest = 取消,
        title = { Text(if (旧 == null) "新建接口" else "编辑接口") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(名称, { 名称 = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                // 协议
                Box {
                    OutlinedTextField(协议, {}, label = { Text("协议") }, readOnly = true, enabled = false,
                        modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { 协议展开 = true }) { Text("选") } })
                    DropdownMenu(expanded = 协议展开, onDismissRequest = { 协议展开 = false }) {
                        协议们.forEach { p ->
                            DropdownMenuItem(text = { Text(if (p == "local") "local（本地 GGUF，不联网）" else p) }, onClick = {
                                协议 = p
                                // 切到本地时给几个合理默认（对齐 PC 版空白表单）
                                if (p == "local") {
                                    if (温度.isBlank()) 温度 = "0.70"
                                    if (上限.isBlank()) 上限 = "512"
                                    if (顶K.isBlank()) 顶K = "40"
                                    if (复读.isBlank()) 复读 = "1.10"
                                    if (上下文.isBlank()) 上下文 = "4096"
                                    if (线程.isBlank()) 线程 = Runtime.getRuntime().availableProcessors().coerceIn(2, 6).toString()
                                    if (gpu层.isBlank()) gpu层 = "0"
                                }
                                协议展开 = false
                            })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (本地) {
                    // ── 仅本地：模型从本地 GGUF 里选 ──
                    // 每次打开表单都重读一遍本地模型，免得"下完了这里还看不到"
                    LaunchedEffect(Unit) { vm.刷新本地模型() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(模型, { 模型 = it }, label = { Text("模型（本地 GGUF 文件名）") },
                            singleLine = true, modifier = Modifier.weight(1f),
                            trailingIcon = { TextButton(onClick = { vm.刷新本地模型(); 本地展开 = true }) { Text("选") } })
                        DropdownMenu(expanded = 本地展开, onDismissRequest = { 本地展开 = false }) {
                            if (vm.本地模型.isEmpty()) DropdownMenuItem(text = { Text("（还没有本地模型）") }, onClick = { 本地展开 = false })
                            vm.本地模型.forEach { 名 -> DropdownMenuItem(text = { Text(名) }, onClick = { 模型 = 名; 本地展开 = false }) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本地模型 ${vm.本地模型.size} 个", style = MaterialTheme.typography.labelSmall,
                            color = if (vm.本地模型.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.刷新本地模型() }) { Text("刷新") }
                        TextButton(onClick = 打开本地模型) { Text("管理模型…") }
                    }

                    // ── 仅本地：n_ctx / 线程（本地推理提速的两个主要旋钮）──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(上下文, { 上下文 = it }, label = { Text("n_ctx") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(线程, { 线程 = it }, label = { Text("线程") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                    }
                    Text("• n_ctx 越小越快：聊天用 2048~4096 就够，8192 会慢不少。\n• 线程：按大核数填，一般 4~6（填太多反而慢）。\n• 想再快就换更小的模型 / 更低量化（Q4_K_M）。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))

                    // ── GPU 加速（OpenCL；默认关 = 纯 CPU）──
                    // 只在高通 Adreno 上有用（llama.cpp 的 OpenCL 后端就是给 Adreno 优化）；
                    // 其它机型开了会被 llama.cpp 忽略、退回 CPU，不会崩。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GPU 加速（OpenCL）", style = MaterialTheme.typography.bodyMedium)
                            Text("只对高通骁龙（Adreno）有效，通常快不少。改完退出重进 App 生效。",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = (gpu层.toIntOrNull() ?: 0) != 0,
                            onCheckedChange = { gpu层 = if (it) "99" else "0" },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // ── 仅本地：top_k / repeat_penalty + 推荐 ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(顶K, { 顶K = it }, label = { Text("top_k") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(复读, { 复读 = it }, label = { Text("repeat_penalty") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = { 温度 = "0.80"; 上限 = "1024"; 顶K = "40"; 复读 = "1.10" }) { Text("角色扮演推荐") }
                } else {
                    // ── 云端：地址 / 密钥 / 模型（可发现）/ 附加头 ──
                    OutlinedTextField(地址, { 地址 = it }, label = { Text("地址（例 https://api.deepseek.com/v1）") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        密钥, { 密钥 = it },
                        label = { Text("密钥（加密存本地）") },
                        singleLine = true,
                        visualTransformation = if (看密钥) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { 看密钥 = !看密钥 }) { Text(if (看密钥) "遮" else "看") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // 模型框 + 挂在它上面的「发现结果」下拉
                    Box {
                        OutlinedTextField(
                            模型, { 模型 = it },
                            label = { Text("模型（如 deepseek-chat）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (发现结果.isNotEmpty()) TextButton(onClick = { 模型展开 = true }) { Text("选") }
                            },
                        )
                        DropdownMenu(expanded = 模型展开, onDismissRequest = { 模型展开 = false }) {
                            发现结果.forEach { 名 ->
                                DropdownMenuItem(text = { Text(名) }, onClick = { 模型 = 名; 模型展开 = false; 发现结果 = emptyList() })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.发现(协议, 地址, 密钥) { 名们 -> 发现结果 = 名们; if (名们.isNotEmpty()) 模型展开 = true } },
                            enabled = 地址.isNotBlank()) { Text("发现模型") }
                        if (发现结果.isNotEmpty()) Text("找到 ${发现结果.size} 个，上面点「选」挑", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 采样（共用）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(温度, { 温度 = it }, label = { Text("temperature") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(上限, { 上限 = it }, label = { Text("max_tokens") }, singleLine = true, modifier = Modifier.weight(1f))
                }

                if (!本地) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(附加头, { 附加头 = it }, label = { Text("附加头（JSON，可空）") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = 名称.isNotBlank(), onClick = {
                val 参 = 拼参数(协议, 温度, 上限, 顶K, 复读, 上下文, gpu层, 线程)
                vm.存(
                    旧, 名称.trim(), 协议,
                    地址 = if (本地) "" else 地址.trim(),
                    密钥 = if (本地) "" else 密钥,
                    模型 = 模型.trim(),
                    采样参数 = 参,
                    附加头 = if (本地) "" else 附加头.trim(),
                    完成后 = 完成,
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = 取消) { Text("取消") } },
    )
}
