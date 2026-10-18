# 通知过滤 App（NotificationFilter）

一个**单人自用**的 Android 通知过滤工具：用关键词 / 正则规则拦截、推迟、改写、朗读通知，
并可以把通知渲染成屏幕上的弹幕或悬浮卡片。

> 小米 15 · 澎湃 OS3（Android 15）上开发与验证 · 侧载 APK · **纯本地运行、不联网、无账号**
> 当前版本：**11.2**（`versionCode 12`）｜ `minSdk 26` / `compileSdk 34`

**📥 下载安装包**：[Releases](https://github.com/zmn001125/notification-filter/releases) → 下载 `notification-filter-v11.2-release.apk` 传到手机点击安装。
（也可以按下面的「构建」一节自己编译。）

---

## 为什么造这个轮子

系统自带的「通知管理」只能按 App 整包静音，做不到这些：

- 按**内容**过滤：「快递柜取件码」留着，其它电商促销全消掉；
- 按内容**反向排除**：某个群的消息要看，但含「接龙」两个字的不要；
- 消除之后还想**找回来**：推迟到晚上再看，或者改写成一句话重新发一条；
- 把通知变成**弹幕 / 悬浮卡片**，不占通知栏；
- 用**正则**从通知里抓内容（取件码 / 验证码 / 运单号），再拼进模板。

于是有了这个 App。它只服务于作者本人，所以很多设计取舍偏向「语义简单可预测」而不是「功能大而全」。

---

## 功能特性

### 规则引擎

- **7 种匹配模式**：全部内容 / 包含任一 / 包含全部 / 不包含任一 / 不包含全部 / 包含 A 且不含 B / 正则
- **匹配字段**：标题、正文、消息列表、副标题、摘要、发送者 + 通知元信息（key / tag / 渠道 ID…）
  + **任意 extras 字段** + `media.*` 媒体字段（来自活跃的 MediaSession）
- **作用范围**：全局 / 仅指定 App / 全局但排除指定 App
- **优先级**：列表顺序自上而下，先命中先处理；黑名单无条件优先
- **设备状态条件**：屏幕亮灭、充电状态、时段与星期
- 拖动排序、复制规则、导入导出（剪贴板 / 文件）

### 六种动作

| 动作 | 说明 |
| --- | --- |
| 消除 | 从通知栏撤掉（可选「静音消除」= 先静音该渠道再撤） |
| 推迟 | 撤掉后到点重新发回，可设多个时刻，重启后自动重排 |
| 替换 | 用自定义标题 / 正文重发，复用原 `contentIntent`，可保留图标与渠道名 |
| 朗读 | TTS 朗读（引擎惰性绑定，空闲自动释放） |
| 弹幕 | 贯穿式 / 定位式，九宫格定位、渐变、动画时长可调 |
| 悬浮 | 指定位置卡片，支持常驻与六类条件显示 |

一条规则可同时配多个动作，流水里会把执行过的动作**全部**列出来（失败留痕用红色标签）。

### 内容提取

正则 + 捕获格式从通知里抽内容，两组占位符：

- `{re1}~{re9}`：只从**当前这条**通知即时提取；
- `{items1}~{items9}`：跨通知**累积**成列表。

### 备份与恢复

- **本地文件夹**（SAF 持久授权）与 **WebDAV**（默认坚果云，也支持 Nextcloud / 群晖）
- 手动 + 定时自动备份；云端按时间命名保留多份（自动备份保留最近 30 份）
- 恢复是两步式：先预览，再确认；规则**永远追加**，不会删掉你现有的规则
- 恢复前自动存一份快照；本地与云端备份文件都可单独删除

### 其它

- 通知流水（同一条存活通知只占一行，不会刷屏）、按天统计、全量搜索
- 六套主题 + 深浅模式，主色派生的强调色体系
- App 内「使用说明」（9 节）—— 功能怎么用直接写在 App 里，不用翻文档
- 零第三方网络 / 图片 / DI / 统计库

---

## 技术栈

| 项 | 版本 |
| --- | --- |
| Kotlin | 2.1.0（KSP 2.1.0-1.0.29） |
| AGP / Gradle | 8.7.3 / 8.9 |
| JDK | 17 |
| Compose | BOM 2024.12.01（Material3 + material-icons-extended） |
| Room | 2.6.1（KSP） |
| DataStore Preferences | 1.1.1 |
| Navigation Compose | 2.8.5 |
| kotlinx-serialization | 1.7.3 |
| minSdk / compileSdk / targetSdk | 26 / 34 / 34 |

架构：**单 Activity + MVVM + 手工 DI**（`AppContainer` 里全是懒加载单例），不引入 Hilt。
纯逻辑（规则引擎、内容提取、占位符、备份编解码、调度计算）**不依赖 Android**，全部可 JVM 单测。

---

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk

./gradlew testDebugUnitTest   # 426 例单测
./gradlew assembleRelease     # 产物在 app/build/outputs/apk/release/
```

Windows 下可以直接双击 `build-apk.bat`（构建 → 复制到 `outputs/` → 可选 `adb install`）。

### 签名

`keystore/` 与 `local.properties` 都已在 `.gitignore` 里，仓库中**不含任何密钥或口令**
（`app/build.gradle.kts` 也从 `local.properties` / 环境变量读取口令，不写死）。

想自己出包：

1. `keytool` 生成一个 jks 放到 `keystore/nf-release.jks`（命令见 `docs/需求文档.md` §7）；
2. 在 `local.properties` 里写三行：

```properties
nf.store.pass=你的密钥库口令
nf.key.alias=你的别名
nf.key.pass=你的密钥口令
```

（也可以改用环境变量 `NF_KEYSTORE_PASS` / `NF_KEY_ALIAS` / `NF_KEY_PASS`。）
密钥库缺失时 release 构建会自动回退到 debug 签名，照样能装，只是指纹不同。

**升级安装要求同一签名**，换签名必须先卸载。

### 两个坑

1. **路径含中文**：`gradle.properties` 里的 `android.overridePathCheck=true` 不能删。
2. **不要**在 `~/.gradle/gradle.properties` 里加 `-Dfile.encoding=UTF-8`，
   会让单测抛 `ClassNotFoundException`。

---

## 项目结构

```text
app/src/main/java/com/local/notificationfilter/
├── actions/     # 动作执行、替换重发、TTS、聚合记录、闹钟调度
├── core/        # 纯逻辑：ruleengine / aggregate(提取) / model / transfer / backup / media
├── data/        # Room、DataStore、Repository、规则 JSON 迁移
├── service/     # NotificationListenerService、管线、覆盖层窗口、广播接收器
├── ui/          # Compose 页面与组件、主题、覆盖层渲染
└── util/        # 包名/应用名、格式扩展、日志、权限检查
```

详细模块职责、数据模型、关键设计决策见 [`docs/需求文档.md`](docs/需求文档.md)。

---

## 权限与隐私

| 权限 | 用途 |
| --- | --- |
| 通知使用权 | 核心：读取通知 |
| 悬浮窗 | 弹幕 / 悬浮卡片 |
| 前台服务 + 通知权限 | 常驻状态通知与重发 |
| 精确闹钟 / 开机自启 | 推迟到点、自动备份重排 |
| 电池优化白名单 | 后台存活 |
| `QUERY_ALL_PACKAGES` | 应用选择器要能列出全部已安装应用 |
| `INTERNET` | **仅**用户主动配置的 WebDAV 备份 |

- 无账号、无埋点、无崩溃上报、无广告 SDK；
- 不配置 WebDAV 就**完全不会发起任何网络请求**；
- WebDAV 密码用 Android Keystore（AES/GCM）加密后存本地，不是明文；
- 数据全在本机，卸载即清空（备份文件不受影响）。

---

## 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/使用说明.md`](docs/使用说明.md) | 面向使用者的完整手册（安装、配规则、占位符、备份、常见问题） |
| [`docs/需求文档.md`](docs/需求文档.md) | 面向开发者的可复刻需求规格（模块、数据模型、设计红线、构建、验收） |

---

## 已知限制

- 澎湃 OS 的**焦点通知 / 超级岛**需要小米公司主体授权，个人侧载拿不到，故未实现；
- 覆盖层依赖悬浮窗权限，未授权时动作会记「屏幕显示跳过」（红色标签），不会静默丢弃；
- 进程被系统回收后覆盖层窗口会消失，不做常驻守护；
- 「静音消除」只能作用于整个通知渠道（`onNotificationPosted` 回调时声音已发出）；
- 自动备份由系统调度，可能延迟几分钟。

---

## License

本项目采用 **GNU General Public License v3.0（GPL-3.0）**，全文见 [`LICENSE`](LICENSE)。

一句话概括：**你可以自由使用、修改、分发，但只要把改过的版本分发出去，就必须同样以 GPL-3.0 开源全部源代码。**

具体说：

- ✅ 可以：自己用、自己改、编译出 APK 自己装、学习它的实现、fork 后继续开发；
- ⚠️ **必须开源**：一旦你**分发**了衍生作品——发布改过的 APK、把代码传到别的仓库、把它并进另一个
  项目发布——就必须①以 GPL-3.0 公开全部源代码，②保留原作者版权声明与许可声明，③说明你改了什么；
- ✅ 不用开源的情况：只在自己手机上编译自用、不向任何人分发（GPL 约束的是「分发」行为，不是「修改」行为）；
- ❌ 不提供任何担保：这是个人自用项目，按「现状」提供，作者不对任何数据丢失或功能故障负责。

源文件没有逐个加 SPDX 头，`LICENSE` 覆盖仓库内全部源代码与文档。
如果你的使用场景与 GPL-3.0 不兼容（例如要并入闭源产品），请先联系作者另行授权。
