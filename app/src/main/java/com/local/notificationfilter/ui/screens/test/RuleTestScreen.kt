package com.local.notificationfilter.ui.screens.test

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.ui.components.RuleTestPanel

/**
 * P10 规则批量测试（从 P5「批量测试」入口进入）：对已有规则批量试跑，
 * 素材三来源（快照 / 流水 / 手填），结果可进 P7。
 *
 * 日常新建 / 修改规则的主路径是 P6 编辑页内嵌测试（「保存并测试」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTestScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val vm: RuleTestViewModel = viewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("规则测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                // V9 间距：顶栏与第一行说明文字之间留 12dp
                .padding(top = 12.dp),
        ) {
            Text(
                text = "本页为批量测试入口：对已有规则批量试跑（快照 / 流水 / 手填素材）。" +
                    "日常新建 / 修改规则的主路径是编辑页内嵌测试——「保存并测试」后编辑页底部测试区自动试跑。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 顶部间距已由内容容器统一提供，这里只留下方 8dp，避免叠加
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            RuleTestPanel(
                rulesProvider = vm::loadAllRules,
                recordsProvider = vm::loadRecentRecords,
                onOpenDetail = { recordId, _ -> onOpenDetail(recordId) },
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
