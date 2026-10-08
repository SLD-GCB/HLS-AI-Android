package UI

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private val 顶层页 = listOf("角色", "对话", "配置")

/** 导航：底部三个标签（角色 / 对话 / 配置）；点条目再逐级进入。 */
@Composable
fun 导航() {
    val 导航 = rememberNavController()
    val 栈顶 by 导航.currentBackStackEntryAsState()
    val 现路由 = 栈顶?.destination?.route
    val 显示底栏 = 现路由 in 顶层页

    Scaffold(
        bottomBar = {
            if (显示底栏) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    @Composable
                    fun 项(路由: String, 字: String, 名: String) {
                        NavigationBarItem(
                            selected = 现路由 == 路由,
                            onClick = { 导航.去顶层(路由) },
                            icon = { Text(字) },
                            label = { Text(名) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    项("角色", "角", "角色")
                    项("对话", "话", "对话")
                    项("配置", "设", "配置")
                }
            }
        },
    ) { 内边距 ->
        NavHost(
            navController = 导航,
            startDestination = "角色",
            // ⚠ `consumeWindowInsets` 不能省：外层已经按系统栏塞过一圈 padding 了，
            //   不"标记为已消费"的话，里面的页面再用键盘 insets 时会**再加一遍**，
            //   输入框就会悬在键盘上方空出一截。
            modifier = Modifier.padding(内边距).consumeWindowInsets(内边距),
        ) {
            composable("角色") {
                角色页(打开角色 = { 导航.navigate("角色详情/$it") })
            }
            composable(
                "角色详情/{角色编号}",
                arguments = listOf(navArgument("角色编号") { type = NavType.IntType }),
            ) { 项 ->
                val 号 = 项.arguments?.getInt("角色编号") ?: 0
                角色详情页(角色编号 = 号, 返回 = { 导航.popBackStack() })
            }
            composable("对话") {
                对话页(打开会话 = { 角色号, 会话号 -> 导航.navigate("聊天/$角色号/$会话号") })
            }
            composable("配置") {
                配置页(
                    打开接口 = { 导航.navigate("接口") },
                    打开本地模型 = { 导航.navigate("本地模型") },
                    打开模型管理 = { 导航.navigate("模型管理") },
                )
            }
            composable(
                "聊天/{角色编号}/{会话编号}",
                arguments = listOf(navArgument("角色编号") { type = NavType.IntType }, navArgument("会话编号") { type = NavType.IntType }),
            ) { 项 ->
                val 角 = 项.arguments?.getInt("角色编号") ?: 0
                val 会 = 项.arguments?.getInt("会话编号") ?: 0
                聊天页(角色编号 = 角, 会话编号 = 会, 返回 = { 导航.popBackStack() },
                    打开上下文 = { 导航.navigate("上下文/$角/$会") },
                    打开设置 = { 导航.navigate("会话设置/$角/$会") })
            }
            composable(
                "会话设置/{角色编号}/{会话编号}",
                arguments = listOf(navArgument("角色编号") { type = NavType.IntType }, navArgument("会话编号") { type = NavType.IntType }),
            ) { 项 ->
                val 会 = 项.arguments?.getInt("会话编号") ?: 0
                会话设置页(会话编号 = 会, 返回 = { 导航.popBackStack() })
            }
            composable(
                "上下文/{角色编号}/{会话编号}",
                arguments = listOf(navArgument("角色编号") { type = NavType.IntType }, navArgument("会话编号") { type = NavType.IntType }),
            ) { 项 ->
                val 角 = 项.arguments?.getInt("角色编号") ?: 0
                val 会 = 项.arguments?.getInt("会话编号") ?: 0
                上下文页(角色编号 = 角, 会话编号 = 会, 返回 = { 导航.popBackStack() })
            }
            composable("接口") { 接口页(返回 = { 导航.popBackStack() }, 打开本地模型 = { 导航.navigate("本地模型") }) }
            composable("本地模型") { 本地模型页(返回 = { 导航.popBackStack() }) }
            composable("模型管理") { 模型管理页(返回 = { 导航.popBackStack() }) }
        }
    }
}

/** 点底栏：回到该顶层页（不叠栈）。 */
private fun NavHostController.去顶层(路由: String) {
    navigate(路由) {
        popUpTo("角色") { inclusive = false; saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
