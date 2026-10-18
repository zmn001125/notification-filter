package com.local.notificationfilter.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * 长按拖动排序的 LazyColumn（用户设计修订 #3：上移下移可以直接拖动）。
 *
 * 设计要点（架构 T09）：
 * - 拖动检测挂在**容器级** [detectDragGesturesAfterLongPress]：手指落在非消耗型区域
 *   （如 ⠿ 手柄，手柄本身不设 clickable）时长按即起拖；卡片其余区域仍是普通点击。
 * - 命中判定用 [LazyListState.layoutInfo] 的 visibleItemsInfo，按下/拖动时反查手指
 *   所在条目索引；跨条目即触发一次交换（hover 交换）。
 * - 拖动过程只做**内存重排**（[onMove] 由调用方改本地列表），松手才回调
 *   [onDragFinished] 持久化，避免每移动一像素写一次库。
 * - 靠近视口上/下边缘时自动滚动（每帧 16ms 步进）。
 *
 * V8 需求②「拖动要有视觉反馈」的三段表现（只改本共享组件的绘制层，
 * 不影响拖动检测与落点计算）：
 * 1. **拖动中**：被拖项 1.02 倍放大 + 10dp 抬升阴影 + zIndex 置顶；
 * 2. **让位**：相邻项用 [androidx.compose.foundation.lazy.LazyItemScope.animateItem]
 *    （Compose 1.7 起替代已废弃的 `animateItemPlacement`）平滑位移；
 * 3. **回落**：松手 draggingKey 置空后，[animateFloatAsState] 以 120ms 把放大与阴影收回常态，
 *    动画走完再把抬升图层**整个摘掉**（见 [LIFT_CLEANUP_GRACE_MS]）。
 *
 * V9.4 需求「卡片跟手」：被抓起的那张卡片跟着手指走（而不是只在列表重排时跳位）。
 * 口径见 [startItemTop] —— 目标顶边 = 起拖顶边 + 手指位移，再减去列表重排/自动滚动
 * 已经把它挪走的量。跟手偏移与 lift 一样只在绘制期计算，拖动帧零重组。
 *
 * V9.4 修复「拖动时卡片底部出现不明意义的白色」：
 * 抬升图层（[Modifier.graphicsLayer]）只挂在**被抓起的那一项**上（条件化 modifier），
 * 其余条目完全没有图层——不产生 outline、不产生阴影轮廓、不画描边。
 * 旧实现给**每一条**都无条件挂 `graphicsLayer` 并写入 `shape = liftShape`，
 * 于是每一条都带着一圈圆角 outline；再叠加一行 1dp 主题主色 `border`
 * （画在整行 Box 上，比卡片左右各宽 16dp，本身就「不明意义」，深色主题下主色
 * 是很浅的蓝白/灰白，看起来就是一圈白边、底部那一段最扎眼）。
 * 现在：描边整条删除；`shadowElevation` / `shape` 只在 `factor > 0` 时生效，
 * 归零立刻还原 `RectangleShape` + 0 阴影，随后整层摘掉——静止态零残留。
 *
 * @param onMove from/to 均为当前列表下标
 * @param onDragFinished 发生过真实移动后的松手回调（用于持久化 order）
 */
@Composable
fun <T> DragReorderLazyColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    itemContent: @Composable (item: T, isDragging: Boolean) -> Unit,
) {
    /** 松手后把视口带回目标位置用（V10.6）。 */
    val scope = rememberCoroutineScope()

    val currentOnMove by rememberUpdatedState(onMove)
    val currentFinished by rememberUpdatedState(onDragFinished)

    // pointerInput(Unit) 不会随列表变化重启，用 UpdatedState 保证手势闭包读到最新列表
    val currentItems by rememberUpdatedState(items)
    val currentKey by rememberUpdatedState(key)

    /** 正在拖动条目的 key（比 index 稳：列表重排后仍能定位）。 */
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }

    /** 起拖瞬间手指在视口内的纵坐标（px）。 */
    var dragStartY by remember { mutableStateOf(0f) }

    /**
     * 起拖瞬间被拖项在视口内的 layout 顶边（px），跟手偏移的基准。
     *
     * 卡片顶边应该停在 `startItemTop + (当前手指纵坐标 - dragStartY)`。
     * 但列表**自己**也在动：hover 换位会把它搬到新槽位、边缘自动滚动会让它的
     * layout 顶边整体平移。所以真正要补的位移是「目标顶边 − 当前 layout 顶边」，
     * 少了后面这一项就会和重排叠加成双倍偏移（卡片跑过头）。
     */
    var startItemTop by remember { mutableStateOf(0f) }

    /**
     * V9 需求 7：拖动期间的**本地顺序副本**。
     *
     * 卡顿根因：旧实现在 `onDrag` 里「每跨过一行」就回调一次 `onMove`，调用方
     * （`RuleListViewModel.move`）会改 `reorderOverride` → 整个页面重组 → `buildList`
     * 重建整份列表 → 二十多张规则卡全部重组。拖一次手指要触发十几次全量重组。
     *
     * 现在改为：拖动期间只改这份本地副本（只让 LazyColumn 增量重排），**松手时
     * 一次性**把「起始下标 → 结束下标」交给调用方落库 —— 与旧语义等价
     * （单项拖拽 = 从原位置取出、插到新位置），但重组次数从 N 次降到 1 次。
     */
    val localItems = remember { mutableStateListOf<T>() }

    /**
     * 当前**挂着抬升图层**的条目 key：拖动中与松手后的回落动画期间非空。
     *
     * 为什么不是一个 `isDragging` 布尔就够了：松手瞬间若立刻摘掉图层，放大与阴影会
     * 硬切回常态；但一直挂着又会让静止态残留 outline。这里的做法是——松手后先让
     * [lift] 把放大/阴影动画回零（120ms），再延迟一帧把图层整个摘掉，
     * 既保留回落动画，又保证静止态**没有任何**图层。
     */
    var liftKey by remember { mutableStateOf<Any?>(null) }

    /**
     * V9.1：**全列表只用一个**抬升动画。
     *
     * 旧实现给每个条目各建一个 `animateFloatAsState`（一屏 20 条 = 20 个 Animatable），
     * 而且 `lift` 在组合期被读取 → 拖动期间每个条目都在重组。现在只保留一个动画，
     * 且在 `graphicsLayer{}`（绘制期）里读 —— 拖动帧不触发任何重组，只重绘被拖的那一项。
     */
    val lift by animateFloatAsState(
        targetValue = if (draggingKey != null) 1f else 0f,
        animationSpec = tween(durationMillis = LIFT_DURATION_MS),
        label = "dragLift",
    )

    // 非拖动状态下与外部列表同步；拖动期间不被外部更新打断（否则手势会跳）
    LaunchedEffect(items, draggingKey) {
        if (draggingKey == null) {
            // V9.4：clear + addAll 必须是**一次**快照写入。
            // 分两次写的话，中间那一帧 localItems 是空的 → 跟手偏移里反查不到该条目
            // （currentTop = null）→ translationY 瞬间归零 → 卡片跳一下。
            Snapshot.withMutableSnapshot {
                localItems.clear()
                localItems.addAll(items)
            }
        }
    }

    // 松手后：等回落动画走完，把抬升图层彻底摘掉（保证静止态零 outline / 零阴影）
    LaunchedEffect(draggingKey) {
        if (draggingKey != null) return@LaunchedEffect
        delay(LIFT_DURATION_MS.toLong() + LIFT_CLEANUP_GRACE_MS)
        liftKey = null
    }

    // —— 边缘自动滚动：拖动期间按帧步进 ——
    val dragging = draggingKey != null

    /**
     * 在纵坐标 [y] 处做一次「是否该换位」的判定（`onDrag` 与自动滚动**共用**）。
     *
     * V9.6 修复「拖到顶部不跟着滚、看不到能放到哪」：
     * 旧实现只在 `onDrag` 里做换位判定。而手指停在屏幕上边缘不动时，`onDrag` 不再触发
     * （没有新的触摸事件），列表却在自动滚动——**只滚不判定**，滚进来的条目永远换不过去，
     * 用户看到的就是「列表动了但卡片插不进去」。现在自动滚动每滚一步都重算一次落点。
     */
    fun tryReorderAt(y: Float) {
        val keyNow = draggingKey ?: return
        val from = localItems.indexOfFirst { currentKey(it) == keyNow }
        if (from < 0) return
        val to = indexUnder(listState, y) ?: return
        if (to == from || to !in localItems.indices) return
        // 只有越过目标条目中线才交换，避免边界处来回抖动（每次都搬移 + 重绘，卡顿放大器）
        if (crossedCenter(listState, from, to, y)) {
            localItems.add(to, localItems.removeAt(from))
            moved = true
        }
    }

    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            // 按帧推进（而不是固定 delay）：滚动与绘制同频，观感才连续；16ms 的 delay 会累积漂移
            withFrameNanos { }
            val y = dragOffset.y
            val viewportH = listState.layoutInfo.viewportSize.height.toFloat()
            if (viewportH <= 0f) continue
            val topDist = y
            val bottomDist = viewportH - y
            // 越贴边缘滚得越快（速度 20%~100% 线性插值）：
            // 贴死边缘时滚得快，刚进入热区时滚得慢，才既能快速跑到头、又不会一帧跨过目标条目
            val step = when {
                topDist < EDGE_THRESHOLD_PX -> {
                    val ratio = (1f - (topDist / EDGE_THRESHOLD_PX)).coerceIn(0.2f, 1f)
                    -SCROLL_STEP_PX * ratio
                }

                bottomDist < EDGE_THRESHOLD_PX -> {
                    val ratio = (1f - (bottomDist / EDGE_THRESHOLD_PX)).coerceIn(0.2f, 1f)
                    SCROLL_STEP_PX * ratio
                }

                else -> 0f
            }
            if (step == 0f) continue
            val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            listState.scrollBy(step)
            val after = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            // 只有真的滚动了才重算落点：滚到顶/底时 scrollBy 是空操作，
            // 此时反复判定纯属空转（还可能因坐标不变把同一格反复换位）。
            if (before != after) tryReorderAt(dragOffset.y)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    dragOffset = offset
                    moved = false
                    val keyNow = itemKeyUnder(listState, localItems, currentKey, offset.y)
                    draggingKey = keyNow
                    if (keyNow != null) {
                        // 与 draggingKey 同帧挂上图层，避免抬升动画晚一帧才起步
                        liftKey = keyNow
                        // 跟手基准：手指起点 + 该项此刻的 layout 顶边（两者同处视口坐标系，
                        // 与 indexUnder / crossedCenter 的口径一致）
                        dragStartY = offset.y
                        startItemTop = itemTopOf(listState, localItems, currentKey, keyNow)
                            ?: offset.y
                    }
                },
                onDrag = { change, _ ->
                    dragOffset = change.position
                    tryReorderAt(change.position.y)
                },
                onDragEnd = {
                    val keyNow = draggingKey
                    if (moved && keyNow != null) {
                        // 松手时一次性提交：起始下标（外部列表）→ 结束下标（本地顺序）
                        val from = currentItems.indexOfFirst { currentKey(it) == keyNow }
                        val to = localItems.indexOfFirst { currentKey(it) == keyNow }
                        if (from >= 0 && to >= 0 && from != to) {
                            currentOnMove(from, to)
                            currentFinished()
                            // V10.6：拖到列表最前时，松手后把视口带回第一条。
                            // 原来只提交顺序、不动滚动位置；而边缘自动滚动常常停在
                            // 「顶部偏下」的位置，列表重排后用户看到的是第 2 条，
                            // 必须再往上拉一次才能看到刚拖上去的那条。
                            if (to == 0) {
                                scope.launch {
                                    // 等一帧：让 LazyColumn 按新顺序完成重排，再滚到顶
                                    withFrameNanos { }
                                    listState.scrollToItem(0)
                                }
                            }
                        }
                    }
                    draggingKey = null
                },
                onDragCancel = { draggingKey = null },
            )
        },
        verticalArrangement = verticalArrangement,
    ) {
        items(items = localItems, key = { key(it) }) { item ->
            val itemKey = key(item)
            val isDragging = itemKey == draggingKey
            // 只有被抓起的那一项（含回落动画窗口）挂抬升图层；其余条目**完全没有**
            // graphicsLayer —— 静止态不产生任何 outline / 阴影轮廓 / 描边。
            val isLifted = itemKey == liftKey
            Box(
                modifier = Modifier
                    // ① 让位动画：只保留位置动画，去掉淡入淡出（拖动期间反复淡入淡出很掉帧）。
                    //    V9.4：被抓起的那一项**必须关掉**位置动画（placementSpec = null）。
                    //    跟手偏移是按「layout 槽位」做补偿的（见 ④），而 animateItem 会让
                    //    它在新旧槽位之间再插值一次——两套位移叠加，卡片会在每次换位时
                    //    反向跳一行再滑回来。关掉后被拖项严格待在槽位上，位移全部由 ④ 负责，
                    //    其余条目照旧享受平滑让位。
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = if (isLifted) null else spring(),
                        fadeOutSpec = null,
                    )
                    // ② 被抓起的项（含松手回落窗口）浮到其它项之上
                    .then(if (isLifted) Modifier.zIndex(1f) else Modifier)
                    // ③ 抬升：放大 + 抬升阴影（V9.4：条件化挂图层）
                    //    · 只有 isLifted 那一项有图层，静止项零开销、零 outline；
                    //    · `lift` 仍在 graphicsLayer 的绘制 lambda 内读取 → 只失效绘制层，
                    //      拖动每一帧都不触发重组（V9.1 的性能关键，原样保留）；
                    //    · shadowElevation / shape 只在 factor > 0 时生效，归零立刻还原
                    //      RectangleShape + 0 阴影，随后整层摘掉——不留一圈浅色轮廓。
                    .then(
                        if (isLifted) {
                            Modifier.graphicsLayer {
                                val factor = lift
                                val scaleNow = 1f + (LIFT_SCALE - 1f) * factor
                                scaleX = scaleNow
                                scaleY = scaleNow
                                // V9.6：**刻意不设 shadowElevation / shape**。
                                // 旧实现在抬起时给 10dp 阴影 + 圆角 outline，用户连续两版反馈
                                // 「拖动时卡片还是有白底」——那就是阴影在卡片边缘合成的一圈浅色
                                // 轮廓（深色主题下最像白边），以及 shape 带来的裁剪边界。
                                // 现在抬升感只靠 1.02 缩放 + zIndex 表达：零轮廓、零残留。
                                // clip 必须为 false，否则被条目边界裁掉（历史上曾因此漏掉缩放部分）。
                                clip = false

                                // ④ 跟手（V9.4）：卡片顶边 = 起拖顶边 + 手指位移。
                                //    currentTop 是该项**当前**的 layout 顶边——hover 换位
                                //    与边缘自动滚动都会改变它。用「目标顶边 − 当前 layout
                                //    顶边」作补偿，把列表自己挪走的那部分减掉，
                                //    因此既严格跟手（手指动多少卡片动多少），又不会和
                                //    重排叠加成双倍偏移。
                                //    乘 factor：抓起时随抬升淡入，松手时随回落把卡片
                                //    从手指位置送回它的槽位——复用现成的 lift，
                                //    不新增第二个动画对象。
                                //    整段仍在绘制期读取，拖动帧零重组。
                                val currentTop =
                                    itemTopOf(listState, localItems, currentKey, liftKey)
                                val targetTop = startItemTop + (dragOffset.y - dragStartY)
                                translationY =
                                    if (currentTop == null) 0f else (targetTop - currentTop) * factor
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                itemContent(item, isDragging)
            }
        }
    }
}

/**
 * 手指是否越过了目标条目的中线。
 *
 * 向上拖（to < from）时要越过目标条目的**上**半部分中线才算换位，向下反之。
 * 用中线而不是「进入目标条目范围」是因为后者在边界处会反复触发换位，
 * 每次换位都会搬移列表 + 触发一次重绘（观感是「抖」且「卡」）。
 */
private fun crossedCenter(
    listState: LazyListState,
    from: Int,
    to: Int,
    y: Float,
): Boolean {
    val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == to } ?: return true
    val center = target.offset + target.size / 2f
    return if (to > from) y > center else y < center
}

/** 反查手指纵坐标落在哪个可见条目上。 */
private fun indexUnder(listState: LazyListState, y: Float): Int? =
    listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        y.toInt() in info.offset until (info.offset + info.size)
    }?.index

/** 反查手指纵坐标落在哪个可见条目上，返回其 key。 */
private fun <T> itemKeyUnder(
    listState: LazyListState,
    items: List<T>,
    key: (T) -> Any,
    y: Float,
): Any? {
    val index = indexUnder(listState, y) ?: return null
    return items.getOrNull(index)?.let(key)
}

/**
 * 某个 key **当前**在视口内的 layout 顶边（px）；不在列表内或已滚出可见区时返回 null。
 *
 * 跟手偏移要用它做补偿：列表重排（hover 换位）与边缘自动滚动都会改变这个顶边，
 * 不减掉这部分就会和重排叠成双倍偏移。
 *
 * 用 index 而不是 `LazyListItemInfo.key` 定位：前者在所用 Compose 版本上稳定可用，
 * 且与 [indexUnder] / [crossedCenter] 的口径一致（都是 `localItems` 下标）。
 */
private fun <T> itemTopOf(
    listState: LazyListState,
    items: List<T>,
    key: (T) -> Any,
    targetKey: Any?,
): Float? {
    if (targetKey == null) return null
    val index = items.indexOfFirst { key(it) == targetKey }
    if (index < 0) return null
    return listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == index }
        ?.offset
        ?.toFloat()
}

/** 距视口边缘多少 px 内开始自动滚动。 */
private const val EDGE_THRESHOLD_PX = 90f

/** 自动滚动每帧步进 px。 */
private const val SCROLL_STEP_PX = 16f

/** 自动滚动帧间隔（ms）。 */

// ==================== V8 需求②：拖动视觉反馈参数 ====================

/** 抓起时的放大倍数（1.02：足以被感知，又不会让卡片明显变形或文字发虚）。 */
private const val LIFT_SCALE = 1.02f

/** 抓起时的抬升阴影高度（dp）；松手后随 lift 归零淡出。 */

/** 抬升阴影与描边的圆角（dp），与 M3 Card 默认圆角一致。 */
private val LIFT_CORNER_DP = 12.dp

/** 抓起 / 回落的过渡时长（ms）：短于 150ms，跟手但不突兀。 */
private const val LIFT_DURATION_MS = 120

/**
 * 回落动画结束后，摘掉抬升图层的宽限时间（ms）。
 *
 * 多留两帧：确保 [lift] 已经真正落到 0（此时 elevation = 0、shape = 矩形），
 * 再移除图层，看到的仍是「动画收干净」而不是「硬切」。
 */
private const val LIFT_CLEANUP_GRACE_MS = 32L
