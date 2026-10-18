package com.local.notificationfilter.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat

/** 权限类型（引导页各项：PRD 四项 + Android 13+ 系统通知权限 + V4 悬浮窗 + V7 无障碍服务）。 */
enum class PermissionType(
    val title: String,
    val desc: String,
    /** false = HyperOS 私有页，无标准查询 API，需用户手动确认。 */
    val autoDetect: Boolean,
) {
    NOTIFICATION_LISTENER(
        "通知使用权",
        "监听本机全部 App 的通知，这是过滤的前提",
        true,
    ),
    POST_NOTIFICATIONS(
        "系统通知权限",
        "允许本 App 发出「替换」后的通知与常驻状态通知",
        true,
    ),
    AUTOSTART(
        "自启动",
        "服务被系统杀掉后能自动恢复，避免漏通知",
        false,
    ),
    BACKGROUND_POPUP(
        "后台弹窗",
        "「替换」「朗读」动作需要能在后台弹出通知",
        false,
    ),
    BATTERY_UNRESTRICTED(
        "电池无限制",
        "防止系统后台限制导致监听中断",
        true,
    ),
    /**
     * V4：悬浮窗（显示在其他应用上层）。
     * **只有启用「弹幕」或「自定义位置悬浮」动作时才需要**，未启用时不阻塞核心过滤。
     */
    OVERLAY(
        "悬浮窗权限",
        "「弹幕」「自定义位置悬浮」需要在其他应用上层绘制",
        true,
    ),

    /**
     * V7 需求 7-⑥：无障碍服务（只读当前前台应用的包名）。
     * **只有启用「打开了某个 App」条件时才需要**；未启用时不展示、不阻塞核心过滤，
     * 也绝不因为「不需要」而自动关闭（架构 §3.6.1，Q2 定案）。
     */
    ACCESSIBILITY(
        "无障碍服务",
        "「打开了某个 App 时显示/隐藏卡片」需要读取当前前台应用",
        true,
    ),
}

data class PermissionStatus(
    val type: PermissionType,
    val granted: Boolean,
)

/**
 * 权限检测与跳转（T07）。HyperOS 私有组件（securitycenter）失败时统一降级到
 * 应用详情页（架构 §1.3(1)）；自启动 / 后台弹窗无标准查询 API，状态由用户手动确认
 * （manualPerms 持久化于 DataStore）。
 */
object PermissionChecker {

    /** `Settings.Secure` 里「已启用的无障碍服务」键（系统私有键，非公开常量，故本地定义）。 */
    private const val ACCESSIBILITY_SERVICES_KEY = "enabled_accessibility_services"


    fun checkAll(context: Context, manualPerms: Set<String>): List<PermissionStatus> =
        PermissionType.entries.map { type ->
            PermissionStatus(type, isGranted(context, type, manualPerms))
        }

    fun isGranted(context: Context, type: PermissionType, manualPerms: Set<String>): Boolean =
        when (type) {
            PermissionType.NOTIFICATION_LISTENER -> isListenerGranted(context)
            PermissionType.POST_NOTIFICATIONS -> areNotificationsEnabled(context)
            PermissionType.BATTERY_UNRESTRICTED -> isBatteryUnrestricted(context)
            PermissionType.AUTOSTART -> type.name in manualPerms
            PermissionType.BACKGROUND_POPUP -> type.name in manualPerms
            PermissionType.OVERLAY -> canDrawOverlays(context)
            PermissionType.ACCESSIBILITY -> isAccessibilityGranted(context)
        }

    /** 悬浮窗权限（Android 6+ 走 Settings.canDrawOverlays，无法查询时保守按未授权）。 */
    fun canDrawOverlays(context: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }.getOrDefault(false)

    /**
     * 无障碍服务是否已开启（V7 需求 7-⑥）。
     *
     * 主判据与 [isListenerGranted] 完全同口径：`enabled_accessibility_services` 是
     * 「包名/服务类名」组件全名列表（以 ':' 分隔），按 '/' 取包名段比对，
     * 兼容「只写包名」的 ROM，也避免整串 equals 永远不成立的 V1 式错误。
     *
     * 兜底判据：HyperOS 上该 Secure 键偶发与实际生效列表不同步（用户刚开启/刚关闭，
     * 键值滞后），再查一次系统权威列表 [AccessibilityManager.getEnabledAccessibilityServiceList]。
     * 两条判据是**或**关系（任一为真即视为已授权）：宁可多认「已授权」，也不要让用户
     * 明明开了服务却看到红色未授权（该权限 autoDetect 且没有「我已授权」手动兜底）。
     */
    fun isAccessibilityGranted(context: Context): Boolean {
        if (isComponentEnabledInSecureSetting(context, ACCESSIBILITY_SERVICES_KEY)) return true
        return runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return@runCatching false
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .orEmpty()
                .any { info ->
                    val id = info?.id ?: return@any false
                    id.substringBefore('/').equals(context.packageName, ignoreCase = true)
                }
        }.getOrDefault(false)
    }

    /**
     * 在 `Settings.Secure` 的「已启用组件列表」里查找本包（通知使用权 / 无障碍服务共用）。
     *
     * @param key `enabled_notification_listeners` 或 `enabled_accessibility_services`。
     */
    private fun isComponentEnabledInSecureSetting(context: Context, key: String): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, key)
        }.getOrNull().orEmpty()
        if (enabled.isEmpty()) return false
        val pkg = context.packageName
        return enabled.split(':').any { entry ->
            val component = entry.trim()
            component.isNotEmpty() &&
                component.substringBefore('/').equals(pkg, ignoreCase = true)
        }
    }

    /**
     * 通知使用权是否已授权（V2 需求⑥修复）。
     *
     * 系统 `enabled_notification_listeners` 的值是「包名/服务类名」组件全名列表
     * （以 ':' 分隔），V1 用整串 equals 包名永远不成立 → 已授权也显示未授权。
     * 现按 '/' 拆出包名段精确比对，与 BootReceiver 的 contains(包名) 口径一致；
     * 兼容极少数只写包名的 ROM。
     */
    fun isListenerGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val pkg = context.packageName
        return enabled.split(':').any { entry ->
            entry.trim().substringBefore('/').equals(pkg, ignoreCase = true)
        }
    }

    private fun areNotificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)

    private fun isBatteryUnrestricted(context: Context): Boolean =
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    /**
     * 跳转对应系统设置页；@return true = 已找到可用的系统页面。
     * 依次尝试专用页 → HyperOS 私有页 → 应用详情页。
     */
    fun openSettings(type: PermissionType, context: Context): Boolean {
        val candidates: List<Intent> = when (type) {
            PermissionType.NOTIFICATION_LISTENER ->
                listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

            PermissionType.BATTERY_UNRESTRICTED -> listOf(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )

            PermissionType.AUTOSTART -> listOf(
                hyperOsPage(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
                hyperOsPermissionEditor(context),
            )

            PermissionType.BACKGROUND_POPUP -> listOf(
                hyperOsPermissionEditor(context),
                hyperOsPage(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )

            PermissionType.POST_NOTIFICATIONS -> listOf(
                appNotificationSettings(context),
                appDetails(context),
            )

            // 悬浮窗：标准 ACTION_MANAGE_OVERLAY_PERMISSION 页 → 应用详情页兜底
            PermissionType.OVERLAY -> listOf(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
                appDetails(context),
            )

            // 无障碍：标准无障碍设置页 → 应用详情页兜底。
            // 注意：Android 不允许应用自行开启无障碍服务，只能把用户送到设置页手动打开。
            PermissionType.ACCESSIBILITY -> listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                appDetails(context),
            )
        }
        candidates.forEach { intent -> if (tryStart(context, intent)) return true }
        return tryStart(context, appDetails(context))
    }

    private fun hyperOsPage(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    /** MIUI 权限编辑页（自启动 / 后台弹窗通用）。 */
    private fun hyperOsPermissionEditor(context: Context): Intent =
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )
            putExtra("extra_pkgname", context.packageName)
            putExtra("extra_uid", context.applicationInfo.uid)
        }

    private fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun tryStart(context: Context, intent: Intent): Boolean {
        val resolvable = runCatching { intent.resolveActivity(context.packageManager) != null }
            .getOrDefault(false)
        if (!resolvable) return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
