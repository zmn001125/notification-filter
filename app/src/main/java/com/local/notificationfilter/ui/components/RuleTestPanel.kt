package com.local.notificationfilter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.core.model.ExtrasKeys
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MetaFieldKind
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.ruleengine.ManualSnapshotFactory
import com.local.notificationfilter.core.ruleengine.RuleTester
import com.local.notificationfilter.core.ruleengine.TestOutcome
import com.local.notificationfilter.data.repo.extrasPairs
import com.local.notificationfilter.service.ActiveNotificationsProvider
import com.local.notificationfilter.ui.navigation.NfRoutes
import kotlinx.coroutines.launch

/** 测试素材三来源（PRD 已确认）。 */
private enum class TestSource(val label: String, val desc: String) {
    SNAPSHOT("当前通知栏快照", "一键抓取当前通知栏，勾选要试跑的通知"),
    RECORD("App 记录流水", "从本 App 已记录的流水中勾选"),
    MANUAL("手填一条", "选应用 + 填六文本字段 / 元信息 / extras 键值（V2T06）"),
}

/** 一条试跑结果的行内元信息（与 [TestOutcome] 按下标对齐）。 */
private data class TestRowMeta(val recordId: Long, val canOpenDetail: Boolean)

/**
 * 规则测试面板（P6 编辑页底部主路径 + P10 批量测试共用，架构 §4.3）。
 *
 * 素材三选一：当前通知栏快照（[ActiveNotificationsProvider]）/ 流水勾选 / 手填；
 * 试跑输出命中 X/Y + 逐条命中字段高亮 + 命中规则名 + 将执行动作；未命中给原因。
 *
 * @param rulesProvider 试跑时拉取当前生效规则全集（P6 保存后调用即拿到新规则）
 * @param recordsProvider 流水素材来源（最近 N 条）
 * @param onOpenDetail 点击结果进入 P7；recordId 为 [NfRoutes.NO_RECORD_ID] 时按 key 定位
 * @param draftKeywords 当前**正在编辑的草稿**关键词（A 组，未 trim / 可含空串）。
 *   命中文字的高亮**优先**取它：编辑页里改了关键词不必先保存，高亮立刻跟着变，
 *   不会再拿上一次试跑时旧规则的关键词去高亮；为空（如 P10 批量测试页没有草稿）
 *   才回落到命中规则自带的关键词。**未命中条目一律不高亮。**
 * @param autoRunToken 数值变化时自动触发一次试跑（P6「保存并测试」用）
 * @param resultsAnchorModifier 挂在「结果块」上的附加修饰符（V8：编辑页用它取
 *   第一条结果的布局坐标，从而滚动定位过去；默认空 = 不参与定位）
 */
@Composable
fun RuleTestPanel(
    rulesProvider: suspend () -> List<Rule>,
    recordsProvider: suspend () -> List<NotificationRecord>,
    onOpenDetail: (recordId: Long, key: String) -> Unit,
    draftKeywords: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    autoRunToken: Int = 0,
    resultsAnchorModifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // V8 任务 1：默认素材来源改为「App 记录流水」（三种来源仍可自由切换）
    var source by remember { mutableStateOf(TestSource.RECORD) }
    var snapshots by remember { mutableStateOf<List<NotificationSnapshot>>(emptyList()) }
    val snapshotSelected = remember { mutableStateListOf<String>() }
    var records by remember { mutableStateOf<List<NotificationRecord>>(emptyList()) }
    val recordSelected = remember { mutableStateListOf<Long>() }
    var running by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    // —— 手填素材 ——
    var manualPkg by remember { mutableStateOf("") }
    var manualAppName by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }
    val manualFields = remember { mutableStateMapOf<MatchField, String>() }
    if (manualFields.isEmpty()) MatchField.ALL.forEach { manualFields[it] = "" }
    // V2T06：元信息（KEY 无法手填，手填素材 key 为 manual:<uuid> 自动生成）
    val manualMeta = remember { mutableStateMapOf<MetaFieldKind, String>() }
    // V2T06：extras 键值行（键名大小写敏感；键校验：非空 / 无空白）
    val manualExtras = remember { mutableStateListOf<Pair<String, String>>() }

    // —— 试跑结果 ——
    var outcomes by remember { mutableStateOf<List<TestOutcome>>(emptyList()) }
    var rowMeta by remember { mutableStateOf<List<TestRowMeta>>(emptyList()) }
    var resultRules by remember { mutableStateOf<List<Rule>>(emptyList()) }

    /**
     * V9.6：本次试跑是否真的跑过（与「结果非空」分开）。
     *
     * 旧实现用 `outcomes.isNotEmpty()` 判断要不要显示结果区，于是**跑出 0 条命中时**
     * 结果区会退回「点『试跑』查看命中统计」的初始提示——用户看到的就是
     * 「命中条数不刷新、还是上一次的样子」。现在用独立的一次性标记，
     * 跑过就一定显示（哪怕 0 条），并明确写出「0 条命中」。
     */
    var hasRun by remember { mutableStateOf(false) }

    /** V9.6：每次试跑自增，作为结果区 key —— 强制重建结果行，避免残留上一次的高亮/展开态。 */
    var runSeq by remember { mutableStateOf(0) }

    fun loadSnapshots() {
        snapshots = ActiveNotificationsProvider.snapshots()
        snapshotSelected.clear()
        snapshotSelected.addAll(snapshots.map { it.key })
        hint = when {
            !ActiveNotificationsProvider.isAttached -> "监听服务未连接（未授权或未启动），暂无法抓取通知栏"
            snapshots.isEmpty() -> "当前通知栏没有可抓取的通知"
            else -> null
        }
    }

    fun runTest() {
        scope.launch {
            running = true
            // V9.6：先清空上一次的结果再算。
            // 不清的话，计算期间（读库 + 逐条匹配）屏幕上仍挂着上一次的「命中 X / 共 Y」，
            // 用户会以为这次没刷新；清空后是「跑完才出结果」，语义明确。
            outcomes = emptyList()
            rowMeta = emptyList()
            resultRules = emptyList()
            try {
                val rules = rulesProvider()
                val materials = selectedSnapshots(
                    source, snapshots, snapshotSelected, records, recordSelected,
                    manualPkg, manualAppName, manualFields, manualMeta, manualExtras,
                )
                outcomes = RuleTester.run(rules, materials)
                resultRules = rules
                rowMeta = when (source) {
                    TestSource.RECORD -> records
                        .filter { it.recordId in recordSelected }
                        .map { TestRowMeta(it.recordId, true) }

                    else -> List(materials.size) { TestRowMeta(NfRoutes.NO_RECORD_ID, false) }
                }
                hasRun = true
                runSeq++
            } catch (t: Throwable) {
                // 读库 / 匹配抛异常时必须复位 running，否则按钮永久禁用、
                // 界面上一直挂着上一次的结果——这正是「命中条数不刷新」的一种成因。
                hint = "试跑失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                running = false
            }
        }
    }

    // V3T07：进入测试区自动拉全部流水并默认全选（去掉「加载最近流水」按钮）
    LaunchedEffect(Unit) {
        loadSnapshots()
        records = recordsProvider()
        recordSelected.clear()
        recordSelected.addAll(records.map { it.recordId })
    }

    // 「保存并测试」：外部触发一次自动试跑（先重置勾选为全选）
    LaunchedEffect(autoRunToken) {
        if (autoRunToken > 0) {
            // 面板可能刚因展开而首次组合（流水此时还没加载完），这里补一次拉取，
            // 否则「保存并测试」会拿空素材跑出 0 条结果。
            if (records.isEmpty()) records = recordsProvider()
            if (snapshots.isEmpty()) snapshots = ActiveNotificationsProvider.snapshots()
            snapshotSelected.clear()
            snapshotSelected.addAll(snapshots.map { it.key })
            recordSelected.clear()
            recordSelected.addAll(records.map { it.recordId })
            // 复用本面板唯一的试跑入口（不另写一套匹配逻辑）
            runTest()
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "选择素材所属应用",
            singleSelect = true,
            onDismiss = { showAppPicker = false },
            onConfirm = { pkgs ->
                manualPkg = pkgs.firstOrNull().orEmpty()
                showAppPicker = false
            },
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // —— ① 素材来源三选一 ——
        Text(
            text = "素材来源（三选一）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        TestSource.entries.forEach { src ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { source = src }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = source == src, onCheckedChange = { source = src })
                Spacer(Modifier.size(6.dp))
                Column {
                    Text(src.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = src.desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // —— ② 素材区 ——
        when (source) {
            TestSource.SNAPSHOT -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { loadSnapshots() }) { Text("一键抓取") }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "已抓取 ${snapshots.size} 条，勾选 ${snapshotSelected.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (snapshots.isEmpty()) {
                    Text(
                        text = hint ?: "点「一键抓取」获取当前通知栏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    ) {
                        snapshots.forEach { snap ->
                            val checked = snap.key in snapshotSelected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) snapshotSelected.remove(snap.key)
                                        else snapshotSelected.add(snap.key)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "${snap.appName} · ${snap.displayTitle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            TestSource.RECORD -> {
                // V3T07：自动拉取后直接展示；流水为空给空态
                Text(
                    text = "共 ${records.size} 条，勾选 ${recordSelected.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (records.isEmpty()) {
                    Text(
                        text = "流水还是空的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    ) {
                        records.forEach { rec ->
                            val checked = rec.recordId in recordSelected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) recordSelected.remove(rec.recordId)
                                        else recordSelected.add(rec.recordId)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "${rec.appName} · ${rec.titleSummary.ifBlank { rec.textSummary }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            TestSource.MANUAL -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showAppPicker = true }) {
                        Text(if (manualPkg.isBlank()) "选择应用" else manualAppName.ifBlank { manualPkg })
                    }
                    if (manualPkg.isNotBlank()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = manualPkg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                MatchField.ALL.forEach { field ->
                    OutlinedTextField(
                        value = manualFields[field].orEmpty(),
                        onValueChange = { manualFields[field] = it },
                        label = { Text(field.label) },
                        placeholder = {
                            Text(
                                when (field) {
                                    MatchField.TEXTLINES, MatchField.PEOPLE -> "每行一条"
                                    else -> "可留空"
                                },
                            )
                        },
                        singleLine = field != MatchField.TEXTLINES && field != MatchField.PEOPLE,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }

                // —— 元信息录入（V2T06；KEY 无法手填：手填素材 key 为自动生成）——
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "元信息（选填）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                MetaFieldKind.entries
                    .filter { it != MetaFieldKind.KEY }
                    .forEach { kind ->
                        OutlinedTextField(
                            value = manualMeta[kind].orEmpty(),
                            onValueChange = { manualMeta[kind] = it },
                            label = { Text(kind.label) },
                            placeholder = {
                                Text(
                                    when (kind) {
                                        MetaFieldKind.IS_ONGOING -> "true / false，留空为非常驻"
                                        else -> kind.hint
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        )
                    }

                // —— extras 键值行（V2T06；可增删）——
                Text(
                    text = "Extras 键值（选填）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                manualExtras.forEachIndexed { index, (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = k,
                            onValueChange = { manualExtras[index] = it to v },
                            label = { Text("键名") },
                            placeholder = { Text("如 filterbox.field.CHANNEL_ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(6.dp))
                        OutlinedTextField(
                            value = v,
                            onValueChange = { manualExtras[index] = k to it },
                            label = { Text("值") },
                            placeholder = { Text("可留空") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { manualExtras.removeAt(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "删除该行")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = { manualExtras.add("" to "") }) {
                    Text("+ 添加一行 extras 键值")
                }
                Text(
                    text = "提示：手填素材的 key 为自动生成，「通知 key」类条件在" +
                        "手填素材上不生效（可用快照 / 流水素材验证 key 匹配）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { runTest() },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "试跑中…" else "试跑")
        }

        // —— ③ 结果 ——
        if (hasRun) {
            Spacer(Modifier.height(12.dp))
            // V8：结果块整体挂 [resultsAnchorModifier]，编辑页据此滚动到「第一条结果」
            Column(modifier = resultsAnchorModifier.fillMaxWidth()) {
                val (hit, total) = RuleTester.summarize(outcomes)
                Text(
                    text = "命中 $hit / 共 $total 条",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                // V9.8：把「本次用的是哪份关键词」显示出来。
                // 用户判断结果有没有刷新的依据就是这个——旧版只有命中数字，
                // 草稿没生效时数字与上次一模一样，看着就像"没刷新"。
                if (draftKeywords.isNotEmpty()) {
                    Text(
                        text = "本次按关键词：${draftKeywords.joinToString("、")}（草稿优先）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                outcomes.forEachIndexed { index, outcome ->
                    val meta = rowMeta.getOrNull(index) ?: TestRowMeta(NfRoutes.NO_RECORD_ID, false)
                    TestResultRow(
                        outcome = outcome,
                        rules = resultRules,
                        draftKeywords = draftKeywords,
                        canOpenDetail = meta.canOpenDetail,
                        onClick = if (meta.canOpenDetail) {
                            { onOpenDetail(meta.recordId, outcome.snapshot.key) }
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "点「试跑」查看命中统计与逐条详情",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 按当前来源汇总参与试跑的快照（保持与勾选顺序一致）。 */
private fun selectedSnapshots(
    source: TestSource,
    snapshots: List<NotificationSnapshot>,
    snapshotSelected: List<String>,
    records: List<NotificationRecord>,
    recordSelected: List<Long>,
    manualPkg: String,
    manualAppName: String,
    manualFields: Map<MatchField, String>,
    manualMeta: Map<MetaFieldKind, String>,
    manualExtras: List<Pair<String, String>>,
): List<NotificationSnapshot> = when (source) {
    TestSource.SNAPSHOT -> snapshots.filter { it.key in snapshotSelected }
    TestSource.RECORD -> records.filter { it.recordId in recordSelected }.map { it.toTestSnapshot() }
    TestSource.MANUAL -> listOf(
        ManualSnapshotFactory.create(
            packageName = manualPkg,
            appName = manualAppName,
            fields = manualFields,
            extras = manualExtras.validExtrasEntries(),
            meta = manualMeta,
        ),
    )
}

/** extras 手填行过滤：键 trim 后非空且不含空白（与字段选择器校验同口径，架构 §6.5）。 */
private fun List<Pair<String, String>>.validExtrasEntries(): Map<String, String> =
    mapNotNull { (rawKey, value) ->
        val key = rawKey.trim()
        key.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }?.let { it to value }
    }.toMap()

/**
 * 流水记录 → 匹配视角快照（标题/正文取摘要列，其余字段从 extras 还原）。
 *
 * V2T06：tag 从 key 第 4 段解析（`userId|pkg|id|tag|uid` 系统稳定格式；第 4 段为
 * 字面量 `"null"` 时表示无 tag，取 null 而非字符串）；userId / channelId / isOngoing /
 * flags 取流水冗余列，保证元信息类条件在流水素材上可试跑；extras 全量还原（T03 就绪）。
 */
private fun NotificationRecord.toTestSnapshot(): NotificationSnapshot {
    val extras = extrasPairs()
    fun extra(key: String): String? = extras.firstOrNull { it.first == key }?.second
    fun lines(key: String): List<String> =
        extra(key)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    return NotificationSnapshot(
        key = key,
        notifId = notifId,
        packageName = packageName,
        appName = appName,
        userId = userId,
        channelId = channelId,
        postTime = postTime,
        isOngoing = isOngoing,
        flags = flags,
        title = titleSummary.takeIf { it.isNotBlank() },
        text = textSummary.takeIf { it.isNotBlank() },
        textLines = lines(ExtrasKeys.TEXTLINES),
        subText = extra(ExtrasKeys.SUBTEXT),
        summaryText = extra(ExtrasKeys.SUMMARYTEXT),
        people = lines(ExtrasKeys.PEOPLE),
        extras = extras,
        tag = key.split("|").getOrNull(3)?.takeIf { it != "null" },
    )
}

/** 单条试跑结果：命中字段 / 命中规则 / 将执行动作；未命中给原因。 */
@Composable
private fun TestResultRow(
    outcome: TestOutcome,
    rules: List<Rule>,
    draftKeywords: List<String>,
    canOpenDetail: Boolean,
    onClick: (() -> Unit)?,
) {
    val result = outcome.result
    val rule = result?.ruleId?.let { id -> rules.firstOrNull { it.id == id } }
    // V2：高亮关键词取 A 组（keywords）。
    // 三处修正：
    // 1) 只有**命中**才高亮——未命中条目之前会拿旧规则的关键词去高亮一段根本没命中的文字；
    // 2) 优先用**当前草稿关键词**（编辑页未保存也跟着变），拿不到才回落命中规则自带的；
    // 3) 未命中 → 关键词置空，高亮函数直接原样返回（普通文字，无任何底色）。
    val hit = result != null && result.hit
    val draft = draftKeywords.normalizedKeywords()
    val keywords: List<String> = if (!hit) {
        emptyList()
    } else if (draft.isNotEmpty()) {
        draft
    } else {
        // 无草稿关键词（批量测试页）才回落到命中规则自带的关键词
        rule?.keywords?.normalizedKeywords().orEmpty()
    }

    // 标题 + 正文：命中常发生在正文里，正文也要高亮。
    // displayTitle 在标题为空时会回落到 text，此时两者相同 → 不重复渲染正文行。
    val title = outcome.snapshot.displayTitle.trim()
    val body = outcome.snapshot.displayText.trim()
        .takeIf { it.isNotBlank() && it != title }
        .orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(
                appName = outcome.snapshot.appName,
                packageName = outcome.snapshot.packageName,
                size = 28.dp,
            )
            Spacer(Modifier.size(8.dp))
            // 标题 + 正文最多共占两行（有正文时标题收成单行），列表高度不变
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightKeywords(title, keywords),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (body.isEmpty()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (body.isNotEmpty()) {
                    Text(
                        text = highlightKeywords(body, keywords),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(6.dp))
            if (result != null && result.hit) {
                // V11：显示**全部**计划动作，不再 take(2) —— 用户要求效果显示完整
                result.plannedActions.forEach { action ->
                    ActionTag(action = action)
                    Spacer(Modifier.size(4.dp))
                }
            } else {
                ResultTag(text = "未命中", style = TagStyle.OUTLINE)
            }
        }
        Spacer(Modifier.height(3.dp))
        if (result != null && result.hit) {
            val orderIndex = rule?.let { r ->
                rules.sortedWith(compareBy({ it.order }, { it.id }))
                    .indexOfFirst { it.id == r.id } + 1
            }
            Text(
                text = buildString {
                    append("命中字段：${result.hitFieldsLabel().ifEmpty { "—" }}")
                    append(" ｜ 命中规则：${result.ruleName}")
                    if (orderIndex != null) append("（第 $orderIndex 条）")
                    if (result.plannedActions.isNotEmpty()) {
                        append(" ｜ 将执行：")
                        append(result.plannedActions.joinToString(" + ") { it.label })
                    }
                    if (canOpenDetail) append("\n点此查看完整字段")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "条件不满足：${result?.reason.orEmpty().ifBlank { "未命中当前规则" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 关键词去空白、去空串、去重（高亮 / 匹配的统一口径）。 */
private fun List<String>?.normalizedKeywords(): List<String> =
    this.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct()

/**
 * 命中关键词高亮（对齐线框稿 `<em class="hit">`）。
 *
 * 三条规则：
 * - **忽略大小写**（`indexOf(..., ignoreCase = true)`），与引擎的关键词匹配口径一致；
 * - **找出全部出现位置**：同一个关键词在一行里出现多次时，每处都高亮
 *   （旧实现只取 `indexOf` 的第一个位置，后面的漏掉）；
 * - **合并重叠区间**：多个关键词的命中范围可能交叠，先按起点排序合并，
 *   渲染时再跳过与已高亮区间重叠的部分，避免 `start < cursor` 造成乱序 append。
 *
 * 样式：底色 `primaryContainer` + 文字 `onPrimaryContainer` + 加粗，
 * 三重强调，保证一行小字里也能一眼看出命中了哪几个字。
 */
@Composable
private fun highlightKeywords(text: String, keywords: List<String>): AnnotatedString {
    if (keywords.isEmpty() || text.isBlank()) return AnnotatedString(text)

    val ranges = ArrayList<Pair<Int, Int>>()
    keywords.forEach { raw ->
        val kw = raw.trim()
        if (kw.isEmpty()) return@forEach
        // 起点每次 +1 后移：既能覆盖相邻重复出现（"aa" 中的两个 "a"），
        // 也能覆盖关键词自身重叠（"aaa" 中匹配 "aa" 的两处）
        var from = text.indexOf(kw, 0, ignoreCase = true)
        while (from >= 0) {
            ranges += from to (from + kw.length)
            from = text.indexOf(kw, from + 1, ignoreCase = true)
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)

    val hlBackground = MaterialTheme.colorScheme.primaryContainer
    val hlForeground = MaterialTheme.colorScheme.onPrimaryContainer

    return buildAnnotatedString {
        var cursor = 0
        mergeRanges(ranges).forEach { (start, endExclusive) ->
            val start2 = start.coerceIn(0, text.length)
            val end2 = endExclusive.coerceIn(start2, text.length)
            if (start2 < cursor || end2 <= start2) return@forEach // 与已高亮区间重叠 → 跳过
            if (start2 > cursor) append(text.substring(cursor, start2))
            withStyle(
                SpanStyle(
                    background = hlBackground,
                    color = hlForeground,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(text.substring(start2, end2))
            }
            cursor = end2
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/**
 * 合并重叠 / 相邻的命中区间（闭开区间 `[first, second)`），按起点升序。
 *
 * 例：[0,2) + [1,5) + [7,9) → [0,5) + [7,9)。
 */
private fun mergeRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    if (ranges.isEmpty()) return emptyList()
    val sorted = ranges.sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
    val merged = ArrayList<Pair<Int, Int>>(sorted.size)
    var current = sorted.first()
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        current = if (next.first <= current.second) {
            current.first to maxOf(current.second, next.second)
        } else {
            merged.add(current)
            next
        }
    }
    merged.add(current)
    return merged
}
