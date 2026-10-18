package com.local.notificationfilter.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.util.NfLog

/** 已安装 App 信息（App 选择器 / 黑名单 / 规则范围共用）。 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean = false,
    /** V8：是否有桌面入口。false = 无桌面图标但仍会发通知的包（系统组件/服务型应用）。 */
    val hasLauncher: Boolean = true,
) {
    var icon: Drawable? = null
}

/**
 * 已安装 App 工具：应用名解析（带缓存）、已安装 App 列表、跳转。
 */
object AppUtils {

    private val nameCache = HashMap<String, String>()

    /** 已安装应用列表缓存（V8：三个入口反复打开，枚举全量包是几百毫秒级开销）。 */
    @Volatile
    private var installedAppsCache: List<AppInfo>? = null

    @Volatile
    private var installedAppsCachedAt: Long = 0L

    /** 重名应用名集合缓存（与 [installedAppsCache] 同 TTL）。 */
    @Volatile
    private var duplicatedLabelsCache: Set<String>? = null

    private const val INSTALLED_APPS_TTL_MS = 5 * 60 * 1000L

    /** 包名 → 应用名（失败回落包名最后一段）。 */
    fun appNameOf(context: Context, packageName: String): String {
        nameCache[packageName]?.let { return it }
        val name = runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull() ?: packageName.substringAfterLast('.')
        nameCache[packageName] = name
        return name
    }

    /** 本 App 包名。 */
    fun selfPackage(context: Context): String = context.packageName

    /**
     * **全部已安装应用**（V8 需求 1/6）：不再按「有无桌面入口」过滤。
     *
     * 旧实现用 `getLaunchIntentForPackage(pkg) != null` 过滤，只保留有桌面图标的应用，
     * 导致「指定 App 的通知」「打开了某个 App」「作用范围」三处都只能看到一部分 App
     * （系统组件、服务型应用、被隐藏/冻结的应用全被排除，但它们照样会发通知）。
     *
     * 排序：有桌面图标的在前 → 用户应用在系统应用之前 → 名称升序。
     * 结果做进程内缓存（5 分钟），因为三个入口会反复打开这个列表，而枚举全部安装
     * 应用 + 逐个查 launchIntent 在几百个包时是几百毫秒级开销。
     */
    fun installedApps(context: Context): List<AppInfo> {
        val cached = installedAppsCache
        val now = System.currentTimeMillis()
        if (cached != null && now - installedAppsCachedAt < INSTALLED_APPS_TTL_MS) return cached

        val pm = context.packageManager
        val self = context.packageName
        val list = runCatching {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            installed.asSequence()
                .filter { it.packageName.isNotBlank() && it.packageName != self }
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrNull().orEmpty()
                    AppInfo(
                        packageName = info.packageName,
                        appName = label.ifBlank { info.packageName },
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        hasLauncher = runCatching {
                            pm.getLaunchIntentForPackage(info.packageName) != null
                        }.getOrDefault(false),
                    )
                }
                .sortedWith(
                    compareByDescending<AppInfo> { it.hasLauncher }
                        .thenBy { it.isSystem }
                        .thenBy { it.appName },
                )
                .toList()
        }.onFailure {
            NfLog.w(this, "枚举已安装应用失败：${it.message}", it)
        }.getOrDefault(emptyList())

        // V9.1：**空结果不缓存**。
        // 旧实现无条件写入缓存，一次瞬时失败（多任务切换、系统繁忙）就会把「0 个应用」
        // 钉住 5 分钟 —— 用户看到的就是「应用列表有时有、有时没有」。
        // 空列表还可能是「包可见性不足」的表现，更不该被缓存放大。
        if (list.isNotEmpty()) {
            installedAppsCache = list
            installedAppsCachedAt = now
        } else {
            NfLog.w(this, "已安装应用枚举结果为空，本次不缓存（下次重试）")
        }
        return list
    }

    /**
     * 兼容保留：仅「有桌面入口」的应用。
     *
     * V8 起 `installedApps` 才是推荐入口；本函数保留给语义上确实只要"可启动应用"的场景。
     */
    fun launchableApps(context: Context): List<AppInfo> =
        installedApps(context).filter { it.hasLauncher }

    /** 应用名；查不到返回包名本身（不回落"最后一段"，避免误导）。 */
    fun appLabel(context: Context, packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: appNameOf(context, packageName)
            ?: packageName
    }

    /**
     * 应用名；若该名称被**多个已安装应用共用**（重名），返回「名称（包名）」，否则只返回名称。
     *
     * 用于「作用范围」等需要让用户明确分辨是哪个 App 的位置（V8 需求 9）。
     * 未安装 / 查不到时直接返回包名。
     */
    fun appLabelWithPkgIfDuplicated(context: Context, packageName: String): String {
        val label = appLabel(context, packageName)
        val duplicated = duplicatedLabels(context).contains(label)
        return if (duplicated && label != packageName) "$label（$packageName）" else label
    }

    /** 被多个包共用的应用名集合（缓存，随 [installedApps] 的 TTL 一起失效）。 */
    private fun duplicatedLabels(context: Context): Set<String> {
        val cached = duplicatedLabelsCache
        val now = System.currentTimeMillis()
        if (cached != null && now - installedAppsCachedAt < INSTALLED_APPS_TTL_MS) return cached
        val set = installedApps(context)
            .groupingBy { it.appName }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicatedLabelsCache = set
        return set
    }

    /** 可启动的用户 + 系统 App（排除本 App），按名称排序。 */
    /** 打开源 App 首页（替换通知点击降级 / 黑名单校验用）。 */
    fun launchApp(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
