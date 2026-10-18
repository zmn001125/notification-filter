package com.local.notificationfilter.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.local.notificationfilter.core.model.DanmakuMode
import com.local.notificationfilter.core.model.DanmakuStyle
import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.OverlayAlign
import com.local.notificationfilter.core.model.OverlayAnim
import com.local.notificationfilter.core.model.OverlayPayload
import com.local.notificationfilter.ui.overlay.DanmakuBubble
import com.local.notificationfilter.ui.overlay.FloatBubble
import com.local.notificationfilter.ui.overlay.OverlayExitSignal
import com.local.notificationfilter.ui.overlay.PositionedDanmakuBubble
import com.local.notificationfilter.ui.overlay.stackSignOf
import com.local.notificationfilter.ui.theme.NotificationFilterTheme
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 屏幕叠加层控制器（V5 需求③弹幕双形态 / 需求④悬浮）。
 *
 * 职责：把 [OverlayPayload] 按样式画到 **WindowManager 上的 ComposeView**
 * （`TYPE_APPLICATION_OVERLAY`，需要 `SYSTEM_ALERT_WINDOW` 权限），并管理生命周期与回收。
 *
 * 关键约定（架构 Q5 / Q7 + V5 需求③/④）：
 * - **弹幕双形态**（[DanmakuStyle.modeEnum]）：
 *   - 贯穿式 THROUGH（默认）：顶部水平轨道，右缘滚入 → 不停留 → 滚出左缘；速度
 *     px/秒，时长 = (屏宽+气泡宽)/速度；同屏最多 [DanmakuStyle.maxConcurrent] 条，
 *     轨道分配走 [occupiedTracks] 最小未占用（D7）；
 *   - 定位式 POSITIONED：九宫格 [DanmakuStyle.stackAlign] + X/Y 偏移落位，淡入出现、
 *     停留后淡出；多条按 [DanmakuStyle.stackDirection] 堆叠（第 i 条相对基准偏移
 *     i×(条目高+间距)，方向符号见 stackSignOf）；同样受同屏上限约束、满则排队；
 * - **悬浮单实例**：新的命中直接替换当前悬浮（[FloatStyle.displaySeconds] 秒后自动消失，
 *   0 = 常驻；常驻实例另有 [MAX_FLOAT_RESIDENT_MS] 上限兜底 + 首页菜单「清除屏幕显示」
 *   可随时撤下，见 [dismissAll]）；配置对话框的真机预览走独立实例
 *   [floatPreviewWindow]，与业务悬浮互不干扰；
 * - **无权限静默跳过**：[canDrawOverlays] 为 false 时一律不弹窗，由调用方记录
 *   [com.local.notificationfilter.core.model.ActionType.OVERLAY_SKIPPED]；
 * - **进程被后台回收时窗口静默消失**（Q7 已接受），不尝试自动重建以免与系统策略冲突；
 * - 所有 WindowManager 调用包在 try-catch 里并走主线程，**绝不因叠加层崩溃影响监听服务**。
 */
object OverlayController {

    @Volatile
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 悬浮窗权限是否授予（API 23+ 走 Settings.canDrawOverlays）。 */
    fun canDrawOverlays(): Boolean {
        val context = appContext ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /** 当前活跃的弹幕条数（供 UI 展示 / 自检）。 */
    private val _activeCount = MutableStateFlow(0)

    val activeCount: StateFlow<Int> get() = _activeCount

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    // ==================== 退场编排（V8：「先播退场、再摘窗口」） ====================

    /**
     * 正在播放退场动画、等待气泡回调的窗口。
     *
     * 为什么要单独记：撤下悬浮 / 条件卡片时，槽位（[floatWindow] / [conditionalFloatWindow]）
     * 会**立刻让出**（否则新内容会写进一张正在消失的卡片），于是这些窗口已经不在任何
     * 槽位里了。若不在全清时逐个硬摘，它们只能等 [EXIT_TIMEOUT_MS] 超时兜底才真正消失，
     * 用户点「清除屏幕显示」后会看到残留的淡出动画。
     */
    private val exitingWindows = mutableSetOf<OverlayWindow>()

    /**
     * 请求某个窗口**播放退场动画**，播完（或超时）后执行 [onDone]。
     *
     * 时序：
     * ```
     * dismissXxx() ──post──> requestExit(window, onDone)
     *                          │ ① markExitRequested()  ← 幂等闸门：已请求过就直接返回
     *                          │ ② window.exitSignal.request()   （Compose 侧 snapshotFlow 被唤醒）
     *                          │ ③ postDelayed(EXIT_TIMEOUT_MS)  （超时兜底）
     *                          ▼
     *               气泡播完退场 → onFinished() ──post──> onDone()
     *                                                     │ 撤销超时任务
     *                                                     │ 退出 exitingWindows
     *                                                     └ detachNow(window) → removeView
     * 超时先到 → onDone() 同样执行一遍（onDone 内部幂等）
     * ```
     *
     * **幂等**：同一窗口重复 requestExit 只有第一次生效（[OverlayWindow.markExitRequested]）。
     * **绝不永久滞留**：即使渲染层因为任何原因没回调 `onFinished`（窗口被系统提前移除、
     * Compose 组合被取消、动画被异常打断），超时任务也会强制走完 [onDone]，最多滞留
     * [EXIT_TIMEOUT_MS]。
     *
     * @param window 目标窗口
     * @param onDone 退场收尾（**必须调用 [detachNow]**，且必须自身幂等）
     */
    private fun requestExit(window: OverlayWindow, onDone: () -> Unit) {
        if (!window.markExitRequested()) return
        exitingWindows += window
        // V9：按该窗口自己的退场时长排超时（动画时长可配），不再用固定 300ms
        val waitMs = window.exitWaitMs
        val timeout = Runnable {
            NfLog.d(this, "退场动画回调超时（${waitMs}ms），强制摘除窗口")
            window.exitTimeoutRunnable = null
            onDone()
        }
        window.exitTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, waitMs)
    }

    /**
     * 立刻摘掉一个窗口（**所有 detach 的唯一出口**）。
     *
     * 做三件事，缺一件就会留下「幽灵任务」：
     * 1. 撤销该窗口尚未触发的退场超时任务（否则 300ms 后会有一个延迟消息去动一个
     *    已经不在屏幕上的窗口，甚至误伤**复用了同一槽位的新窗口**）；
     * 2. 从 [exitingWindows] 登记表里移除；
     * 3. 真正 `removeView` + 销毁 Lifecycle（幂等：窗口未挂载时直接返回）。
     *
     * **只在主线程调用**。
     */
    private fun detachNow(window: OverlayWindow) {
        window.exitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        window.exitTimeoutRunnable = null
        exitingWindows.remove(window)
        window.detach()
    }

    // ==================== 弹幕 ====================

    /** 弹幕轨道（同屏活跃窗口）。 */
    private val danmakuWindows = mutableListOf<OverlayWindow>()

    /**
     * 已被占用的**轨道下标**集合（D7 修复）。
     *
     * 为什么不能用 `danmakuWindows.size` 当轨道号：同屏 3 条时若**中间那条**先滚完结束，
     * size 从 3 变 2，下一条新弹幕会拿到 2 —— 与原本就在 2 号轨的那条**叠在同一条轨**上。
     * 改为「显式登记 / 归还下标」，分配时取最小的未占用下标，与存活数量彻底解耦。
     */
    private val occupiedTracks = mutableSetOf<Int>()

    /** 排队等待的弹幕（FIFO）。 */
    private val danmakuQueue = ArrayDeque<Pair<OverlayPayload, DanmakuStyle>>()

    /**
     * 分配一个空轨道下标（最小的未占用者），范围 `[0, maxConcurrent)`。
     *
     * @return 分配到即登记并返回；全部被占用返回 -1（调用方应排队，不得强行复用轨道）
     */
    private fun allocateTrack(maxConcurrent: Int): Int {
        val limit = maxConcurrent.coerceAtLeast(1)
        for (index in 0 until limit) {
            if (index !in occupiedTracks) {
                occupiedTracks += index
                return index
            }
        }
        return -1
    }

    /** 归还轨道下标（窗口结束 / 挂载失败 / 全清时调用）。 */
    private fun freeTrack(index: Int) {
        if (index >= 0) occupiedTracks.remove(index)
    }

    /**
     * 投递一条弹幕。同屏未达上限则立即显示，否则进入队列等待。
     * @return 是否已（或将会）显示；false 表示无权限 / 未初始化，调用方应记为跳过。
     */
    fun enqueueDanmaku(payload: OverlayPayload, style: DanmakuStyle): Boolean {
        val context = appContext
        if (context == null || !canDrawOverlays()) return false
        // V9.6：**不再按「同屏最多条数」限流**。
        // 用户要求「弹幕可以显示不限定的条数，只要不把前面的弹幕遮住」——
        // 是否放得下改由 showThroughDanmaku 里的「找一条不重叠的 y」决定：
        // 屏幕还有空位就继续显示，真的放不下才排队。
        mainHandler.post {
            // V10.7 耗电修复：**恢复同屏上限**。
            // 每条弹幕是一个全宽独立 Window + 独立 ComposeView/Recomposer/Surface，
            // V9.6 取消上限后可同时挂 10+ 个全宽图层并各自跑动画 —— 实测这是耗电主因之一。
            // 这里按样式里的「同屏最多条数」硬限流（用户可在样式里调大，但会有功耗代价）。
            val limit = style.maxConcurrent.coerceAtLeast(1)
            if (danmakuWindows.size >= limit) {
                if (danmakuQueue.size >= MAX_QUEUE) danmakuQueue.removeFirst()
                danmakuQueue.addLast(payload to style)
                NfLog.d(this, "弹幕已达同屏上限 $limit，排队等待（队列 ${danmakuQueue.size}）")
            } else {
                showDanmakuWindow(context, payload, style)
            }
        }
        return true
    }

    private fun showDanmakuWindow(context: Context, payload: OverlayPayload, style: DanmakuStyle) {
        // V5 需求③：双形态派发（贯穿式 = 顶部水平轨道；定位式 = 九宫格堆叠）
        when (style.modeEnum) {
            DanmakuMode.THROUGH -> showThroughDanmaku(context, payload, style)
            DanmakuMode.POSITIONED -> showPositionedDanmaku(context, payload, style)
        }
    }

    /**
     * 贯穿式弹幕窗口：顶部第 [trackIndex] 条轨道（y = topMargin + i×(轨道高+间距)），
     * 滚动与回收由 [DanmakuBubble] 内的动画驱动。
     */
    private fun showThroughDanmaku(context: Context, payload: OverlayPayload, style: DanmakuStyle) {
        // V9.6 重写（两个真问题的根因都在这）：
        // ① 条数被 `maxConcurrent` 硬顶住 —— 用户要「不限条数，只要不遮住前面的」；
        // ② y 按「轨道号 × 固定 48dp」算，实测高度一回报就整列重排 ——
        //    屏幕上的弹幕会**突然变个位置**（真机反馈原话）。
        // 现在：找一条与所有存活弹幕都不重叠的 y，**分配后永不再动**；
        // 放不下才排队。
        val estimatedHDp = TRACK_HEIGHT_DP
        val yDp = findFreeYDp(context, style, estimatedHDp)
        if (yDp == null) {
            if (danmakuQueue.size >= MAX_QUEUE) danmakuQueue.removeFirst()
            danmakuQueue.addLast(payload to style)
            NfLog.d(this, "屏幕已无空位，弹幕排队等待（队列 ${danmakuQueue.size}）")
            return
        }
        // trackIndex 仍登记（用于窗口归属与归还），但**不再参与 y 的计算**
        val trackIndex = allocateTrack(MAX_TRACKS)
        val window = OverlayWindow(context).apply {
            this.trackIndex = trackIndex
            gravity = Gravity.TOP or Gravity.START
            xDp = 0f
            this.yDp = yDp
            // 样式留存：定位式堆叠重排需要（贯穿式已不重排）
            styleForStack = style
        }
        window.setContent {
            NotificationFilterTheme {
                DanmakuBubble(
                    payload = payload,
                    style = style,
                    onFinished = { mainHandler.post { releaseDanmaku(window) } },
                    // V9.6：实测高度**只记录**，不再触发贯穿式重排。
                    // 旧实现把实测回报接到 repositionAllStacks()，于是每条弹幕一测完，
                    // 整列弹幕的 y 就被重算一遍——这就是「突然变个位置」。
                    onMeasuredHeightDp = { h ->
                        mainHandler.post { window.measuredHeightDp = h }
                    },
                )
            }
        }
        if (!window.attach()) {
            NfLog.w(this, "弹幕窗口挂载失败（权限或系统限制）")
            // 挂载失败必须归还轨道，否则这个下标永远泄漏、轨道越用越少
            freeTrack(trackIndex)
            drainQueue()
            return
        }
        danmakuWindows += window
        _activeCount.value = danmakuWindows.size
    }

    /**
     * 找一条「不与任何存活贯穿式弹幕重叠」的 y（dp）；放不下返回 null（调用方排队）。
     *
     * 高度优先用**实测值**（`measuredHeightDp`），还没测出来时用估算值。
     * 从 `topMarginDp` 起逐段跳过已被占用的区间（`y .. y+高度+间距`），
     * 因此上下顺序永远是「先来的在上」，且新弹幕**绝不会压住前面的**。
     *
     * 返回的 y 一旦分配给某条弹幕就**永不改动**——这是修掉「位置突然跳变」的关键：
     * 别人滚完、或者实测高度回报，都不会再触发贯穿式重排。
     */
    private fun findFreeYDp(context: Context, style: DanmakuStyle, estimatedHDp: Float): Float? {
        val gap = style.trackGapDp
        val occupied = danmakuWindows
            .filter { it.stackSignature == null && it.trackIndex >= 0 }
            .map { w -> w.yDp to (w.measuredHeightDp ?: estimatedHDp) }
            .sortedBy { it.first }
        var candidate = style.topMarginDp
        for ((top, height) in occupied) {
            // 空隙放得下就停（不为了"排在最后"而白白浪费上方空位）
            if (candidate + estimatedHDp + gap <= top) break
            candidate = maxOf(candidate, top + height + gap)
        }
        val screenHDp = screenHeightDp(context)
        return if (candidate + estimatedHDp <= screenHDp) candidate else null
    }

    /** 屏幕可用高度（dp）。 */
    private fun screenHeightDp(context: Context): Float =
        context.resources.displayMetrics.let { it.heightPixels / it.density }

    /**
     * 定位式弹幕窗口（V5 需求③ 形态B）：九宫格 [DanmakuStyle.stackAlign] 落位，
     * X/Y 偏移为「距停靠边向屏幕中线」语义（与悬浮的 yDp 口径一致：gravity 决定
     * 从哪条边量，正数 = 向内）。堆叠：第 i 条（[trackIndex]）在基准上按
     * [DanmakuStyle.stackDirection] 偏移 i×(条目高+间距)。
     *
     * 淡入错峰（多条逐条依次出现）：第 i 条延迟 i × [STACK_STAGGER_MS] 再淡入。
     */
    private fun showPositionedDanmaku(context: Context, payload: OverlayPayload, style: DanmakuStyle) {
        val trackIndex = allocateTrack(style.maxConcurrent)
        if (trackIndex < 0) {
            if (danmakuQueue.size >= MAX_QUEUE) danmakuQueue.removeFirst()
            danmakuQueue.addLast(payload to style)
            NfLog.d(this, "定位式堆叠已满，弹幕改排队，当前队列 ${danmakuQueue.size}")
            return
        }
        val align = style.stackAlignEnum
        val (xDp, yDp) = stackOffsetOf(style, trackIndex, measuredHeightOf = { null })
        val window = OverlayWindow(context).apply {
            this.trackIndex = trackIndex
            gravity = gravityOf(align)
            this.xDp = xDp
            this.yDp = yDp
            // 堆叠签名 + 样式留存：补位重排（repositionAllStacks）按签名分组、按样式重算
            stackSignature = StackKey(align, style.stackDirectionEnum)
            styleForStack = style
        }
        window.setContent {
            NotificationFilterTheme {
                PositionedDanmakuBubble(
                    payload = payload,
                    style = style,
                    onFinished = { mainHandler.post { releaseDanmaku(window) } },
                    staggerMs = trackIndex * STACK_STAGGER_MS,
                    // V6 需求③：实测高度回报 → 存窗口 → 触发定位式堆叠重排
                    onMeasuredHeightDp = { h ->
                        mainHandler.post {
                            window.measuredHeightDp = h
                            repositionAllStacks()
                        }
                    },
                )
            }
        }
        if (!window.attach()) {
            NfLog.w(this, "定位式弹幕窗口挂载失败（权限或系统限制）")
            freeTrack(trackIndex)
            drainQueue()
            return
        }
        danmakuWindows += window
        _activeCount.value = danmakuWindows.size
    }

    /**
     * 弹幕第 [index] 条的窗口偏移（dp），两形态共用。
     *
     * [measuredHeightOf]：取「前面第 j 条」的实测高度（V6 需求③；null = 尚未回报）。
     * 贯穿式由调用方从 [danmakuWindows] 按轨道查；定位式初挂时前面条尚未就绪，恒 null
     * （用估算值先落位，实测回报后由 [repositionAllStacks] 收敛到真实位置）。
     *
     * 坐标口径：WindowManager 的 x/y 相对 gravity 停靠角量取，正数一律**向内**
     * （TOP 锚 +y 向下、BOTTOM 锚 +y 向上、START/END 同理），所以
     * [DanmakuStyle.offsetXDp]/[DanmakuStyle.offsetYDp] 的「向屏幕中线为正」直接映射；
     * 堆叠偏移在屏幕坐标系算好（TOP_DOWN = 向下为正，见 stackSignOf）再乘锚点符号
     * 换算回「向内」口径（BOTTOM 锚翻转为负）。
     *
     * 累计偏移 = Σ_{j<index}(实测高度_j ?: 估算 TRACK_HEIGHT_DP + 该条样式的间距)。
     */
    private fun stackOffsetOf(
        style: DanmakuStyle,
        index: Int,
        measuredHeightOf: (Int) -> Float?,
    ): Pair<Float, Float> {
        return when (style.modeEnum) {
            // 贯穿式：顶部轨道，y = topMargin + Σ前面轨道实测高+间距
            DanmakuMode.THROUGH -> {
                var acc = style.topMarginDp
                for (j in 0 until index) {
                    acc += (measuredHeightOf(j) ?: TRACK_HEIGHT_DP) + style.trackGapDp
                }
                0f to acc
            }
            // 定位式：九宫格基准 + 按排队方向的累计堆叠偏移
            // V9.11 气泡：暂时沿用同一套堆叠计算（贴边逻辑随渲染层一起接入）
            DanmakuMode.POSITIONED -> {
                val anchorSignV = when (style.stackAlignEnum) {
                    OverlayAlign.BOTTOM_START,
                    OverlayAlign.BOTTOM_CENTER,
                    OverlayAlign.BOTTOM_END,
                    -> -1

                    else -> 1
                }
                var acc = 0f
                for (j in 0 until index) {
                    acc += (measuredHeightOf(j) ?: TRACK_HEIGHT_DP) + style.stackGapDp
                }
                val stackStep = acc * stackSignOf(style.stackDirectionEnum)
                style.offsetXDp to (style.offsetYDp + stackStep * anchorSignV)
            }
        }
    }

    /**
     * 补位/实测回报后的统一重排（V6 需求③：两形态共用）。
     *
     * - 定位式（有 stackSignature 的窗口）：按 StackKey 分组，组内按 danmakuWindows
     *   顺序依次顶上腾出的格子；
     * - 贯穿式（无签名）：y = topMargin + Σ_{j<自己的轨道号}(第 j 轨实测高 + 间距)；
     * - 全部用 updateViewLayout 原位移动，**不重建窗口、不重播动画**、不打断停留计时。
     *
     * P1-2 修复：贯穿式的「前面第 j 条」必须按**轨道号**查，不能拿 `danmakuWindows`
     * 排序后的**列表下标**当轨道号——①[allocateTrack] 取的是最小空闲下标，存活轨道可能
     * 是 `{1,2}`（0 号已归还），列表下标 0 对应的是 1 号轨；②同一列表里还混着定位式
     * 弹幕（它们也占 trackIndex），按顺序取会拿到完全无关的定位式高度。两者都会让
     * 轨道间距忽大忽小甚至重叠。改为显式「轨道号 → 窗口」映射（局部变量
     * `throughByTrack`，`j` 是轨道号，取不到该轨就回落估算高度）。
     */
    private fun repositionAllStacks() {
        // 定位式：按组重排
        danmakuWindows
            .mapNotNull { w -> w.stackSignature?.let { it to w } }
            .groupBy({ it.first }, { it.second })
            .forEach { (_, windows) ->
                windows.forEachIndexed { index, w ->
                    val style = w.styleForStack ?: return@forEachIndexed
                    val (xDp, yDp) = stackOffsetOf(style, index) { j ->
                        windows.getOrNull(j)?.measuredHeightDp
                    }
                    w.updatePosition(xDp, yDp)
                }
            }
        // V9.6：**贯穿式不再重排**。
        // y 在分配时就用「不与存活弹幕重叠」算好并定死，之后无论是别人滚完、
        // 还是本条实测高度回报，都不再改动它 —— 否则用户看到的就是「弹幕突然变个位置」。
        // （旧实现按「轨道号 × 固定高度」重算全列，实测一回报整列就跳一次。）
    }

    /** 定位式弹幕堆叠分组键（同锚点同方向视为同一组队列）。 */
    private data class StackKey(val align: OverlayAlign, val direction: com.local.notificationfilter.core.model.DanmakuStackDirection)

    private fun releaseDanmaku(window: OverlayWindow) {
        // 只有「仍在册」的窗口才摘除并归还轨道。重复回调（例如 dismissAll 已统一 detach
        // 之后 Compose 侧又补一次 onFinished）若照旧归还下标，会把**已被别人复用的**轨道
        // 误释放，等于又制造一次 D7 类重叠问题，故在此做幂等保护。
        if (!danmakuWindows.remove(window)) return
        // V8：统一走 detachNow（顺带撤销退场超时任务、退出 exitingWindows 登记）。
        // 弹幕走到这里时**退场动画已经播完**——贯穿式是滚出左缘、定位式是淡出/animOut 结束，
        // 都是渲染层自己驱动的，因此这里不需要再「等一次退场」。
        detachNow(window)
        // D7：把轨道下标还回池子，后续弹幕才能复用这一轨
        freeTrack(window.trackIndex)
        _activeCount.value = danmakuWindows.size
        // 定位式：腾出的堆叠位由同组后续弹幕依次顶上（原位移动，不重建窗口）
        repositionAllStacks()
        drainQueue()
    }

    /** 从队列取一条补位（窗口挂载失败时也调用，避免队列卡死）。 */
    private fun drainQueue() {
        val context = appContext ?: return
        val next = danmakuQueue.removeFirstOrNull() ?: return
        showDanmakuWindow(context, next.first, next.second)
    }

    // ==================== 悬浮 ====================

    @Volatile
    private var floatWindow: OverlayWindow? = null

    // —— V9.3：业务悬浮的「原地换内容」状态 ——
    //
    // 用户反馈「无动画要能跟上一次悬浮弹窗无缝显示」：原来每条命中都 detach 旧窗口 +
    // 新建一个，于是屏幕上必然出现「旧的一下没了、新的一条重新入场」——选了「无动画」
    // 也还是有一下跳变。改成同一样式直接在**同一个窗口里换内容**：ComposeView 不重建、
    // 入场动画不重播、位置不重算，观感就是上一条直接变成新的一条。
    //
    // 与条件卡片（[conditionalPayloadState]）是同一套手法，但**两个槽位完全独立**。

    /** 当前业务悬浮已应用的样式快照；null = 无悬浮 / 刚被清槽。 */
    private var floatStyle: FloatStyle? = null

    /** 内容状态：写入即重组，不重建窗口。 */
    private var floatPayloadState: MutableState<OverlayPayload>? = null

    /** 内容版本：每次重新显示 +1，驱动「停留 N 秒」重新计时（见 FloatBubble.timerKey）。 */
    private var floatVersionState: MutableState<Int>? = null

    /** 当前内容签名；未变则不写状态（避免进度类通知每秒刷新时无意义重组）。 */
    private var floatSignature: Long = 0L

    /**
     * 常驻悬浮的「最大存活时长」兜底计时器（P0-1）。
     *
     * 为什么需要：`displaySeconds <= 0` 的常驻悬浮窗口带 `FLAG_NOT_TOUCHABLE`
     * （点击穿透，点不中）且没有通知栏入口，[dismissFloat] 在 V6.0 里**没有任何调用者**——
     * 一旦挂上就永久留在屏幕上，用户没有办法关掉。故对常驻实例设 30 分钟上限。
     *
     * 撤销点（三处，缺一处就会出现「旧计时关掉新窗口」）：
     * 1. [showFloat] 开头 —— 新悬浮覆盖旧的；
     * 2. [dismissFloat] —— 主动撤下；
     * 3. [dismissAll] —— 全清（与 1 配合，保证全清后残留计时不会命中后来者）。
     */
    private val residentDismissTask = Runnable {
        NfLog.d(OverlayController, "常驻悬浮达到最大存活时长，自动撤下")
        dismissFloat()
    }

    /**
     * 显示（或替换）悬浮卡片。
     * @return 是否已显示；false 表示无权限 / 未初始化。
     */
    fun showFloat(payload: OverlayPayload, style: FloatStyle): Boolean {
        val context = appContext
        if (context == null || !canDrawOverlays()) return false
        mainHandler.post {
            // P0-1：替换旧悬浮时必须撤销旧的「常驻最大存活」计时，否则旧计时会在
            // **新窗口**上提前触发（同一个 MessageQueue 上的延迟消息不会因为窗口被
            // detach 而失效），表现为「新悬浮刚出来几秒就自己没了」。
            mainHandler.removeCallbacks(residentDismissTask)
            // —— V9.3：同一样式 → 原地换内容（无缝衔接上一次悬浮）——
            //
            // 注意 `isExitRequested`：上一次正在播退场动画时不能复用（它马上就没了），
            // 必须重建窗口（旧窗口硬摘，新内容优先，与 V8 的替换语义一致）。
            val current = floatWindow
            if (current != null && floatStyle == style && !current.isExitRequested) {
                applyFloatContent(payload)
                if (style.displaySeconds <= 0f) {
                    // 常驻实例的 30 分钟兜底重新排（旧计时已在上面撤销）
                    mainHandler.postDelayed(residentDismissTask, MAX_FLOAT_RESIDENT_MS)
                }
                return@post
            }
            // V8：替换旧悬浮用 detachNow —— 顺手撤销旧窗口可能还挂着的退场超时任务
            // （否则旧窗口的超时延迟消息会命中**新窗口**所在的槽位）。
            // 新内容要立刻上场，故替换路径**不等**旧窗口的退场动画（旧窗口直接硬摘）。
            current?.let { detachNow(it) }
            floatWindow = null
            clearFloatSlot()

            val payloadState = mutableStateOf(payload)
            val versionState = mutableStateOf(0)
            // 出现动画 =「无」→ 冻结 animKey：内容热更新时**不重播**任何入场（用户要的
            // 「无缝」）；其余动画则用版本号当 key，让新内容也有一次入场，而不是硬跳变。
            val replayEntranceOnUpdate = style.animEnum != OverlayAnim.NONE
            val window = OverlayWindow(context).apply {
                gravity = gravityOf(style.screenAlignEnum)
                // V9：退场超时跟着用户配的消失时长走（+60ms 容差，下限 120ms）
                exitWaitMs = (style.animOutMs + 60).coerceAtLeast(120).toLong()
                // D3：垂直偏移只在这里生效（FloatBubble 不再重复 padding(top)）。
                // 停靠 TOP 时为「距顶部」，停靠 BOTTOM 时为「距底部」——都是相对停靠边。
                yDp = style.offsetTopDp
                // 水平偏移交给 FloatBubble 的 start/end 内边距（窗口是 MATCH_PARENT 宽，
                // 在窗口层再设 x 会与内边距叠加成双倍偏移）。
                xDp = 0f
            }
            window.setContent {
                NotificationFilterTheme {
                    FloatBubble(
                        payload = payloadState.value,
                        style = style,
                        // V8：把窗口的退场信号交给渲染层 —— 于是 dismissFloat() 不再是
                        // 「啪一下消失」，而是先按 animOut 播退场、播完回调这里。
                        exitSignal = window.exitSignal,
                        onFinished = {
                            mainHandler.post {
                                // P1-4：只摘自己。本回调与 showFloat（管线线程）
                                // 都会 post 到同一个 MessageQueue，跨线程投递顺序不保证：
                                // 此刻 floatWindow 可能**已经是新窗口**，无条件 detach
                                // 会把新悬浮闪一下就摘掉（预览路径早已按此写法修正）。
                                if (floatWindow == window) {
                                    floatWindow = null
                                    clearFloatSlot()
                                }
                                detachNow(window)
                            }
                        },
                        // V9.3：animKey 管动画、timerKey 管计时（见 FloatBubble 参数说明）
                        animKey = if (replayEntranceOnUpdate) versionState.value else 0,
                        timerKey = versionState.value,
                    )
                }
            }
            if (window.attach()) {
                floatWindow = window
                floatStyle = style
                floatPayloadState = payloadState
                floatVersionState = versionState
                floatSignature = signatureOf(payload)
                // P0-1：displaySeconds <= 0 = 常驻。常驻窗口带 FLAG_NOT_TOUCHABLE
                // （点不中）且没有通知栏入口，若不设上限就会**永久挂在屏幕上**。
                // 这里给 30 分钟的兜底存活时长，用户另有「首页菜单 → 清除屏幕显示」可随时撤。
                // 预览实例（showFloatPreview）**不走这条路径**，由配置页的 DisposableEffect 管。
                if (style.displaySeconds <= 0f) {
                    mainHandler.postDelayed(residentDismissTask, MAX_FLOAT_RESIDENT_MS)
                }
            } else {
                NfLog.w(this, "悬浮窗口挂载失败（权限或系统限制）")
            }
        }
        return true
    }

    /**
     * 原地更新业务悬浮的内容（V9.3，**只在主线程**调用）。
     *
     * 内容没变也照样 +版本号：语义是「这条又命中了一次」，停留 N 秒要重新开始计。
     * 内容变了才写状态（避免进度类通知每秒刷新时的无意义重组）。
     */
    private fun applyFloatContent(payload: OverlayPayload) {
        val state = floatPayloadState ?: return
        val version = floatVersionState ?: return
        val signature = signatureOf(payload)
        if (signature != floatSignature) {
            floatSignature = signature
            state.value = payload
        }
        version.value += 1
    }

    /** 只清槽位字段、**不动窗口**（窗口的摘除由调用方决定）。 */
    private fun clearFloatSlot() {
        floatStyle = null
        floatPayloadState = null
        floatVersionState = null
        floatSignature = 0L
    }

    /**
     * 撤下当前悬浮（**先播退场动画，再摘窗口**）。
     *
     * V8 时序（这是需求「悬浮消失也能配动画」的落地点）：
     * 1. 撤销常驻兜底计时（P0-1）；
     * 2. `requestExit(window)` → 渲染层播 [FloatStyle.animOut] 的退场动画（240ms）；
     * 3. 气泡播完回调 `onFinished()` → 清槽位 + `detachNow`；
     * 4. 万一 300ms 内没等到回调 → 超时任务强制走同一条收尾（窗口绝不会留在屏幕上）。
     *
     * **幂等**：重复调用时 `markExitRequested()` 直接拦住，不会播两次、不会摘两次。
     * 注意：退场期间 [floatWindow] **仍然指向该窗口**（它还在屏幕上），
     * 因此新悬浮到来时 `showFloat` 会把它硬摘替换掉——语义是「新内容优先」。
     */
    fun dismissFloat() {
        mainHandler.post {
            // P0-1：撤悬浮必须同步撤销常驻计时（否则计时器留到下一次悬浮上触发）
            mainHandler.removeCallbacks(residentDismissTask)
            val window = floatWindow ?: return@post
            requestExit(window) {
                // 只清自己：退场期间槽位可能已被新悬浮接管
                if (floatWindow === window) {
                    floatWindow = null
                    // V9.3：内容状态一并清掉，否则下一次 showFloat 会往死掉的窗口里写内容
                    clearFloatSlot()
                }
                detachNow(window)
            }
        }
    }

    // ==================== 悬浮真机预览（V5 需求④） ====================

    /**
     * 悬浮预览的**独立实例**：与业务悬浮 [floatWindow] 互不干扰——配置对话框开着时
     * 一条真实通知来了仍走 floatWindow，预览参数刷新也不会顶掉它。
     */
    @Volatile
    private var floatPreviewWindow: OverlayWindow? = null

    /**
     * 悬浮样式配置的真机预览：把**编辑中的**样式投到屏幕实际位置（与正式悬浮同一渲染
     * 管线，所见即所得）。每次调用重建预览窗口（防抖由调用方负责，~300ms）。
     *
     * V6 需求①：预览实例**忽略 [FloatStyle.displaySeconds] 的自动消失**——预览必须
     * 一直挂在屏幕上（播完就消失的预览等于没有预览）；「停留多久」的语义由
     * 「对话框关闭时撤掉」承担，入场动画重播由本方法的重建驱动（动画参数一变，
     * 对话框侧 animReplayTick 触发重新投影）。
     *
     * @return 是否已投影；false = 无权限 / 未初始化（调用方提示用户授权）。
     */
    fun showFloatPreview(payload: OverlayPayload, style: FloatStyle): Boolean {
        val context = appContext
        if (context == null || !canDrawOverlays()) return false
        mainHandler.post {
            // V8：预览是「编辑中的样式实时投影」，防抖约 300ms 重建一次；若每次重建都先播
            // 240ms 退场，拖动参数会明显拖影，故预览路径**保持硬摘**、也不传 exitSignal
            // （预览 autoDismiss=false，本来就不会自己走退场）。用 detachNow 只为统一撤销
            // 可能残留的超时任务。
            floatPreviewWindow?.let { detachNow(it) }
            floatPreviewWindow = null
            val window = OverlayWindow(context).apply {
                gravity = gravityOf(style.screenAlignEnum)
                // V6 需求②：offsetTopDp 允许 0（顶部行停靠时贴屏幕物理顶端，
                // 盖住状态栏）；FLAG_LAYOUT_NO_LIMITS 已在 attach() 统一带。
                yDp = style.offsetTopDp
                xDp = 0f
            }
            window.setContent {
                NotificationFilterTheme {
                    FloatBubble(
                        payload = payload,
                        style = style,
                        // V6 需求①：预览不自动消失（停留到对话框关闭撤掉）
                        autoDismiss = false,
                        onFinished = {
                            mainHandler.post {
                                // 防回清错窗口：防抖高频重建时，旧实例的退场回调
                                // 不得把刚建的新预览摘了
                                if (floatPreviewWindow == window) {
                                    floatPreviewWindow?.let { detachNow(it) }
                                    floatPreviewWindow = null
                                } else {
                                    detachNow(window)
                                }
                            }
                        },
                    )
                }
            }
            if (window.attach()) {
                floatPreviewWindow = window
            } else {
                NfLog.w(this, "悬浮预览挂载失败（权限或系统限制）")
            }
        }
        return true
    }

    /** 撤掉悬浮真机预览（配置对话框关闭时必须调用）。 */
    fun dismissFloatPreview() {
        mainHandler.post {
            // V8：预览走硬摘（理由见 showFloatPreview 的说明）；统一走 detachNow 以便
            // 撤销超时任务、清 exitingWindows 登记。
            floatPreviewWindow?.let { detachNow(it) }
            floatPreviewWindow = null
        }
    }

    // ==================== 条件卡片（V7 需求 7 / 8） ====================

    /**
     * 条件卡片的**单实例槽位**：与业务悬浮 [floatWindow] **完全独立**。
     *
     * 为什么要独立：条件卡片由 `OverlayConditionArbiter` 按条件驱动、可能常驻很久；
     * 业务悬浮是「一条命中一次」的短时展示。若共用一个槽位，一条新命中的普通悬浮
     * 就会把正在显示的条件汇总卡片顶掉（用户最关心的功能被无关通知打断）。
     */
    @Volatile
    private var conditionalFloatWindow: OverlayWindow? = null

    /** 当前条件卡片的 key（= 规则 id 的字符串形式）；null = 当前无卡片。 */
    @Volatile
    private var conditionalKey: String? = null

    /**
     * 当前条件卡片**已应用**的样式快照。
     *
     * 用途：样式（限高 / 可滚动 / 停靠位置 / 可触摸）变化时窗口布局参数必须重建
     * （`WRAP_CONTENT` 与 `FLAG_NOT_TOUCHABLE` 都是 attach 时一次性写入 LayoutParams 的）。
     * 只有「同 key 且同样式」才走原地换内容，否则重建——避免用户改了卡片高度却看不到变化。
     */
    @Volatile
    private var conditionalStyle: FloatStyle? = null

    /**
     * 条件卡片的内容状态：**内容原地更新靠改这个 MutableState**，不重建窗口
     * （架构 §3.4 红线 4：不重播入场动画、不 reset 停留计时）。
     */
    @Volatile
    private var conditionalPayloadState: MutableState<OverlayPayload>? = null

    /**
     * 已渲染内容的签名（标题 + 正文的 64 位指纹）。
     *
     * 重绘前先比对：**未变则不写状态**。这是「进度类通知每秒刷新」「同一批聚合条目重复
     * 触发」时避免无意义重组的关键保护；同时它也被冻结成 [FloatBubble] 的 `animKey`，
     * 于是同一张卡片的内容更新不会重播入场动画。
     */
    @Volatile
    private var conditionalSignature: Long = 0L

    /** 当前条件卡片的 key（供 Arbiter / 自检读取；null = 无卡片）。 */
    val conditionalFloatKey: String? get() = conditionalKey

    /**
     * 显示 / 替换条件卡片（V7 需求 7 + 8）。
     *
     * **幂等语义**（调用方可以无脑重复调用）：
     * - 传入的 [key] 与当前卡片相同 → **原地换内容**（不重建窗口、不重播动画、不 reset 计时）；
     * - [key] 不同（或当前无卡片）→ 撤下旧卡、重建新卡（新规则替换旧规则，与既有悬浮替换路径一致）。
     *
     * 窗口差异（相对普通悬浮，见 `OverlayWindow.attach()`）：
     * - [FloatStyle.touchable] = true 时：宽度 `WRAP_CONTENT`（最大屏宽 85%，由渲染层约束）、
     *   **去掉 `FLAG_NOT_TOUCHABLE`**（卡片内可手指滚动），水平停靠改由 gravity + `params.x` 完成；
     * - 高度恒 `WRAP_CONTENT`；内容区限高 [FloatStyle.maxHeightDp]，可滚动见 `contentScrollable`；
     * - **不加 30 分钟常驻上限**：条件卡片的消失由条件驱动，不由计时驱动。
     *
     * @param key 规则 id（字符串形式）；同 key 视为同一张卡片
     * @param payload 卡片内容（已渲染好的标题 / 正文）
     * @param style 卡片样式（`touchable` 由调用方按功能推导后写入）
     * @return true = 已投递显示；false = 无悬浮窗权限 / 未初始化（调用方应记为跳过）
     */
    fun showConditionalFloat(key: String, payload: OverlayPayload, style: FloatStyle): Boolean {
        val context = appContext ?: return false
        if (!canDrawOverlays()) return false
        mainHandler.post { applyConditionalCard(context, key, payload, style) }
        return true
    }

    /**
     * 仅更新内容（聚合列表变化 / 同规则新内容）：不重建窗口、不重播动画、不 reset 计时。
     *
     * key 不匹配（当前卡片属于别的规则 / 已无卡片）时**什么都不做**——
     * 避免「旧条件的更新把新卡片改掉」。
     */
    fun updateConditionalFloat(key: String, payload: OverlayPayload) {
        // 快路径：绝大多数调用（非当前卡片的规则）在这里就返回，不投递主线程消息
        if (conditionalKey != key) return
        mainHandler.post { applyConditionalPayload(key, payload) }
    }

    /**
     * 撤下条件卡片。
     *
     * @param key null = 无条件撤下（全清 / 卸载 / 索引清空）；非 null = 只撤「key 匹配」的那张
     *   （旧条件的 HIDE 不能关掉新卡片，架构 §3.9 的防串台口径在此落地）。
     */
    fun dismissConditionalFloat(key: String? = null) {
        mainHandler.post {
            if (key != null && conditionalKey != key) return@post
            val window = conditionalFloatWindow
            if (window == null) {
                // 槽位已空（可能上一张卡片正在退场）→ 只清残留字段，别把空槽变脏
                clearConditionalSlot()
                return@post
            }
            // V8：先让出槽位，再播退场。
            //
            // 顺序很重要：槽位立刻清空 → 退场期间到达的 `updateConditionalFloat` /
            // 新 `showConditionalFloat` 不会再往这张正在消失的卡片里写内容；
            // 窗口本身仍由 requestExit 持有，播完（或超时）才 detachNow。
            clearConditionalSlot()
            requestExit(window) { detachNow(window) }
        }
    }

    /** 条件卡片的实际挂载（**只在主线程**调用）。 */
    private fun applyConditionalCard(
        context: Context,
        key: String,
        payload: OverlayPayload,
        style: FloatStyle,
    ) {
        val existing = conditionalFloatWindow
        if (existing != null && conditionalKey == key && conditionalStyle == style) {
            // 同规则 + 同样式 → 原地换内容（这是「聚合条目新增时不动画重播」的落地点）
            applyConditionalPayload(key, payload)
            return
        }

        // V8：替换 / 重建一律硬摘旧窗口（新卡片要立即上场，不等旧卡退场），
        // 但仍走 detachNow 以撤销旧窗口可能残留的退场超时任务。
        existing?.let { detachNow(it) }
        clearConditionalSlot()

        val state = mutableStateOf(payload)
        // 冻结入场动画 key：同一张卡片后续内容变化不会改变它 → 不重播动画；
        // 新卡片（新窗口 = 新 composition）本来就会播一次，无需 key 变化驱动。
        val animKey = signatureOf(payload)
        // 注意：局部变量不能与 OverlayWindow.touchable 同名——apply{} 内的隐式接收者
        // 优先级高于外层局部变量，`this.touchable = touchable` 会自我赋值
        val isTouchable = style.touchable
        val window = OverlayWindow(context).apply {
            this.touchable = isTouchable
            gravity = gravityOf(style.screenAlignEnum)
            // V9：条件卡片的退场超时同样跟着 animOutMs 走
            exitWaitMs = (style.animOutMs + 60).coerceAtLeast(120).toLong()
            // 垂直偏移仍由 params.y 承担（与 V6 口径一致，渲染层不再加 padding(top)）
            yDp = style.offsetTopDp
            // 不可触摸（宽度 MATCH_PARENT）时水平位置由渲染层的 start/end 内边距负责，
            // 窗口层再设 x 会叠加成双倍偏移；可触摸（WRAP_CONTENT）时才靠 params.x 落位。
            xDp = if (isTouchable) style.offsetEndDp else 0f
        }
        window.setContent {
            NotificationFilterTheme {
                FloatBubble(
                    payload = state.value,
                    style = style,
                    // V8：条件卡片的撤下（dismissConditionalFloat）同样先播退场再摘窗口
                    exitSignal = window.exitSignal,
                    onFinished = {
                        mainHandler.post {
                            // 只摘自己：跨线程投递顺序不保证，此刻槽位可能已是新卡片
                            if (conditionalFloatWindow === window) {
                                clearConditionalSlot()
                            }
                            detachNow(window)
                        }
                    },
                    // 条件卡片**不自动消失**：它的生命周期由条件驱动（V7 需求 7），
                    // 停留秒数对它是无意义的配置
                    autoDismiss = false,
                    // 可触摸窗口是 WRAP_CONTENT：渲染层不能 fillMaxWidth / 不能加 side padding
                    fillWidth = !isTouchable,
                    maxHeightDp = style.maxHeightDp,
                    scrollable = style.contentScrollable,
                    animKey = animKey,
                )
            }
        }
        if (window.attach()) {
            conditionalFloatWindow = window
            conditionalKey = key
            conditionalStyle = style
            conditionalPayloadState = state
            conditionalSignature = animKey
            NfLog.d(this, "条件卡片已显示 key=$key touchable=$isTouchable")
        } else {
            NfLog.w(this, "条件卡片挂载失败（权限或系统限制）")
        }
    }

    /** 原地更新内容（**只在主线程**调用）：内容未变则不写状态，避免无意义重组。 */
    private fun applyConditionalPayload(key: String, payload: OverlayPayload) {
        if (conditionalKey != key) return
        val state = conditionalPayloadState ?: return
        val signature = signatureOf(payload)
        if (signature == conditionalSignature) return
        conditionalSignature = signature
        state.value = payload
    }

    /**
     * 只清槽位字段、**不动窗口**（V8）。
     *
     * 用于「窗口还在播退场动画、但槽位必须立刻让出来」的两种场景：
     * [dismissConditionalFloat] 与 [applyConditionalCard] 的替换路径。
     * 窗口的实际摘除由调用方交给 [requestExit]（退场后）或 [detachNow]（立即替换）。
     */
    private fun clearConditionalSlot() {
        conditionalFloatWindow = null
        conditionalPayloadState = null
        conditionalKey = null
        conditionalStyle = null
        conditionalSignature = 0L
    }

    /** 清空条件卡片槽位并**立刻**摘掉窗口（**只在主线程**调用）。 */
    private fun clearConditionalCard() {
        val window = conditionalFloatWindow
        clearConditionalSlot()
        window?.let { detachNow(it) }
    }

    /**
     * 内容签名（标题 + 正文的 64 位指纹）。
     *
     * 复用管线那套 FNV-1a 指纹（`NotificationFingerprint`）：64 位碰撞概率可忽略，
     * 且与「同 key 内容未变则跳过」用同一口径，排查时不会出现两套指纹互相矛盾。
     */
    private fun signatureOf(payload: OverlayPayload): Long =
        NotificationFingerprint.of(payload.title, payload.text, emptyList())


    // ==================== 全清 ====================

    fun dismissAll() {
        mainHandler.post {
            // ——— V8 取舍：本路径**故意不播退场动画**，一律硬摘 ———
            //
            // 「清除屏幕显示」是首页菜单里用户主动按下的清屏动作，语义是「立刻干净」。
            // 若这里也走 requestExit（等 240ms 退场动画），用户会看到屏幕上的东西
            // 慢慢淡出，第一反应是「点了没反应」然后连点。
            // 因此这是**唯一**绕过「先播退场、再摘窗口」的撤下路径。
            //
            // 但硬摘不等于「裸 detach」：每个窗口仍然走 detachNow（撤销退场超时任务 +
            // 清掉 exitingWindows 登记），所以不会留下 300ms 后才响的延迟消息，
            // 也不会残留任何「正在播退场」的窗口。
            danmakuWindows.forEach { detachNow(it) }
            danmakuWindows.clear()
            danmakuQueue.clear()
            // 轨道占用集合必须一起清空，否则下一轮弹幕永远拿不到 0 号轨
            occupiedTracks.clear()
            // P0-1：常驻悬浮的兜底计时一并撤销（全清之后不该再有延迟消息去动悬浮窗）
            mainHandler.removeCallbacks(residentDismissTask)
            // 悬浮：可能正处在退场动画中（槽位已清但窗口还在），这里一并硬摘
            floatWindow?.let { detachNow(it) }
            floatWindow = null
            clearFloatSlot()
            // 配置对话框的悬浮预览一并撤掉（否则服务断连后预览残留在屏幕上）
            floatPreviewWindow?.let { detachNow(it) }
            floatPreviewWindow = null
            // V7：条件卡片也在「清除屏幕显示」的范围内（首页菜单无需改动即可清掉它）
            clearConditionalCard()
            // V8：槽位已让出、正在播退场的窗口（悬浮 / 条件卡片）不在任何槽位里，
            // 必须靠这张登记表逐个硬摘，否则它们会残留到超时兜底才消失。
            // 用 toList() 拷贝：detachNow 内部会修改 exitingWindows。
            exitingWindows.toList().forEach { detachNow(it) }
            exitingWindows.clear()
            _activeCount.value = 0
        }
    }

    // ==================== 内部：单个叠加窗口 ====================

    /** 九宫格 → WindowManager gravity（悬浮停靠位置）。 */
    private fun gravityOf(align: com.local.notificationfilter.core.model.OverlayAlign): Int {
        val vertical = when (align) {
            com.local.notificationfilter.core.model.OverlayAlign.TOP_START,
            com.local.notificationfilter.core.model.OverlayAlign.TOP_CENTER,
            com.local.notificationfilter.core.model.OverlayAlign.TOP_END,
            -> Gravity.TOP

            com.local.notificationfilter.core.model.OverlayAlign.BOTTOM_START,
            com.local.notificationfilter.core.model.OverlayAlign.BOTTOM_CENTER,
            com.local.notificationfilter.core.model.OverlayAlign.BOTTOM_END,
            -> Gravity.BOTTOM

            else -> Gravity.CENTER_VERTICAL
        }
        val horizontal = when (align) {
            com.local.notificationfilter.core.model.OverlayAlign.TOP_START,
            com.local.notificationfilter.core.model.OverlayAlign.CENTER_START,
            com.local.notificationfilter.core.model.OverlayAlign.BOTTOM_START,
            -> Gravity.START

            com.local.notificationfilter.core.model.OverlayAlign.TOP_END,
            com.local.notificationfilter.core.model.OverlayAlign.CENTER_END,
            com.local.notificationfilter.core.model.OverlayAlign.BOTTOM_END,
            -> Gravity.END

            else -> Gravity.CENTER_HORIZONTAL
        }
        return vertical or horizontal
    }

    /**
     * 一个 WindowManager 窗口 + 其中的 ComposeView。
     *
     * Compose 在无 Activity 场景下需要自行提供 LifecycleOwner / SavedStateRegistryOwner /
     * ViewModelStoreOwner（否则 `setContent` 会抛错），这里用最小实现 [OverlayViewOwner]。
     */
    private class OverlayWindow(private val context: Context) {

        private val wm: WindowManager =
            context.getSystemService(WindowManager::class.java)

        private val owner = OverlayViewOwner()

        private val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }

        /** 停靠位置（WindowManager gravity 组合）。 */
        var gravity: Int = Gravity.TOP or Gravity.START

        /**
         * V9：该窗口退场动画的超时兜底（ms）。
         *
         * 退场动画时长可由用户配置（100..1000ms），固定 300ms 的超时会把「消失时长调到
         * 600ms」的动画从中间掐断。因此每个窗口在创建时按 `animOutMs + 60` 写入本值
         * （下限 120ms），[requestExit] 用它排超时任务。
         */
        var exitWaitMs: Long = EXIT_TIMEOUT_MS

        /**
         * 窗口是否可触摸（V7 需求 8，**仅条件卡片可能为 true**）。
         *
         * 为 false 时保持 V6 语义：宽度 `MATCH_PARENT` + `FLAG_NOT_TOUCHABLE`（点击穿透）。
         * 为 true 时宽度改 `WRAP_CONTENT` 且去掉 `FLAG_NOT_TOUCHABLE`——
         * 否则窗口内无法手指滚动，而 `MATCH_PARENT` 宽的窗口会变成"整条横带"把下方 App 点死。
         * `FLAG_NOT_FOCUSABLE` **始终保留**：卡片不需要输入焦点，保留它才能让窗口外的触摸
         * 继续落到下方 App（不会弹出"挡住"的输入法或抢焦点）。
         */
        var touchable: Boolean = false

        /** 水平偏移（dp，attach 时换算为像素）。 */
        var xDp: Float = 0f

        /** 垂直偏移（dp，attach 时换算为像素）。 */
        var yDp: Float = 0f

        /**
         * 占用的弹幕轨道下标（D7）。由控制器在创建时写入，回收时据此归还轨道。
         * 悬浮窗口不使用（保持 -1）。
         */
        var trackIndex: Int = -1

        /** 定位式弹幕的堆叠分组键（V5）；非定位式弹幕 / 悬浮为 null（不参与重排）。 */
        var stackSignature: Any? = null

        /** 定位式弹幕的样式快照（V5）：补位重排时按它重算该条的堆叠偏移。 */
        var styleForStack: DanmakuStyle? = null

        /**
         * 气泡实测高度（dp，V6 需求③）：渲染层 onSizeChanged 回报；null = 尚未回报。
         * 堆叠/轨道排布按实测高度累计（估算常量只在未回报前兜底）。
         */
        var measuredHeightDp: Float? = null

        // —— V8：退场编排（先播退场、再摘窗口）——

        /**
         * 退场请求信号：传给 [FloatBubble] 的 `exitSignal`。
         *
         * 渲染层用 `snapshotFlow` 监听它，被 request 后播退场动画、播完回调 `onFinished`。
         * 弹幕窗口不使用（弹幕的消失由自身动画驱动，见 [releaseDanmaku]）。
         */
        val exitSignal = OverlayExitSignal()

        /** 退场超时兜底任务（由控制器 post / 撤销；[detachNow] 会 removeCallbacks）。 */
        var exitTimeoutRunnable: Runnable? = null

        /** 是否已经请求过退场（幂等闸门：重复请求不重复播动画、不重复回调）。 */
        private var exitRequested = false

        /**
         * V9.3：外部只读的退场状态。
         *
         * 用途：悬浮「原地换内容」的复用判定——正在播退场动画的窗口马上就要被摘掉，
         * 绝不能把新内容塞进去。`exitRequested` 本身保持 private，避免被误写。
         */
        val isExitRequested: Boolean get() = exitRequested

        /**
         * 标记「已请求退场」，并唤醒渲染层。
         *
         * @return true = **首次**请求（调用方可以继续 post 超时任务）；
         *   false = 已经请求过（幂等，调用方直接返回即可）
         */
        fun markExitRequested(): Boolean {
            if (exitRequested) return false
            exitRequested = true
            exitSignal.request()
            return true
        }

        private var content: (@Composable () -> Unit)? = null

        private var attached = false

        fun setContent(content: @Composable () -> Unit) {
            this.content = content
        }

        /**
         * 原位移动窗口（V5 定位式弹幕补位重排用）：只改 x/y，**不重建 Compose 内容**
         * （不重播淡入/停留动画），窗口内弹幕的停留计时不受影响。
         *
         * P2-7：位置没变就直接早退。[repositionAllStacks] 一次会遍历**所有**存活窗口，
         * 不做判重时每条新弹幕到达都会产生 N 次「位置未变」的 `updateViewLayout`
         * （跨进程 IPC，`WindowManager` 侧还会触发一次 layout 遍历）。
         *
         * 判重依据是本窗口自己的 [xDp]/[yDp]（attach 时写进 `LayoutParams` 的那份缓存值），
         * 它们只在 `apply {}` 初始化、[attach]、本方法三处被写，因此「缓存值 == 期望值」
         * 等价于「窗口已在该位置」，不会漏掉真正需要移动的情况。
         */
        fun updatePosition(xDp: Float, yDp: Float) {
            if (!attached) return
            if (xDp == this.xDp && yDp == this.yDp) return
            this.xDp = xDp
            this.yDp = yDp
            val lp = composeView.layoutParams as? WindowManager.LayoutParams ?: return
            lp.x = dpToPx(xDp)
            lp.y = dpToPx(yDp)
            runCatching { wm.updateViewLayout(composeView, lp) }
                .onFailure { NfLog.w(OverlayController, "弹幕堆叠位置更新失败: ${it.message}") }
        }

        @SuppressLint("InflateParams")
        fun attach(): Boolean {
            val block = content ?: return false
            // V7 需求 8：宽度按「是否可触摸」分流——
            // 可触摸（条件卡片）必须 WRAP_CONTENT，否则整条屏宽都会吃掉触摸；
            // 不可触摸（弹幕 / 普通悬浮 / 预览）保持 V6 的 MATCH_PARENT + 内边距停靠。
            val width = if (touchable) {
                WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            }
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            // 不可触摸：点击穿透给下方 App（避免误触）；可触摸：本次**唯一**去掉的 flag 就是它
            val flags = if (touchable) baseFlags else baseFlags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                // 不可聚焦、不可触摸：叠加层只做展示，点击穿透给下方 App（避免误触）；
                // FLAG_LAYOUT_NO_LIMITS：允许窗口越出屏幕边界（贯穿式弹幕滚出左缘、
                // y 取负值时仍能画到屏幕外）。它**不解除** insets 裁剪——
                // 「TOP 系 y=0 贴屏幕物理顶端」「BOTTOM 系仍留在导航栏上沿」这两件事都由
                // 下面 apply{} 里「只 fit 底边」的 insets 配置负责（V7 需求⑥）。
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // V7 需求⑥：只 fit 底部系统栏（**有意为之，不是「全不 fit」**）。
                //
                // 背景：Android 11(API 30) 起窗口默认按 insets **裁剪**绘制区
                // （LayoutParams.fitInsetsTypes 默认含 systemBars() 与 displayCutout()），
                // 于是 gravity=TOP 的窗口即便 y=0 也被压到状态栏下沿——这就是「自定义位置
                // 悬浮把距顶部调成 0 仍到不了状态栏」的真正原因。
                //
                // 但也不能整个放开（把要 fit 的 insets 类型置空是**回归**）：那样窗口 frame
                // 会撑到屏幕物理底端，Gravity.BOTTOM 的窗口底边落到导航栏**下方**，悬浮卡片 /
                // 定位式弹幕的下半截被手势条或三键导航压住。
                // 故取交集：types 只留 systemBars()，sides 只留 BOTTOM——
                //   * 顶部 / 刘海 insets **不**参与布局 → TOP 系锚点 = 屏幕物理顶端，
                //     y=0 即贴顶（可覆盖状态栏区域，V6 需求② / V7 需求⑥）；
                //   * 底部导航栏 inset **参与**布局 → BOTTOM 系锚点 = 导航栏上沿，
                //     卡片不会被手势条压住（保持 V6 之前的观感，无回归）；
                //   * CENTER_VERTICAL 系锚点 = 上述可用区的垂直正中。
                //
                // 口径确认：各窗口的 y 仍由 [dpToPx] 直接换算、配合 gravity 从**停靠边**
                // 量起（正数一律向内），本次只改「可用区边界」、不改偏移算法，
                // 因此贯穿式轨道 / 定位式堆叠的偏移计算完全不受影响。
                //
                // API 说明：setFitInsetsTypes(int) 与 setFitInsetsSides(int) 都是
                // WindowManager.LayoutParams 的公开方法，**API 30 才加入**（与
                // fitInsetsTypes / fitInsetsSides 字段同批）；旧版本不存在 → 必须包版本判断，
                // 否则 NoSuchMethodError。WindowInsets.Type / WindowInsets.Side 同样是
                // API 30 才有的嵌套类，故引用也只在同一个 >= R 分支内执行。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(WindowInsets.Type.systemBars())
                    setFitInsetsSides(WindowInsets.Side.BOTTOM)
                }
                // layoutInDisplayCutoutMode 字段 **API 28 才有**（同样必须版本判断）；
                // ALWAYS(API 30) 才是「允许画进刘海区」，28/29 只能用 SHORT_EDGES。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        } else {
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                }
                this.gravity = this@OverlayWindow.gravity
                this.x = dpToPx(this@OverlayWindow.xDp)
                this.y = dpToPx(this@OverlayWindow.yDp)
            }
            return runCatching {
                owner.onCreate()
                composeView.setContent { block() }
                wm.addView(composeView, params)
                // 先置 attached 再 onResume：若 onResume 抛异常，detach() 才不会因
                // attached=false 提前 return、把已挂上的窗口永久留在屏幕上。
                attached = true
                owner.onResume()
                true
            }.onFailure {
                NfLog.e(OverlayController, "叠加窗口挂载失败: ${it.message}")
                // 与 D17 同源的生命周期收尾：挂载中途失败时 owner 可能停在 CREATED，
                // 若不销毁，LifecycleRegistry 与 ViewModelStore 会永久残留。
                if (attached) {
                    detach()
                } else {
                    runCatching { owner.onDestroy() }
                }
            }.getOrDefault(false)
        }

        fun detach() {
            if (!attached) return
            // D17 修复销毁顺序：**先**把 ComposeView 从窗口摘除，**再**销毁 Lifecycle。
            // 原来的顺序（onDestroy → removeView）会在 Lifecycle 已进入 ON_DESTROY 之后
            // 仍让视图留在窗口里，Compose 可能在已销毁的 owner 上继续组合/重组并抛异常，
            // 被下面的 runCatching 吞掉后表现为「偶发窗口残留 + 一条 warn 日志」。
            // 顺序修正后两步各自独立兜底，任何一步失败都不影响另一步继续执行。
            runCatching {
                wm.removeView(composeView)
            }.onFailure {
                NfLog.w(OverlayController, "叠加窗口 removeView 失败: ${it.message}")
            }
            runCatching {
                owner.onDestroy()
            }.onFailure {
                NfLog.w(OverlayController, "叠加窗口 Lifecycle 销毁失败: ${it.message}")
            }
            attached = false
        }

        /** dp → 像素（WindowManager.LayoutParams 的 x/y 单位是像素）。 */
        private fun dpToPx(dp: Float): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        ).toInt()
    }

    /** 无 Activity 场景下的最小三合一 Owner。 */
    private class OverlayViewOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        override val viewModelStore: ViewModelStore get() = store

        fun onCreate() {
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

}

/** 排队上限（超出丢最旧，防通知轰炸把队列撑爆）。 */
private const val MAX_QUEUE = 20

/** 单条弹幕条目估算高度（dp）：贯穿式轨道间距 / 定位式堆叠步进共用。 */
private const val TRACK_HEIGHT_DP = 48f

/**
 * V9.6：贯穿式弹幕的轨道号上限（仅用于 `occupiedTracks` 登记，**不再限制同屏条数**）。
 *
 * 真正决定「能显示几条」的是屏幕还剩多少空位（见 `findFreeYDp`）。
 * 这里给一个足够大的值，只为避免占用集合无限增长。
 */
private const val MAX_TRACKS = 64

/** 定位式弹幕「逐条依次出现」的淡入错峰间隔（ms）：第 i 条延迟 i × 本值。 */
private const val STACK_STAGGER_MS = 150

/**
 * 退场动画的**超时兜底**（ms，V8）。
 *
 * 语义：控制器请求退场后，最迟在这个时间内必须收到气泡的「退场动画已播完」回调，
 * 否则强制 `removeView`。**窗口绝不会因为动画没回调而永久留在屏幕上。**
 *
 * 取值 300ms = 退场动画 `OverlayRender.EXIT_ANIM_MS`（240ms）+ 60ms 余量：
 * - 230ms 起手延迟已吃掉一帧重组（退场信号是 `MutableState`，落地要等下一次组合），
 *   再叠加 `onFinished` → `mainHandler.post` 的一次主线程投递；
 * - 为什么不取 250ms：240ms 的动画在 250ms 处被掐，**每一条**退场都会缺最后一帧，
 *   观感是「淡到 5% 忽然消失」，比不做动画还生硬。
 *
 * 上界仍然是硬性的：配置页/条件卡片这类「一直挂在屏幕上」的窗口，即便渲染层完全不
 * 响应退场信号，300ms 后也一定被摘掉。
 */
private const val EXIT_TIMEOUT_MS = 300L

/**
 * 常驻悬浮（`FloatStyle.displaySeconds <= 0`）的最大存活时长（ms，P0-1）。
 *
 * 30 分钟：常驻语义是「一直挂着直到下一条命中替换」，但窗口不可触摸、无通知栏入口，
 * 必须留一个保底退出路径。取值远大于任何「看完这条通知」的停留需求，同时能保证
 * 忘关的悬浮不会留到第二天。
 */
private const val MAX_FLOAT_RESIDENT_MS = 30 * 60 * 1000L
