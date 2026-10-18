package com.local.notificationfilter.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * - **悬浮多实例**（V12：按规则 id 一规则一窗，同停靠位自动级联避让）：
 *   每条规则的悬浮各占一个独立窗口（[showFloat] 的 key = 规则 id 字符串），不同规则
 *   同时在屏幕上显示、**互不顶掉**；同停靠位 + 同偏移的多个窗口按显示顺序级联避让
 *   （步进 [FLOAT_CASCADE_STEP_DP]），同屏上限 [MAX_FLOAT_WINDOWS]（安全阀）。
 *   每条悬浮 [FloatStyle.displaySeconds] 秒后自动消失，0 = 常驻；常驻实例另有
 *   [MAX_FLOAT_RESIDENT_MS] 上限兜底 + 首页菜单「清除屏幕显示」可随时撤下，见 [dismissAll]）；
 *   规则编辑页的预览按钮走固定 key [PREVIEW_FLOAT_KEY]，配置对话框的真机预览走独立实例
 *   [floatPreviewWindow]，两者与业务窗口互不干扰；
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
     * 为什么要单独记：撤下悬浮时，槽位（[floatSlots] 中的表项）会**立刻让出**
     * （否则新内容会写进一张正在消失的窗口），于是这些窗口已经不在任何槽位里了。
     * 若不在全清时逐个硬摘，它们只能等 [EXIT_TIMEOUT_MS] 超时兜底才真正消失，
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
            // V10.7 耗电修复：定位式弹幕是固定格子的堆叠，格子满了就排队（行为不变）。
            // V11.8：**贯穿式不再按条数限流** —— 用户要求「可以超过 5 条」。
            // 同屏条数改由「一条轨道上能否再塞一条」（[findThroughYDp]）决定：
            // 轨道能不能复用取决于前一条滚出去多远，天然不会重叠；
            // 真放不下才排队。仅保留一个安全上限，防止极端情况挂出上百个全宽窗口。
            if (style.modeEnum == DanmakuMode.POSITIONED) {
                val limit = style.maxConcurrent.coerceAtLeast(1)
                if (danmakuWindows.count { it.stackSignature != null } >= limit) {
                    if (danmakuQueue.size >= MAX_QUEUE) danmakuQueue.removeFirst()
                    danmakuQueue.addLast(payload to style)
                    NfLog.d(this, "定位式堆叠已满 $limit，排队等待（队列 ${danmakuQueue.size}）")
                    return@post
                }
            } else if (danmakuWindows.size >= MAX_DANMAKU_WINDOWS) {
                if (danmakuQueue.size >= MAX_QUEUE) danmakuQueue.removeFirst()
                danmakuQueue.addLast(payload to style)
                NfLog.d(this, "贯穿式弹幕达安全上限 $MAX_DANMAKU_WINDOWS，排队等待（队列 ${danmakuQueue.size}）")
                return@post
            }
            showDanmakuWindow(context, payload, style)
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
     * 贯穿式弹幕窗口：从右向左滚过屏幕。
     *
     * V11.8：**同一 y 轨道允许同时挂多条**（只要不重叠，见 [findThroughYDp]），
     * 因此这里不再占用 `trackIndex`（贯穿式的轨道归属只由 `yDp` 决定）。
     */
    private fun showThroughDanmaku(context: Context, payload: OverlayPayload, style: DanmakuStyle) {
        // V9.6 重写（两个真问题的根因都在这）：
        // ① 条数被 `maxConcurrent` 硬顶住 —— 用户要「不限条数，只要不遮住前面的」；
        // ② y 按「轨道号 × 固定 48dp」算，实测高度一回报就整列重排 ——
        //    屏幕上的弹幕会**突然变个位置**（真机反馈原话）。
        // 现在：y 一旦分配就**永不再动**；能复用已有轨道就复用（不排队），真放不下才排。
        val estimatedHDp = TRACK_HEIGHT_DP
        val yDp = findThroughYDp(context, style, estimatedHDp)
        if (yDp == null) {
            if (danmakuQueue.size >= MAX_QUEUE) danmakuQueue.removeFirst()
            danmakuQueue.addLast(payload to style)
            NfLog.d(this, "屏幕已无空位，弹幕排队等待（队列 ${danmakuQueue.size}）")
            return
        }
        val window = OverlayWindow(context).apply {
            gravity = Gravity.TOP or Gravity.START
            xDp = 0f
            this.yDp = yDp
            // 样式留存：定位式堆叠重排需要（贯穿式不重排）
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
                    // V11.8：滚动真正开始时算一次「这条轨什么时候可以再塞一条」——
                    // 气泡右缘离开屏幕右边留白所需的时间。
                    onScrollStarted = { bubbleWidthPx, _ ->
                        mainHandler.post {
                            val density = context.resources.displayMetrics.density
                            val gapPx = DANMAKU_REUSE_GAP_DP * density
                            val speedPx = style.speedPxps.coerceAtLeast(1f)
                            window.trackReuseAtMs = SystemClock.uptimeMillis() +
                                (((bubbleWidthPx + gapPx) / speedPx) * 1000f).toLong()
                        }
                    },
                )
            }
        }
        if (!window.attach()) {
            NfLog.w(this, "弹幕窗口挂载失败（权限或系统限制）")
            drainQueue()
            return
        }
        danmakuWindows += window
        _activeCount.value = danmakuWindows.size
    }

    /**
     * 给一条贯穿式弹幕选 y（dp）；放不下返回 null（调用方排队）。
     *
     * 顺序是「先复用、再新开」：
     * 1. **复用**：同一条 y 上允许多条并存（V11.8）。只要该轨道最新那条的右缘已滚离
     *    屏幕右侧留白（`trackReuseAtMs` 到点），新的一条从右缘起滚就不会挡住它；
     * 2. **新开**：没有可复用的，才在下方找一条空轨道（逐段跳过已占区间，
     *    高度优先用实测值，未测到用估算值）；
     * 3. 都放不下 → null。
     *
     * 返回的 y 一旦分配就**永不改动**——这是修掉「位置突然跳变」的关键：
     * 别人滚完、或者实测高度回报，都不会再触发贯穿式重排。
     */
    private fun findThroughYDp(context: Context, style: DanmakuStyle, estimatedHDp: Float): Float? {
        val now = SystemClock.uptimeMillis()
        val through = danmakuWindows.filter { it.stackSignature == null }
        // ① 复用：同一条轨道上**可以有多条弹幕同时在滚**（V11.8）。
        //    判据 = 这条轨道上**最新**那条的右缘已经滚离屏幕右边（`trackReuseAtMs` 已到）。
        //    此时新的这条从右缘起滚，追不上前面那条，绝不会挡住它。
        //    这正是「歌词慢一步」的解法：以前一条轨道同时只能有一条，新歌词只能排队，
        //    等前一条整条滚完（好几秒）才出，看起来永远慢一句。
        through.groupBy { it.yDp }
            .entries
            .filter { (_, ws) -> ws.all { it.trackReuseAtMs <= now } }
            .minByOrNull { it.key }
            ?.let { return it.key }
        // ② 没有可复用的轨道 → 在下方找一条新的空轨道
        val gap = style.trackGapDp
        val occupied = through
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

    // ==================== 悬浮（V12：多窗口，一规则一窗） ====================

    /**
     * 预览悬浮的固定 key（规则编辑页预览按钮用；与业务窗口互不干扰）。
     *
     * 业务窗口的 key 是规则 id 的字符串形式，预览内容不属于任何规则，故用这个
     * 不可能与规则 id 冲突的哨兵值独占一个槽位。
     */
    const val PREVIEW_FLOAT_KEY = "__preview__"

    /**
     * 一个悬浮窗口的完整槽位（V12）。
     *
     * 与旧「单实例」字段（floatWindow / floatStyle / …）一一对应，只是按 key 存进
     * [floatSlots] 表：每条规则的悬浮各占一行，互不顶掉。
     */
    private class FloatSlot(val key: String) {
        /** 该 key 当前挂着的窗口；null = 槽位已撤 / 重建中。 */
        var window: OverlayWindow? = null

        /** 已应用的样式快照；null = 无悬浮 / 刚被清槽。样式变化才重建窗口。 */
        var style: FloatStyle? = null

        /** 内容状态：写入即重组，不重建窗口。 */
        var payloadState: MutableState<OverlayPayload>? = null

        /** 内容版本：每次重新显示 +1，驱动「停留 N 秒」重新计时（见 FloatBubble.timerKey）。 */
        var versionState: MutableState<Int>? = null

        /** 当前内容签名；未变则不写状态（避免进度类通知每秒刷新时无意义重组）。 */
        var signature: Long = 0L

        /** 常驻悬浮的「最大存活时长」兜底计时（P0-1）；null = 未排 / 已撤销。 */
        var residentRunnable: Runnable? = null
    }

    /**
     * key（规则 id 字符串 / [PREVIEW_FLOAT_KEY]）→ 槽位；**只在主线程访问**。
     *
     * LinkedHashMap 的插入序 = 显示序：级联避让（[cascadeYDp]）与「达上限撤最早的」
     * 都按它算。因此同 key 换样式重建时**不能 remove 再 put**（那会把它挪到队尾、
     * 级联位置跳变），必须原地清字段（见 [applyFloatShow] 的样式变化分支）。
     */
    private val floatSlots = LinkedHashMap<String, FloatSlot>()

    /**
     * [floatSlots] 键集合的跨线程快照（编排器在协程线程读「窗口还在不在」）。
     * 每次表结构变动都在主线程经 [syncFloatKeys] 重写；跨线程读取方只拿这份不可变快照，
     * 绝不直接碰 [floatSlots]。
     */
    @Volatile
    private var floatKeySnapshot: Set<String> = emptySet()

    /** 当前活跃的悬浮窗口 key 集合（跨线程安全快照，供 Arbiter 判断窗口是否还在）。 */
    val floatKeys: Set<String> get() = floatKeySnapshot

    // —— V9.3：业务悬浮的「原地换内容」状态 ——
    //
    // 用户反馈「无动画要能跟上一次悬浮弹窗无缝显示」：原来每条命中都 detach 旧窗口 +
    // 新建一个，于是屏幕上必然出现「旧的一下没了、新的一条重新入场」——选了「无动画」
    // 也还是有一下跳变。改成同一样式直接在**同一个窗口里换内容**：ComposeView 不重建、
    // 入场动画不重播、位置不重算，观感就是上一条直接变成新的一条。
    //
    // V12：这套状态搬进 [FloatSlot]，每条规则一份，于是「A 规则在换内容」完全不会
    // 碰到 B 规则的窗口。

    /**
     * 显示（或替换）某个 key 的悬浮卡片（V12：一规则一窗）。
     *
     * 语义（与旧单实例版一致，只是按 key 各管各的）：
     * - 同 key + 同样式 → **原地换内容**（不重建窗口、不重播动画，V9.3）；
     * - 同 key + 样式变了 → 硬摘旧窗口、**原位**重建（新内容优先，V8 替换语义；
     *   原位 = 槽位对象留在 [floatSlots] 原处，级联顺序不变）；
     * - 新 key → 新开槽位；同屏达 [MAX_FLOAT_WINDOWS] 时先撤最早的（插入序第一个）。
     *
     * @param key 悬浮归属：规则 id 的字符串形式，或 [PREVIEW_FLOAT_KEY]
     * @return 是否已显示；false 表示无权限 / 未初始化。
     */
    fun showFloat(key: String, payload: OverlayPayload, style: FloatStyle): Boolean {
        val context = appContext
        if (context == null || !canDrawOverlays()) return false
        mainHandler.post { applyFloatShow(context, key, payload, style) }
        return true
    }

    /**
     * 原地刷新某个 key 的悬浮内容（V12；主线程执行）。
     *
     * key 不在表里（用户刚清屏 / 已自动消失）就**什么都不做**——
     * 绝不让「后台来了一条聚合条目」把用户刚清掉的悬浮重新拉起来。
     */
    fun updateFloat(key: String, payload: OverlayPayload) {
        // 快路径：跨线程只读快照，绝大多数调用（窗口不在）在这里就返回，不投递主线程消息
        if (key !in floatKeySnapshot) return
        mainHandler.post {
            if (key !in floatSlots) return@post
            applyFloatContent(key, payload)
        }
    }

    /**
     * 撤下悬浮（**先播退场动画，再摘窗口**）。
     *
     * V8 时序（这是需求「悬浮消失也能配动画」的落地点）：
     * 1. 撤销该 key 的常驻兜底计时（P0-1）；
     * 2. 槽位**立刻出表**（退场期间到达的新 showFloat 不会往这张消失的窗口里写内容，
     *    也不会被这次退场回调误摘——onFinished 里按 `window` 身份校验）；
     * 3. `requestExit(window)` → 渲染层播 [FloatStyle.animOut] 的退场动画；
     * 4. 气泡播完回调 `onFinished()` → `detachNow`；超时兜底走同一条收尾。
     *
     * **幂等**：重复调用时 `markExitRequested()` 直接拦住，不会播两次、不会摘两次。
     *
     * @param key null = 全部撤下；非 null = 只撤该 key
     */
    fun dismissFloat(key: String? = null) {
        mainHandler.post {
            val targets: List<FloatSlot>
            if (key == null) {
                targets = floatSlots.values.toList()
                floatSlots.clear()
                syncFloatKeys()
            } else {
                val slot = floatSlots.remove(key)
                targets = if (slot == null) emptyList() else listOf(slot)
                if (slot != null) syncFloatKeys()
            }
            targets.forEach { slot ->
                // P0-1：撤悬浮必须同步撤销常驻计时（否则计时器留到下一次悬浮上触发）
                slot.residentRunnable?.let { mainHandler.removeCallbacks(it) }
                slot.residentRunnable = null
                slot.window?.let { requestExit(it) { detachNow(it) } }
            }
            // 撤掉一个后，同组后面的窗口按新顺序顶上（原位移动，不重建、不重播动画）
            recomputeCascades()
        }
    }

    /**
     * 悬浮窗口的实际挂载 / 替换（**只在主线程**调用）。
     *
     * @param key 悬浮归属（见 [showFloat]）
     */
    private fun applyFloatShow(
        context: Context,
        key: String,
        payload: OverlayPayload,
        style: FloatStyle,
    ) {
        // —— V9.3：同 key 同样式 → 原地换内容（无缝衔接上一次悬浮）——
        //
        // 注意 `isExitRequested`：正在播退场动画的窗口马上就要没了，不能复用，
        // 必须重建窗口（旧窗口硬摘，新内容优先，与 V8 的替换语义一致）。
        val existing = floatSlots[key]
        if (existing != null && existing.style == style &&
            existing.window?.isExitRequested == false
        ) {
            applyFloatContent(key, payload)
            scheduleResidentDismiss(existing, style)
            return
        }

        // 同屏窗口数达安全上限：撤最早的（插入序第一个），防止规则轰炸挂出几十个窗口
        if (existing == null && floatSlots.size >= MAX_FLOAT_WINDOWS) {
            val oldestKey = floatSlots.keys.first()
            floatSlots.remove(oldestKey)?.let { oldest ->
                oldest.residentRunnable?.let { mainHandler.removeCallbacks(it) }
                oldest.window?.let { detachNow(it) }
            }
            syncFloatKeys()
            NfLog.d(this, "悬浮窗口达上限 $MAX_FLOAT_WINDOWS，撤下最早的 key=$oldestKey")
        }

        // V8：样式变化（同 key 换样式）一律硬摘旧窗口（新内容要立即上场，不等旧窗退场），
        // 但仍走 detachNow 以撤销旧窗口可能残留的退场超时任务。
        // 槽位对象**保留在 map 原位**（LinkedHashMap put 已存在 key 不改顺序），
        // 只把窗口与状态字段清空待重建——级联避让的相对位置因此不变。
        existing?.let { slot ->
            slot.residentRunnable?.let { mainHandler.removeCallbacks(it) }
            slot.residentRunnable = null
            slot.window?.let { detachNow(it) }
            slot.window = null
            slot.style = null
            slot.payloadState = null
            slot.versionState = null
            slot.signature = 0L
        }
        val slot = existing ?: FloatSlot(key).also {
            floatSlots[key] = it
            syncFloatKeys()
        }

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
            // V12：同停靠位 + 同偏移的多个窗口按显示顺序级联避让，防止完全重叠。
            yDp = cascadeYDp(key, style)
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
                            // 此刻同 key 的槽位可能**已经是新窗口**，无条件清槽 / detach
                            // 会把新悬浮闪一下就摘掉（预览路径早已按此写法修正）。
                            if (floatSlots[key]?.window === window) {
                                discardFloatSlot(key)
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
            slot.window = window
            slot.style = style
            slot.payloadState = payloadState
            slot.versionState = versionState
            slot.signature = signatureOf(payload)
            // P0-1：displaySeconds <= 0 = 常驻。常驻窗口带 FLAG_NOT_TOUCHABLE
            // （点不中）且没有通知栏入口，若不设上限就会**永久挂在屏幕上**。
            // 这里给 30 分钟的兜底存活时长，用户另有「首页菜单 → 清除屏幕显示」可随时撤。
            // 预览实例（showFloatPreview）**不走这条路径**，由配置页的 DisposableEffect 管。
            scheduleResidentDismiss(slot, style)
            recomputeCascades()
        } else {
            NfLog.w(this, "悬浮窗口挂载失败（权限或系统限制）key=$key")
            discardFloatSlot(key)
        }
    }

    /**
     * 原地更新某个 key 的悬浮内容（**只在主线程**调用）。
     *
     * 内容没变也照样 +版本号：语义是「这条又命中了一次」，停留 N 秒要重新开始计。
     * 内容变了才写状态（避免进度类通知每秒刷新时的无意义重组）。
     */
    private fun applyFloatContent(key: String, payload: OverlayPayload) {
        val slot = floatSlots[key] ?: return
        val state = slot.payloadState ?: return
        val version = slot.versionState ?: return
        val signature = signatureOf(payload)
        if (signature != slot.signature) {
            slot.signature = signature
            state.value = payload
        }
        version.value += 1
    }

    /**
     * 撤表 + 取消该槽常驻计时 + 刷新快照与级联（**不动窗口**，窗口由调用方 detach）。
     *
     * 调用点：[applyFloatShow] 挂载失败、FloatBubble 的 `onFinished`（退场播完）。
     */
    private fun discardFloatSlot(key: String) {
        val slot = floatSlots.remove(key) ?: return
        slot.residentRunnable?.let { mainHandler.removeCallbacks(it) }
        slot.residentRunnable = null
        syncFloatKeys()
        recomputeCascades()
    }

    /** 把 [floatSlots] 的键集合写成跨线程快照（**只在主线程**调用）。 */
    private fun syncFloatKeys() {
        floatKeySnapshot = LinkedHashSet(floatSlots.keys)
    }

    /**
     * 常驻悬浮的「最大存活时长」兜底计时（P0-1，V12 起每槽一份）。
     *
     * 为什么需要：`displaySeconds <= 0` 的常驻悬浮窗口带 `FLAG_NOT_TOUCHABLE`
     * （点击穿透，点不中）且没有通知栏入口——一旦挂上就可能永久留在屏幕上，
     * 用户只能靠「清除屏幕显示」关掉。故对常驻实例设 30 分钟上限。
     *
     * 撤销点（缺一处就会出现「旧计时关掉新窗口」）：同 key 原地换内容 /
     * 换样式重建（[applyFloatShow]）、撤下（[dismissFloat] / [discardFloatSlot]）、
     * 全清（[dismissAll]）——全部经本方法取消旧计时后再按需重排。
     */
    private fun scheduleResidentDismiss(slot: FloatSlot, style: FloatStyle) {
        slot.residentRunnable?.let { mainHandler.removeCallbacks(it) }
        slot.residentRunnable = null
        // displaySeconds > 0 = 到点自行消失，不需要常驻兜底
        if (style.displaySeconds > 0f) return
        val runnable = Runnable {
            NfLog.d(this, "常驻悬浮达到最大存活时长，自动撤下 key=${slot.key}")
            dismissFloat(slot.key)
        }
        slot.residentRunnable = runnable
        mainHandler.postDelayed(runnable, MAX_FLOAT_RESIDENT_MS)
    }

    /**
     * 同停靠位多窗口的级联避让 y（dp，V12）。
     *
     * 防的是「多条规则都配了默认位置（TOP_END + 120dp）」时多个窗口**完全重叠**：
     * 数「表中排在 [key] 之前、同组（[FloatStyle.screenAlignEnum] 与
     * [FloatStyle.offsetTopDp] 都相同）」的槽位数 i，按 i 逐步偏移——
     * BOTTOM 系停靠向上避让（`offsetTopDp - i×step`，否则会越避让越出屏幕），
     * 其余向下（`offsetTopDp + i×step`）。
     *
     * 对**已在表中**的 key（换样式重建）只数排在它前面的，相对位置保持不变。
     */
    private fun cascadeYDp(key: String, style: FloatStyle): Float {
        var index = 0
        for (existingKey in floatSlots.keys) {
            if (existingKey == key) break
            val other = floatSlots[existingKey]?.style ?: continue
            if (other.screenAlignEnum == style.screenAlignEnum &&
                other.offsetTopDp == style.offsetTopDp
            ) {
                index++
            }
        }
        val step = index * FLOAT_CASCADE_STEP_DP
        return when (style.screenAlignEnum) {
            OverlayAlign.BOTTOM_START,
            OverlayAlign.BOTTOM_CENTER,
            OverlayAlign.BOTTOM_END,
            -> style.offsetTopDp - step

            else -> style.offsetTopDp + step
        }
    }

    /**
     * 表中槽位增减后重算级联避让位置（**只在主线程**调用）。
     *
     * 只对**位置真的变了**的窗口发 `updateViewLayout`（[OverlayWindow.updatePosition]
     * 内部有判重），原位移动、不重建 Compose 内容，不重播动画、不打断停留计时。
     */
    private fun recomputeCascades() {
        if (floatSlots.size <= 1) return
        for ((key, slot) in floatSlots) {
            val style = slot.style ?: continue
            val window = slot.window ?: continue
            window.updatePosition(0f, cascadeYDp(key, style))
        }
    }

    // ==================== 悬浮真机预览（V5 需求④） ====================

    /**
     * 悬浮预览的**独立实例**：与业务悬浮（[floatSlots] 里的窗口）互不干扰——配置对话框
     * 开着时一条真实通知来了仍走业务窗口，预览参数刷新也不会顶掉它。
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
            // P0-1：每个悬浮窗口的常驻兜底计时一并撤销（全清之后不该再有延迟消息去动悬浮窗）
            floatSlots.values.toList().forEach { slot ->
                slot.residentRunnable?.let { mainHandler.removeCallbacks(it) }
                slot.residentRunnable = null
                // 悬浮：可能正处在退场动画中（槽位已清但窗口还在），这里一并硬摘
                slot.window?.let { detachNow(it) }
            }
            floatSlots.clear()
            syncFloatKeys()
            // 配置对话框的悬浮预览一并撤掉（否则服务断连后预览残留在屏幕上）
            floatPreviewWindow?.let { detachNow(it) }
            floatPreviewWindow = null
            // V8：槽位已让出、正在播退场的窗口（悬浮）不在任何槽位里，
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
         * 窗口是否可触摸（V7 需求 8 引入；V12 起条件卡片整段删除，当前所有悬浮窗口
         * 恒 false = 点击穿透，机制保留给将来可能的可触摸悬浮）。
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

        /**
         * 贯穿式弹幕：这条轨道**什么时候可以再塞一条**（uptimeMillis，V11.8）。
         *
         * 由渲染层在滚动真正开始时按「气泡宽 + 右缘留白」÷ 速度算出并回填；
         * 默认 [Long.MAX_VALUE] = 还没开始滚，绝不复用（保守，避免重叠）。
         */
        var trackReuseAtMs: Long = Long.MAX_VALUE

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

/**
 * 同屏悬浮窗口上限（V12 安全阀）。
 *
 * 用户要求「不同规则的悬浮窗能够同时存在」，因此不再有「一条顶一条」的天然限流；
 * 但每个窗口都是一个全宽 Window + 独立 ComposeView/Recomposer/Surface，必须留一个
 * 防失控的硬顶。8 条远超实际观感需求（通常同时可见的规则悬浮也就 2~4 条），
 * 达上限时撤下**最早显示**的那个（[OverlayController.floatSlots] 插入序第一个）。
 */
private const val MAX_FLOAT_WINDOWS = 8

/**
 * 同停靠位多窗口级联避让的步进（dp，V12）。
 *
 * 多条规则都配了默认位置（同 [com.local.notificationfilter.core.model.OverlayAlign] +
 * 同 offsetTopDp）时，第 i 个窗口相对基准偏移 i×本值，肉眼可辨、不会完全重叠。
 * 14dp ≈ 一个紧凑行高的一半，错开明显又不至于把卡片推出屏幕。
 */
private const val FLOAT_CASCADE_STEP_DP = 14f

/** 排队上限（超出丢最旧，防通知轰炸把队列撑爆）。 */
private const val MAX_QUEUE = 20

/** 单条弹幕条目估算高度（dp）：贯穿式轨道间距 / 定位式堆叠步进共用。 */
private const val TRACK_HEIGHT_DP = 48f

/**
 * 贯穿式弹幕「同一条轨道可以再塞一条」所需的右缘留白（dp，V11.8）。
 *
 * 前一条的气泡右缘必须已经滚离屏幕右边这个距离，新的一条才允许从右缘起滚——
 * 这样两条之间始终隔着一段空白，肉眼上绝不会重叠。
 */
private const val DANMAKU_REUSE_GAP_DP = 24f

/**
 * 贯穿式弹幕同时挂载的**安全上限**（V11.8）。
 *
 * 用户要求「可以超过 5 条」，所以不再按「同屏最多条数」限流；但每条弹幕都是一个
 * 全宽独立 Window + 独立 ComposeView/Recomposer/Surface，必须留一个防失控的硬顶。
 * 24 条远超实际观感需求（正常情况下轨道数 × 2~3 就到头了）。
 */
private const val MAX_DANMAKU_WINDOWS = 24

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
