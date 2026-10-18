package com.local.notificationfilter

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.local.notificationfilter.ui.theme.AppTheme
import com.local.notificationfilter.ui.theme.DarkMode
import com.local.notificationfilter.ui.navigation.NfNavHost
import com.local.notificationfilter.ui.navigation.NfRoutes
import com.local.notificationfilter.ui.theme.NotificationFilterTheme
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 单 Activity，承载全部 11 个页面的 NavHost。
 * 起始页由「引导完成标记」决定：未完成 → 权限引导页（P1）；已完成 → 首页（P2）。
 *
 * V8：额外承担「在多任务（最近任务）界面隐藏本 App」的运行时应用职责 ——
 * 安卓没有运行时可切的 Manifest 属性（`android:excludeFromRecents` 写死即永久生效，
 * 会失去开关语义），因此只能调 `AppTask.setExcludeFromRecents()` 改任务属性。
 * 三处触发点（开关切换 / 每次 onResume / 启动）共用同一份实现
 * [applyRecentsVisibility] + [applyFromSettings]，不重复写逻辑。
 */
class MainActivity : ComponentActivity() {

    /** 懒取容器，避免 onCreate 内外重复强转。 */
    private val container: AppContainer
        get() = (application as NfApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // V8 触发点①：启动时按已保存的设置应用一次（任务属性不跨进程保留，必须每次启动重设）
        lifecycleScope.launch { applyFromSettings() }

        // V8 触发点②：开关切换时立即生效。
        // 开关值只从 DataStore 来（HomeViewModel 只写不碰 Activity），这里订阅同一条 Flow；
        // DataStore 首次会回放当前值，drop(1) 跳过它（那一刻已由上面的 onCreate 应用过），
        // 只对「用户真的改了开关」做出反应，避免启动时重复 setExcludeFromRecents。
        lifecycleScope.launch {
            container.settingsRepository.hideFromRecents
                .drop(1)
                // DataStore 读盘异常不应连带崩掉 Activity（本 Flow 只负责「立即生效」这条链路，
                // onResume 那条兜底链路仍在），故就地吞掉并记一条日志。
                .catch { NfLog.w(this@MainActivity, "订阅「多任务中隐藏」设置失败：${it.message}") }
                .collect { enabled -> applyRecentsVisibility(enabled) }
        }

        lifecycleScope.launch {
            val startDestination =
                if (container.settingsRepository.guideCompleted.first()) NfRoutes.HOME
                else NfRoutes.PERMISSION

            setContent {
                // V4-5：色相主题 + 深浅模式两个正交开关，实时随用户选择重建配色
                val themeName by container.settingsRepository.theme
                    .collectAsState(initial = AppTheme.INDIGO.name)
                val darkModeName by container.settingsRepository.darkMode
                    .collectAsState(initial = DarkMode.SYSTEM.name)
                NotificationFilterTheme(
                    theme = AppTheme.of(themeName),
                    darkMode = DarkMode.of(darkModeName),
                ) {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        NfNavHost(startDestination = startDestination)
                    }
                }
            }
        }
    }

    /**
     * V8 触发点③：每次回到前台重申一次。
     *
     * 必要性：任务可能被系统销毁重建（「返回键退出后再从桌面点图标」、
     * 系统内存回收、部分 ROM 的最近任务重建），新任务不会继承旧的
     * excludeFromRecents 属性，所以 onResume 必须重新应用一次。
     * 幂等、无 UI 副作用，连续调用开销仅一次 binder 调用。
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { applyFromSettings() }
    }

    /**
     * 从设置仓库读一次当前值并应用。
     *
     * 读取失败时按 `false` 兜底：宁可让 App 显示在最近任务里（用户总能唤起），
     * 也不要在数据层异常时把入口「藏」起来让用户找不到应用。
     */
    private suspend fun applyFromSettings() {
        val enabled = runCatching {
            container.settingsRepository.isHideFromRecents()
        }.onFailure {
            NfLog.w(this, "读取「多任务中隐藏」设置失败，按不隐藏处理：${it.message}")
        }.getOrDefault(false)
        applyRecentsVisibility(enabled)
    }

    /**
     * 把「是否在最近任务中隐藏」施加到当前任务（唯一实现，三处触发点共用）。
     *
     * 实现说明：
     * - `ActivityManager.getAppTasks()` 自 API 30 起标记 deprecated，但**查询本应用自己的
     *   任务仍然可用**（只是不允许查别人的任务）；
     * - `AppTask.setExcludeFromRecents(boolean)` 是 API 21+ 的公开运行时接口，正是
     *   Manifest 属性 `android:excludeFromRecents` 的等价开关；
     * - 任何异常（个别 ROM 禁用 appTasks / 返回空列表 / SecurityException）一律
     *   **静默降级**：只记一条 [NfLog.w]，不崩、不弹错、不改用户的其他设置。
     *   降级表现 = 行为等同开关关闭（App 仍显示在最近任务里）。
     *
     * @param enabled true = 在最近任务中隐藏；false = 正常显示。
     */
    @Suppress("DEPRECATION") // getAppTasks() 自 API 30 起 deprecated，但查本应用任务仍可用且无替代 API
    private fun applyRecentsVisibility(enabled: Boolean) {
        val am = getSystemService(ActivityManager::class.java)
        if (am == null) {
            NfLog.w(this, "ActivityManager 不可用，多任务可见性未应用（hide=$enabled）")
            return
        }
        val task = runCatching { am.appTasks?.firstOrNull() }
            .onFailure {
                NfLog.w(this, "读取 AppTask 失败，多任务可见性未应用（hide=$enabled）：${it.message}")
            }
            .getOrNull()
        if (task == null) {
            // 空列表在部分 ROM 上确实会出现（如本应用当前没有可见任务的主线程时机），静默降级。
            NfLog.w(this, "取不到当前任务（appTasks 为空），多任务可见性未应用（hide=$enabled）")
            return
        }
        runCatching { task.setExcludeFromRecents(enabled) }
            .onSuccess { NfLog.i(this, "多任务可见性已应用：hide=$enabled") }
            .onFailure {
                NfLog.w(this, "setExcludeFromRecents 调用失败（hide=$enabled）：${it.message}")
            }
    }
}
