package com.local.notificationfilter.ui.screens.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.ui.components.CollapsibleSection

/**
 * App 内「使用说明」（V10）。
 *
 * 为什么放进 App 而不是只写文档：用户是在手机上配规则，边看边配才用得上；
 * 翻 Markdown 文档的摩擦太大，等于没有说明。
 *
 * 排版约定：一节一个可折叠标题，默认收起 —— 一屏能看完全部章节名，
 * 想深入哪节再点开，不会一进来就被几千字淹没。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用说明", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("") }

            items().forEach { entry ->
                item(key = entry.title) {
                    CollapsibleSection(
                        title = entry.title,
                        defaultExpanded = false,
                        titleColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = entry.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Text("") }
        }
    }
}

/** 一条说明。 */
private data class HelpEntry(val title: String, val body: String)

private fun items(): List<HelpEntry> = listOf(
    HelpEntry(
        title = "1. 快速上手（三步）",
        body = """
① 按提示授予「通知使用权」（必须）——没有它本 App 收不到任何通知。
② 首页打开总开关，确认显示「过滤中」。
③ 规则页点「新建规则」：填关键词 → 选动作 → 保存。

想先看效果：规则编辑页底部有「测试」，用通知栏现有通知或历史流水当素材，
能看到「命中 X / 共 Y 条」和每条为什么命中 / 为什么不命中。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "2. 规则怎么配",
        body = """
一条规则 = 匹配条件 + 作用范围 + 动作。

【匹配条件】
· 先选「匹配字段」：标题 / 正文 / 消息列表 / 副标题 / 摘要 / 发送者，
  还能加「元信息」（通知 key、tag、渠道 ID 等）和任意 extras 字段。
· 正在播放的音乐：字段面板里可以直接选 media.title / media.artist / media.album /
  media.package 等（键名都以 media. 开头）。这些值来自当前活跃的 MediaSession，
  面板上每个字段后面会显示**现在的实时值**，抓得到再勾。
  采集是「按需」的：只有你保存过引用 media.* 的启用规则时才启动，平时零开销。
· 再选匹配模式：
  - 全部内容：不看关键词，命中所有通知
  - 包含任一 / 包含全部：关键词任一命中 / 全部命中
  - 不包含任一 / 不包含全部：反向排除
  - 包含 A 且不含 B：最常见的「要看、但要排掉」用法
  - 正则：写正则表达式，适合复杂格式
  - 分组匹配：关键词分多组，每组都要满足（组间「且」）；组内可选「任一出现 / 全部出现」。
    例：必须出现 true 且 salt、nest 任一出现 → 第 1 组填 true，第 2 组填 salt、nest 选「任一出现」
· 多个关键词用换行或逗号分隔。

【作用范围】
· 全局
· 仅指定 App
· 全局但排除指定 App

【分组（归类标签）】一条规则可打多个分组标签，列表顶部 tab 点一下只看该分组。
分组只是归类，完全不影响匹配判定。编辑页「分组」区块可新建或直接点已有分组加入。
排序是全局优先级：只有「全部」tab 下才能拖动调顺序。

【优先级】规则按列表顺序自上而下，先命中先处理。拖动可调整顺序。
黑名单优先级最高，会无条件消除对应 App 的通知。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "3. 六种动作",
        body = """
· 消除：直接从通知栏撤掉。
· 推迟：撤掉后，到设定的时刻重新发回来（可设多个时刻）。
· 替换：用你自己写的标题/正文重新发一条（可保留图标、大图、渠道名）。
· 朗读：把通知内容读出来（TTS）。
· 弹幕：在屏幕上飘一条弹幕，不撤原通知。
· 悬浮：在屏幕指定位置显示一张卡片，不撤原通知。

弹幕 / 悬浮 / 替换 的文案都能用占位符自由拼装。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "4. 占位符（模板里能写什么）",
        body = """
在规则编辑页的「占位符速查」里点一下即可复制。

· {title} {text} {app} —— 原标题 / 原正文 / 应用名
· {textLines} {subText} {summaryText} {people} —— 其他文本字段
· {key} {id} {tag} {pkg} {channelId} {time} —— 通知元信息
· {extras.键名} —— 任意 extras 字段（键名大小写敏感，没有该键出空串）

两组动态 token（都要先在「内容提取」里配好提取器）：
· {re1}~{re9} = 只从「当前这一条」通知里抓，实时、不累积
· {items1}~{items9} = 把「多条」通知的抓取结果攒成列表

模板整体渲染后会去掉首尾空白；没识别的 {xxx} 按空串处理。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "5. 内容提取（用正则抓内容）",
        body = """
用途：从通知里抓出你关心的片段（取件码、验证码、运单号…），再拼进弹幕/悬浮/替换文案。

四步：
① 打开「⑤ 内容提取与汇总」的总开关
② 点「添加提取器」——列表号就是占位符里的 N（第 1 个 → {re1}）
③ 勾选要匹配的字段，填正则（要取的部分用括号），捕获格式填 ${'$'}1；勾「启用」，条数上限 > 0
④ 回模板里写 {re1} 或 {items1}

正则示例（取 6 位取件码）：
  正则：取件码[：:为是]?\s*(\d{6})
  捕获格式：${'$'}1     （${'$'}0 = 整段匹配，${'$'}1 = 第 1 个括号里的内容）

⚠ 启用 + 正则非空 + 上限 > 0，三者缺一，「{re1}」就会被判成未识别占位符。

【当场验证】配好后点「用通知栏第一条通知试提取」，
它会直接显示每个 {reN} 从真实通知里抓到了什么；没抓到会提示检查中文冒号/空格/位数。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "6. 弹幕与悬浮",
        body = """
【弹幕】
· 贯穿式：从右往左滚动，多条占顶部轨道。条数不再受"同屏最多"限制——
  只要屏幕上还排得下就继续显示，排满了才排队。
· 定位式：按九宫格位置出现，停留后消失，多条依次堆叠。
· 样式：颜色/渐变/圆角/内边距/字号/透明度/出现与消失动画（各自可设时长）。
· 预览：样式设置页顶部有实时预览；「发射」可让预览里同时出现多条，
  「真机发射」按当前样式真的往屏幕上打一条。

【悬浮】
· 位置用九宫格 + 偏移量精调；可以覆盖到状态栏区域。
· 支持常驻（停留 0 秒）——不会自动消失，需在首页菜单「清除屏幕显示」撤下。
· 打开样式页即把编辑中的样式投到屏幕真实位置，所见即所得。

【条件显示】「悬浮显示条件」里可设：某 App 有通知、屏幕亮/灭、充电中、
时段、前台是某个 App 等，满足才显示、不满足自动消失。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "7. 需要的权限",
        body = """
· 通知使用权（必须）：核心权限，没有它什么都做不了。
· 悬浮窗权限（弹幕/悬浮必须）：否则窗口无法显示。
· 无障碍服务（可选）：只有「按前台 App 显示悬浮」这类条件才需要。
· 电池优化白名单（建议）：避免后台被系统杀掉。
· 后台弹出界面 / 后台运行（小米机型建议开启）：否则弹幕可能被拦。

以上都能在首页的权限引导里逐项检查与跳转。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "8. 备份与恢复",
        body = """
入口：首页右上角菜单 →「备份与恢复」。

【备份什么】
规则 + 黑名单 + 应用设置（总开关 / 主题 / 深浅模式 / 常驻通知 / 多任务中隐藏）。

【本地备份】
· 「选择文件夹」→ 授权一次，之后一直有效（系统重启也不丢）。
· 「立即备份」→ 在该文件夹里生成 nf-backup-时间戳.json，保留多份，不会被自动删。
· 自动备份写的是固定名 nf-backup-auto.json，每次覆盖，不会堆一堆文件。

【坚果云 WebDAV】
· 服务器地址默认 https://dav.jianguoyun.com/dav/ 。
· 账号 = 坚果云登录邮箱；密码 = **应用专用密码**（网页版 → 账户信息 →
  安全选项 → 添加应用密码）。填登录密码一定认证失败。
· 「远程目录」默认 notification-filter/，只决定放在哪个目录，**文件名按当天
  时间自动生成**（如 nf-backup-20261018-1605.json），可以保留多份、挑任意一份恢复；
  目录不存在时会自动创建。
· 「测试连接」验证账号密码 + 写权限，并顺带把远程目录建好（已存在则跳过），
  不会上传或删除任何文件。
· 每个备份文件右侧除了「恢复」还有一个删除图标，删本地/云端文件都需要二次确认。

【自动备份】
· 开关 + 间隔（每天 / 每 3 天 / 每周）+ 时间点（默认 03:00）+ 目标（本地 / WebDAV / 两者）。
· 云端自动备份同样按时间命名保留多份：只保留最近 30 份，更早的自动删除；
  你手动备份的文件和恢复前快照永远不会被自动删。
· 系统可能延迟几分钟触发，属正常（省电策略，不是 bug）。
· 关机重启后会自动重排。

【恢复】
· 两种入口：本地文件列表、云端文件列表，点右侧「恢复」。
· 先弹预览：多少条规则、多少黑名单、哪些设置——确认后才真正写入。
· 规则一律**追加**到现有规则之后，**不会删除你现在已有的规则**；
  黑名单与设置按备份**覆盖**。
· 「只恢复规则」= 只追加规则，黑名单与设置一律不动。
· 恢复前会自动在备份位置存一份 nf-before-restore-时间戳.json（只保留最近 5 份），
  导错了可以用它退回来。

【说明】
· 这是本 App 唯一会联网的功能；不改 WebDAV、不开启它，就完全不会发起任何网络请求。
· WebDAV 密码用系统密钥库（Android Keystore）加密后保存，不是明文。
        """.trimIndent(),
    ),
    HelpEntry(
        title = "9. 常见问题",
        body = """
Q：弹幕不出现？
A：先确认悬浮窗权限；再看规则是否命中（用「测试」验证）；最后检查样式里的透明度是否为 0。

Q：改了关键词，测试结果没变？
A：测试面板只统计「当前这一条规则」，且用的是眼前这份草稿（不用先保存）。
   结果上方会显示「本次按关键词：xxx」，如果这里没跟着变，说明草稿没构造成功。

Q：{re1} 提示未识别？
A：去「⑤ 内容提取」确认：总开关打开、提取器勾了启用、正则非空、条数上限 > 0。

Q：{re1} 渲染出来是空的？
A：点「用通知栏第一条通知试提取」，看正则到底抓到没有。
   最常见原因是中文冒号「：」和英文冒号「:」不一致，或数字位数不对。

Q：通知被消除了但想找回来？
A：记录页有完整流水，点进详情可以「恢复通知」。

Q：记录页里一条通知占了一整屏？
A：不会了（V10.11 起）。下载 / 备份 / 音乐 / 导航这类通知会原地刷新进度，
   以前每次刷新都记一行，一条通知就能把整页刷满。现在**同一条通知在流水里只占一行**，
   内容随更新刷新、位置不动（不会每 1.2 秒跳到顶部）；等这条通知从通知栏里消失后，
   下一次再来才是新的一行。统计口径同步改为「一条通知计一次」，不再按刷新次数累加。

Q：两套规则都命中同一条通知？
A：只执行列表里最靠前的那一条。用拖动调整顺序即可。

Q：记录里一条通知为什么显示好几个效果标签？
A：因为这条规则同时配了多个动作（比如「消除 + 朗读」）。V11 起记录页会把
   一条规则执行过的**所有**动作都列出来（列表行最多 2 个，多的显示「+N」，
   进详情看全量），筛选也能按其中任意一个筛出来。
   带错误色（红）的标签表示那个动作没做成：朗读跳过 / 替换失败 / 屏幕显示跳过。

Q：推迟的通知到点了没回来？
A：检查是否授予了精确闹钟权限；小米机型还需允许自启动。

Q：坚果云连接失败？
A：按提示对号入座，改完先点「测试连接」再上传：
   ①「认证失败」→ 密码填的大概率是登录密码。坚果云只认**应用专用密码**
      （网页版 → 账户信息 → 安全选项 → 添加应用密码），登录密码一定被拒；
   ②「无法创建远程目录」→ 去坚果云网页版把「远程目录」里写的文件夹先建好，
      再回来测试（个别账号 MKCOL 建目录会被服务端挡下）；
   ③「无法连接服务器」→ 核对服务器地址是不是 https://dav.jianguoyun.com/dav/ ，
      并确认手机能上网（这是本 App 唯一需要联网的功能）。
        """.trimIndent(),
    ),
)
