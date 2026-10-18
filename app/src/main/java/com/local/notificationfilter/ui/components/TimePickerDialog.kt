package com.local.notificationfilter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 共用时间选择对话框（V8 任务 4）：**表盘 + 表盘上方的 HH:mm 输入框**，两者双向同步。
 *
 * 布局（自上而下，全部在对话框内部）：
 * 1. 标题；
 * 2. `HH:mm` 输入框（[KeyboardType.Number] 数字键盘，最多 5 个字符）；
 * 3. Material3 [TimePicker] 时钟表盘；
 * 4. 取消 / 确定。
 *
 * 输入规则（[normalizeTimeInput]）：
 * - 只保留数字，另允许用户自己敲一个冒号；
 * - **自动补冒号**的约定：纯数字输入到第 3 位时，把冒号插入到**最后两位之前**——
 *   输 `930` 得到 `9:30`，输 `0930` 得到 `09:30`，输 `23` + `59` 得到 `23:59`；
 * - 1~2 位纯数字按「时」解析（分钟补 0）：输 `9` 得到 `09:00`（失焦回填）；
 * - 总长度上限 5（`HH:mm`）。
 *
 * 双向同步与非法输入：
 * - 表盘 → 输入框：表盘拖动后由 [LaunchedEffect] 回填（**正在输入时不回写**，
 *   避免把用户刚敲的字符冲掉）；
 * - 输入框 → 表盘：只有解析成功才写 [rememberTimePickerState]，
 *   非法输入（`25:70` / `:` / 空串）**不写值**，只把输入框标红；
 * - 失焦时回填表盘的当前合法值（非法输入随之被纠正）。
 *
 * @param title 对话框标题（如「选择时刻」）
 * @param initialHour 初始小时（0..23，越界自动收敛）
 * @param initialMinute 初始分钟（0..59，越界自动收敛）
 * @param onDismiss 取消 / 点击外部
 * @param onConfirm 确定：回传**当前表盘**的小时与分钟（已与输入框同步）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val safeHour = initialHour.coerceIn(0, 23)
    val safeMinute = initialMinute.coerceIn(0, 59)
    // 表盘状态：唯一「真值」来源；输入框只是它的可编辑视图
    val timeState = rememberTimePickerState(
        initialHour = safeHour,
        initialMinute = safeMinute,
        is24Hour = true,
    )

    var text by remember { mutableStateOf(formatHhmm(safeHour, safeMinute)) }
    /** 输入框是否正持有焦点（输入中 → 表盘变化不回写文本）。 */
    var inputFocused by remember { mutableStateOf(false) }
    /** 当前文本是否为非法输入（标红）。 */
    var invalid by remember { mutableStateOf(false) }

    // 表盘 → 输入框（输入中跳过；失焦 / 拖动表盘都会走到这里回填）
    LaunchedEffect(timeState.hour, timeState.minute, inputFocused) {
        if (inputFocused) return@LaunchedEffect
        text = formatHhmm(timeState.hour, timeState.minute)
        invalid = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // —— 输入框：放在表盘**上方**，位于对话框内部 ——
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        val normalized = normalizeTimeInput(raw)
                        text = normalized
                        val parsed = parseHhmm(normalized)
                        // 非法输入不写值，只标红；合法才推动表盘
                        invalid = parsed == null
                        if (parsed != null) {
                            timeState.hour = parsed.first
                            timeState.minute = parsed.second
                        }
                    },
                    label = { Text("时刻（HH:mm）") },
                    placeholder = { Text("如 09:30") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        Text(
                            text = if (invalid) {
                                "时刻不合法：小时 0-23，分钟 0-59"
                            } else {
                                "可直接输入数字，会自动补冒号"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (invalid) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                inputFocused = true
                            } else {
                                inputFocused = false
                                // 失焦回填合法值（非法输入在此被纠正）
                                text = formatHhmm(timeState.hour, timeState.minute)
                                invalid = false
                            }
                        },
                )
                Spacer(Modifier.height(8.dp))
                // —— 表盘 ——
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * `HH:mm` 字符串版本的重载：给已有按字符串进出的调用点用（避免各处各写一份解析）。
 *
 * @param initial `HH:mm` 原文；解析失败（空 / 格式不对）按 09:00 兜底
 * @param onConfirm 回传新值（`HH:mm`，小时分钟各补足两位）
 */
@Composable
fun TimePickerDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = remember(initial) {
        val parts = initial.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
    }
    TimePickerDialog(
        title = title,
        initialHour = parsed.first,
        initialMinute = parsed.second,
        onDismiss = onDismiss,
        onConfirm = { hour, minute -> onConfirm(formatHhmm(hour, minute)) },
    )
}

/** `HH:mm`（各补足两位）。 */
internal fun formatHhmm(hour: Int, minute: Int): String =
    hour.coerceIn(0, 23).toString().padStart(2, '0') +
        ":" +
        minute.coerceIn(0, 59).toString().padStart(2, '0')

/** 输入框规范化：只留数字与一个冒号，超长截断，按约定自动补冒号。 */
internal fun normalizeTimeInput(raw: String): String {
    // 用户显式敲了冒号：尊重分段（最多两段，各 2 位）
    if (raw.contains(':')) {
        val parts = raw.split(':')
        val hour = parts.getOrNull(0).orEmpty().filter { it.isDigit() }.take(2)
        if (parts.size < 2) return hour
        val minute = parts.getOrNull(1).orEmpty().filter { it.isDigit() }.take(2)
        return "$hour:$minute"
    }
    val digits = raw.filter { it.isDigit() }.take(4)
    return when {
        digits.isEmpty() -> ""
        // 1~2 位：当作「时」，冒号等用户继续输或失焦时补
        digits.length <= 2 -> digits
        // 3~4 位：冒号插在最后两位之前（930 → 9:30，0930 → 09:30）
        else -> digits.dropLast(2) + ":" + digits.takeLast(2)
    }
}

/** 解析 `H:mm` / `HH:mm` / 纯数字；非法（越界、含其它字符、空）返回 null。 */
internal fun parseHhmm(text: String): Pair<Int, Int>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains(':')) {
        val parts = trimmed.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }
    if (trimmed.any { !it.isDigit() }) return null
    val digits = trimmed
    return when {
        digits.length <= 2 -> digits.toIntOrNull()?.takeIf { it in 0..23 }?.let { it to 0 }
        else -> {
            val hour = digits.dropLast(2).toIntOrNull() ?: return null
            val minute = digits.takeLast(2).toIntOrNull() ?: return null
            if (hour in 0..23 && minute in 0..59) hour to minute else null
        }
    }
}
