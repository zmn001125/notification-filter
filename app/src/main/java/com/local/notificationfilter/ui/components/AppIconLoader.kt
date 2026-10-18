package com.local.notificationfilter.ui.components

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.local.notificationfilter.util.NfLog

/**
 * 已渲染好的应用图标。
 *
 * @property bitmap 原图位图，尺寸 = 调用方传入的 `sizePx`（dp × density）。**不含任何自造底板、
 *   也未做圆形裁剪**——直接就是系统里取到的图标 Drawable 按正方形铺满绘制的结果；
 * @property adaptive 是否来自 [AdaptiveIconDrawable]。仅作为诊断/降级信息保留（[OverlayRender]
 *   等调用方可据此决定展示外形），loader 本身不再按它分流绘制；
 * @property circularReady 位图是否已做过圆形处理。V9 起**恒为 `false`**：loader 只输出原图，
 *   是否裁圆完全交给 UI（V8 的「圆底盘 + 0.86 缩放」方案已被用户否决并撤销）。
 */
data class LoadedAppIcon(
    val bitmap: ImageBitmap,
    val adaptive: Boolean,
    val circularReady: Boolean = false,
)

/**
 * 应用图标加载器（V7-②；V9 改为「只输出原图」）。
 *
 * 设计取舍（V9 用户明确要求「不要显示圆底，直接显示图标，不要底色，固定大小」）：
 * - loader **只负责取图标 + 渲染成正方形位图**，既不垫圆底盘/底色，也不裁圆、不缩放内缩；
 * - 同一 `size` 下所有图标的占位完全一致（都是满幅正方形位图），这就是「固定大小」；
 * - 代价：自适应图标显示为它自己的满幅方图（带系统自带形状/底色），老图标显示原始形状，
 *   **形状不再统一**。用户宁可形状不统一，也不要那块自造圆底盘——这是明确的产品取舍。
 *
 * 职责与约束：
 * - **必须在后台线程调用**（[load] 内部做 Drawable → Bitmap 绘制，主线程会掉帧）；
 * - 四级兜底取 Drawable：`getApplicationIcon` → `getLaunchIntentForPackage().activityInfo.loadIcon`
 *   → `getApplicationInfo().loadIcon` → **已安装应用列表按包名反查**，任一成功即用，
 *   全失败记 warn 并返回 null（UI 回落首字母）；
 * - 缓存 key = `包名@像素尺寸`，最多 [CACHE_MAX_ENTRIES] 条：
 *   64 × 96×96×4B ≈ 2.4MB，足够覆盖一屏列表 + 详情页跳转回读。
 */
object AppIconLoader {

    /** 缓存条目上限（列表一屏十几行 + 详情/测试页复用，64 条足够且内存可控）。 */
    private const val CACHE_MAX_ENTRIES = 64

    /** 兜底像素尺寸（dp）：首帧 [load] 收到 0 尺寸时用，避免建 0×0 位图抛异常。 */
    private const val FALLBACK_SIZE_DP = 48f

    private val cache = LruCache<String, LoadedAppIcon>(CACHE_MAX_ENTRIES)

    /**
     * 「包名 → 已安装应用 ApplicationInfo」索引（第四级兜底用）。
     *
     * `getInstalledApplications` 是全量查询（~50–200ms），若每次兜底都全表扫描，
     * 统计页一屏十几行就可能扫十几次；故进程内只建一次。索引**不随安装/卸载刷新**：
     * 兜底只针对「历史流水里的老包名」，新装应用的第一级 `getApplicationIcon` 就能命中。
     */
    @Volatile
    private var installedAppsIndex: Map<String, ApplicationInfo>? = null

    /**
     * 同步加载并渲染应用图标。
     *
     * @param context 任意 Context（内部只用 packageManager，不持有引用）；
     * @param packageName 目标应用包名，空白直接返回 null；
     * @param sizePx 目标位图边长（像素，正方形）。≤0 时按 [FALLBACK_SIZE_DP] × [density] 兜底；
     * @param density 当前屏幕 density，仅用于尺寸兜底换算。
     * @return 渲染结果；取不到或渲染失败返回 null，由 UI 回落首字母。
     */
    fun load(context: Context, packageName: String, sizePx: Int, density: Float): LoadedAppIcon? {
        if (packageName.isBlank()) return null

        val targetPx = if (sizePx > 0) {
            sizePx
        } else {
            (FALLBACK_SIZE_DP * density).toInt().coerceAtLeast(1)
        }
        val key = "$packageName@$targetPx"
        cache.get(key)?.let { return it }

        val drawable = resolveDrawable(context, packageName) ?: return null
        val adaptive = drawable is AdaptiveIconDrawable

        // 既无固有尺寸又不是纯色块：画出来会是一张全透明位图，不如回落首字母。
        if (!adaptive && drawable !is ColorDrawable && drawable.intrinsicWidth <= 0) {
            NfLog.w(this, "图标无固有尺寸且非 ColorDrawable，回落首字母 pkg=$packageName")
            return null
        }

        val bitmap = runCatching { renderToBitmap(drawable, targetPx) }
            .onFailure { NfLog.w(this, "渲染图标失败 pkg=$packageName size=$targetPx", it) }
            .getOrNull() ?: return null

        // V9：只输出原图，未做任何圆形处理 —— circularReady 恒为 false（字段保留给 OverlayRender 读）。
        val icon = LoadedAppIcon(bitmap.asImageBitmap(), adaptive, circularReady = false)
        cache.put(key, icon)
        return icon
    }

    /**
     * 四级兜底取 Drawable：普通取图标 → 启动 Activity 图标 → ApplicationInfo 图标
     * → 已安装应用列表反查。
     *
     * 已卸载应用 / 系统包异常 / 工作资料/厂商定制图标抛异常时逐级降档，
     * 每一级失败都记 warn（带包名与原因），最后一级也失败返回 null。
     */
    private fun resolveDrawable(context: Context, packageName: String): Drawable? {
        val pm = context.packageManager

        runCatching { pm.getApplicationIcon(packageName) }
            .onFailure { NfLog.w(this, "getApplicationIcon 失败 pkg=$packageName", it) }
            .getOrNull()
            ?.let { return it }

        // Intent 本身没有 activityInfo，得先拿 component 再查 ActivityInfo。
        // （原写法的 `intent?.activityInfo` 在 Kotlin 里解析不到，编译期即报错。）
        runCatching {
            pm.getLaunchIntentForPackage(packageName)
                ?.component
                ?.let { pm.getActivityInfo(it, 0) }
                ?.loadIcon(pm)
        }
            .onFailure { NfLog.w(this, "launchIntent.loadIcon 失败 pkg=$packageName", it) }
            .getOrNull()
            ?.let { return it }

        runCatching { pm.getApplicationInfo(packageName, 0).loadIcon(pm) }
            .onFailure { NfLog.w(this, "applicationInfo.loadIcon 失败 pkg=$packageName", it) }
            .getOrNull()
            ?.let { return it }

        // V8 需求 8（「有些图标没显示」）：统计/流水页的包名来自历史库，
        // `getApplicationIcon` 可能抛 NameNotFound（多用户/工作资料/包可见性收紧），
        // 但应用其实还装着 —— 在已安装应用列表里按包名反查一次即可救回。
        resolveByInstalledApps(pm, packageName)
            ?.let {
                NfLog.w(this, "三级兜底失败，已用已安装应用列表反查救回 pkg=$packageName")
                return it
            }

        NfLog.w(this, "应用图标四级兜底全部失败，回落首字母 pkg=$packageName")
        return null
    }

    /**
     * 第四级兜底：在已安装应用列表里按包名反查 [ApplicationInfo] 再 `loadIcon`。
     *
     * 索引进程内只建一次（见 [installedAppsIndex]）；建索引失败时返回 null（不缓存失败结果，
     * 下次兜底会重试）。
     */
    private fun resolveByInstalledApps(pm: PackageManager, packageName: String): Drawable? {
        val info = installedAppsByPackage(pm)[packageName] ?: return null
        return runCatching { info.loadIcon(pm) }
            .onFailure { NfLog.w(this, "installedApps.loadIcon 失败 pkg=$packageName", it) }
            .getOrNull()
    }

    /** 取（必要时构建）包名索引；构建失败返回空表且不缓存，等待下次重建。 */
    private fun installedAppsByPackage(pm: PackageManager): Map<String, ApplicationInfo> {
        installedAppsIndex?.let { return it }
        val built = runCatching { pm.getInstalledApplications(0).associateBy { it.packageName } }
            .onFailure { NfLog.w(this, "getInstalledApplications 失败，第四级兜底不可用", it) }
            .getOrNull()
        if (built.isNullOrEmpty()) return emptyMap()
        installedAppsIndex = built
        return built
    }

    /**
     * 把 Drawable 绘制成 `targetPx × targetPx` 的正方形位图（**原图直出**）。
     *
     * - 不做圆形/圆角裁剪（V9：是否裁圆由 UI 决定，loader 不预设形状）；
     * - 不垫任何底板/底色（用户原话：「不要底色」——自造圆底在深色页面上很像一块脏色块）；
     * - 不做内缩缩放：`setBounds(0, 0, targetPx, targetPx)` 满幅绘制，
     *   保证同一 `size` 下所有图标占位一致（「固定大小」）；
     * - [ColorDrawable] 没有固有内容，直接填满整块（否则 draw() 什么也不画）。
     */
    private fun renderToBitmap(drawable: Drawable, targetPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (drawable is ColorDrawable) {
            canvas.drawColor(drawable.color)
            return bitmap
        }

        drawable.setBounds(0, 0, targetPx, targetPx)
        drawable.draw(canvas)
        return bitmap
    }
}
