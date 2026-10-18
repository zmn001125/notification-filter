package com.local.notificationfilter.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.local.notificationfilter.ui.screens.blacklist.BlacklistScreen
import com.local.notificationfilter.ui.screens.detail.NotificationDetailScreen
import com.local.notificationfilter.ui.screens.home.HomeScreen
import com.local.notificationfilter.ui.screens.help.HelpScreen
import com.local.notificationfilter.ui.screens.permission.PermissionGuideScreen
import com.local.notificationfilter.ui.screens.record.RecordScreen
import com.local.notificationfilter.ui.screens.record.ResultFilter
import com.local.notificationfilter.ui.screens.rules.RuleEditScreen
import com.local.notificationfilter.ui.screens.rules.RuleListScreen
import com.local.notificationfilter.ui.screens.search.SearchScreen
import com.local.notificationfilter.ui.screens.stats.StatsScreen
import com.local.notificationfilter.ui.screens.test.RuleTestScreen

/**
 * 全部路由集中管理（架构共享约定 2）。
 * 页面编号对齐 PRD / 线框稿：P1 权限引导、P2 首页、P3/P4 记录、P5 规则列表、
 * P6 规则编辑、P7 完整字段详情、P8 黑名单、P9 搜索、P10 规则测试、P11 统计。
 */
object NfRoutes {

    const val PERMISSION = "permission"
    const val HOME = "home"
    const val RECORD = "record"

    /**
     * P3/P4 带预置过滤的记录页（V9 需求 1）。
     *
     * 首页「今日消除 / 今日放行」点进来时预选结果过滤（逗号分隔的 [ResultFilter] 名）。
     * **为什么不把参数塞进 [RECORD]**：底部导航用 `RECORD` 这个裸路由，
     * 若把它改成带 query 的模板，底栏那条跳转会匹配不上；因此单开一条路由，
     * 两条各自注册一个 composable。
     */
    const val RECORD_FILTERED = "record?results={results}"
    const val ARG_RESULTS = "results"

    /** 首页跳记录页并预置过滤（多个用逗号分隔）。 */
    fun record(results: String? = null): String =
        "record?results=${Uri.encode(results ?: "")}"
    const val RULES = "rules"
    const val STATS = "stats"
    const val BLACKLIST = "blacklist"
    const val TEST = "test"

    /** V10：使用说明（首页顶部菜单进入，不占底部 Tab）。 */
    const val HELP = "help"

    /** V11：备份与恢复（首页顶部菜单进入，不占底部 Tab）。 */
    const val BACKUP = "backup"

    const val RULE_EDIT = "rule_edit?ruleId={ruleId}&prefillPkg={prefillPkg}"
    const val DETAIL = "detail/{recordId}?key={key}"
    const val SEARCH = "search?package={pkg}&from={from}"

    const val ARG_RULE_ID = "ruleId"
    const val ARG_RECORD_ID = "recordId"
    const val ARG_KEY = "key"
    const val ARG_PACKAGE = "pkg"
    const val ARG_FROM = "from"
    const val ARG_PREFILL_PKG = "prefillPkg"

    const val NO_RULE_ID = -1L
    const val NO_RECORD_ID = -1L

    /** P6：ruleId 为空表示新建；prefillPkg 用于从详情页「为该应用建规」预填 ONLY+包名。 */
    fun ruleEdit(ruleId: Long? = null, prefillPkg: String? = null): String =
        "rule_edit?ruleId=${ruleId ?: NO_RULE_ID}&prefillPkg=${Uri.encode(prefillPkg ?: "")}"

    /** P7：recordId 为 NO_RECORD_ID 时表示按 key 定位。 */
    fun detail(recordId: Long, key: String? = null): String =
        "detail/$recordId?key=${Uri.encode(key ?: "")}"

    fun detailByKey(key: String): String = detail(NO_RECORD_ID, key)

    /** P9：from 记录入口（home / stats），目前仅用于埋点与返回行为。 */
    fun search(pkg: String? = null, from: String = "home"): String =
        "search?package=${Uri.encode(pkg ?: "")}&from=$from"
}

/** 底部四个 Tab。 */
private enum class BottomTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    HOME(NfRoutes.HOME, "首页", Icons.Filled.Home, Icons.Outlined.Home),
    RECORD(NfRoutes.RECORD, "记录", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    RULES(NfRoutes.RULES, "规则", Icons.Filled.List, Icons.Outlined.List),
    STATS(NfRoutes.STATS, "统计", Icons.Filled.BarChart, Icons.Outlined.BarChart),
}

private val TAB_ROUTES = BottomTab.entries.map { it.route }.toSet()

@Composable
fun NfNavHost(startDestination: String = NfRoutes.PERMISSION) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in TAB_ROUTES) {
                // V6-⑥：底栏容器色走 surfaceContainer（主题色调和，见 AppThemes），
                // 选中项强调色对齐主题主色（默认 M3 用 secondaryContainer 绿色药丸，与主题脱节）
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    BottomTab.entries.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab.route) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // V5-1：只消费底部 inset（底部栏/手势条），顶部留给各屏 TopAppBar 延伸进状态栏区域，
        // 使状态栏与顶栏天然同色（顶栏一体化）。此前整体 padding(innerPadding) 会把内屏顶栏
        // 压到状态栏下方，状态栏区域透出外层 Scaffold 的 background 色，与顶栏 surface 色割裂。
        // 唯一无顶栏的 PermissionGuideScreen 已自行加 statusBarsPadding。
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            // —— P1 权限引导 ——
            composable(NfRoutes.PERMISSION) {
                PermissionGuideScreen(
                    onFinished = {
                        navController.navigate(NfRoutes.HOME) {
                            popUpTo(NfRoutes.PERMISSION) { inclusive = true }
                        }
                    },
                )
            }

            // —— P2 首页 ——
            composable(NfRoutes.HOME) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onOpenDetail = { recordId -> navController.navigate(NfRoutes.detail(recordId)) },
                    onReRunGuide = { navController.navigate(NfRoutes.PERMISSION) },
                )
            }

            // —— P3/P4 记录（最近通知 + 明细流双子页签） ——
            composable(NfRoutes.RECORD) {
                RecordScreen(
                    onOpenDetail = { recordId -> navController.navigate(NfRoutes.detail(recordId)) },
                    onOpenSearch = { navController.navigate(NfRoutes.search()) },
                )
            }

            // V9 需求 1：首页统计卡跳进来的「已预置过滤」记录页
            composable(
                route = NfRoutes.RECORD_FILTERED,
                arguments = listOf(
                    navArgument(NfRoutes.ARG_RESULTS) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                RecordScreen(
                    onOpenDetail = { recordId -> navController.navigate(NfRoutes.detail(recordId)) },
                    onOpenSearch = { navController.navigate(NfRoutes.search()) },
                    presetResults = ResultFilter.fromNames(
                        entry.arguments?.getString(NfRoutes.ARG_RESULTS),
                    ),
                )
            }

            // —— P5 规则列表 ——
            composable(NfRoutes.RULES) {
                RuleListScreen(
                    onCreateRule = { navController.navigate(NfRoutes.ruleEdit()) },
                    onEditRule = { ruleId -> navController.navigate(NfRoutes.ruleEdit(ruleId)) },
                    onOpenBlacklist = { navController.navigate(NfRoutes.BLACKLIST) },
                    onOpenTest = { navController.navigate(NfRoutes.TEST) },
                )
            }

            // —— P11 统计 ——
            composable(NfRoutes.STATS) {
                StatsScreen(
                    onOpenSearchForApp = { pkg ->
                        navController.navigate(NfRoutes.search(pkg = pkg, from = "stats"))
                    },
                )
            }

            // —— P6 规则编辑（ruleId = -1 为新建；prefillPkg 从详情页建规预填） ——
            composable(
                route = NfRoutes.RULE_EDIT,
                arguments = listOf(
                    navArgument(NfRoutes.ARG_RULE_ID) {
                        type = NavType.LongType
                        defaultValue = NfRoutes.NO_RULE_ID
                    },
                    navArgument(NfRoutes.ARG_PREFILL_PKG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val ruleId = entry.arguments?.getLong(NfRoutes.ARG_RULE_ID) ?: NfRoutes.NO_RULE_ID
                val prefillPkg = entry.arguments?.getString(NfRoutes.ARG_PREFILL_PKG)
                    ?.takeIf { it.isNotBlank() }
                RuleEditScreen(
                    ruleId = ruleId,
                    prefillPkg = prefillPkg,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { recordId -> navController.navigate(NfRoutes.detail(recordId)) },
                )
            }

            // —— P7 完整字段详情 ——
            composable(
                route = NfRoutes.DETAIL,
                arguments = listOf(
                    navArgument(NfRoutes.ARG_RECORD_ID) { type = NavType.LongType },
                    navArgument(NfRoutes.ARG_KEY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val recordId =
                    entry.arguments?.getLong(NfRoutes.ARG_RECORD_ID) ?: NfRoutes.NO_RECORD_ID
                val key = entry.arguments?.getString(NfRoutes.ARG_KEY)?.takeIf { it.isNotBlank() }
                NotificationDetailScreen(
                    recordId = recordId,
                    locateKey = key,
                    onBack = { navController.popBackStack() },
                    onCreateRuleForApp = { pkg ->
                        navController.navigate(NfRoutes.ruleEdit(prefillPkg = pkg))
                    },
                    onEditRule = { ruleId ->
                        navController.navigate(NfRoutes.ruleEdit(ruleId))
                    },
                    // V9 需求 5：跳到「该应用的所有消息」（搜索页按包名过滤）
                    onOpenAppMessages = { pkg ->
                        navController.navigate(NfRoutes.search(pkg))
                    },
                )
            }

            // —— P8 黑名单 ——
            composable(NfRoutes.BLACKLIST) {
                BlacklistScreen(onBack = { navController.popBackStack() })
            }

            // —— P9 搜索 ——
            composable(
                route = NfRoutes.SEARCH,
                arguments = listOf(
                    navArgument(NfRoutes.ARG_PACKAGE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(NfRoutes.ARG_FROM) {
                        type = NavType.StringType
                        defaultValue = "home"
                    },
                ),
            ) { entry ->
                val pkg = entry.arguments?.getString(NfRoutes.ARG_PACKAGE)?.takeIf { it.isNotBlank() }
                SearchScreen(
                    presetPackage = pkg,
                    onOpenDetail = { recordId -> navController.navigate(NfRoutes.detail(recordId)) },
                    onBack = { navController.popBackStack() },
                )
            }

            // —— P10 规则批量测试 ——
            composable(NfRoutes.TEST) {
                RuleTestScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { recordId -> navController.navigate(NfRoutes.detail(recordId)) },
                )
            }

            // —— V10 使用说明 ——
            composable(NfRoutes.HELP) {
                HelpScreen(onBack = { navController.popBackStack() })
            }

            // —— V11 备份与恢复（首页顶部菜单进入） ——
            composable(NfRoutes.BACKUP) {
                com.local.notificationfilter.ui.screens.backup.BackupScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Tab 切换：保存各 Tab 状态、栈内单例。
 * V5-6：优先确定性 popBackStack 回到已在栈内的 Tab——修复从首页「查看全部」普通
 * navigate(RECORD) 压栈后，底部栏点「首页」被 navigate+launchSingleTop+restoreState
 * 组合吞掉、无法回首页的问题。目标不在栈内（首次进入）才走 navigate + 状态保存/恢复。
 */
private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    if (popBackStack(route, inclusive = false)) return
    navigate(route) {
        // startDestinationId：本 graph 的起始目的地即为 Tab 根，语义等价于 findStartDestination()
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
