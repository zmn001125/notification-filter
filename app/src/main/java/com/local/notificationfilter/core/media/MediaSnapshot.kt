package com.local.notificationfilter.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次媒体会话的快照（V10.8）。
 *
 * 为什么字段这么多：用户要求「媒体会话的所有字段都能用来编辑规则」。
 * 不同播放器往不同字段塞的东西差别很大——有的把歌名放 `title`、把进度文案放 `displayTitle`，
 * 有的反过来；还有大量自定义信息塞进 metadata extras 的。
 * 全部摊开存、全部允许匹配，才不会出现「明明有值但选不到」。
 */
data class MediaSnapshot(
    val packageName: String,
    /** METADATA_KEY_TITLE —— 内容由播放器自行决定（多数是曲目标题，也可能是进度/自定义文案）。 */
    val title: String? = null,
    /** METADATA_KEY_DISPLAY_TITLE —— 通常是**给用户看**的标题（可能与 TITLE 不同）。 */
    val displayTitle: String? = null,
    val displaySubtitle: String? = null,
    val displayDescription: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val writer: String? = null,
    val year: String? = null,
    val durationMs: Long? = null,
    val playing: Boolean = false,
    /** metadata 里其余所有**可转成字符串**的键（键名原样，如 `android.media.metadata.X`）。 */
    val extras: List<Pair<String, String>> = emptyList(),
    val updatedAtMs: Long = 0L,
)

/**
 * 进程内「当前各媒体会话」的唯一事实来源（V10.8）。
 *
 * 与旧版仅存单行文本的方案不同，这里存**整份 metadata**，
 * 目的是让规则能对着任意媒体字段做匹配。
 *
 * 红线：
 * 1. **不持有 Context、不做 IO**，纯内存 + StateFlow，可以被放在通知热路径上读；
 * 2. **按需启用**：没有任何规则用到 `media.*` 字段时，[MediaSessionSource] 根本不会挂载，
 *    本 Holder 会一直是空 Map（零开销）；
 * 3. 单进程假设：Manifest 里没有 `android:process`，UI 与服务同进程，可直接 collect。
 */
object MediaSessionHolder {

    private val _byPackage = MutableStateFlow<Map<String, MediaSnapshot>>(emptyMap())

    /** 各 App 的媒体快照（键 = 包名）。 */
    val byPackage: StateFlow<Map<String, MediaSnapshot>> = _byPackage.asStateFlow()

    /**
     * 采集总开关（**按需启用**）。
     *
     * 只有当存在规则引用 `media.*` 字段时才置 true（由规则集变化时重新计算）。
     * 为 false 时：采集回调立刻返回、[update] 直接丢弃 —— 不构造对象、不写 StateFlow，
     * 这就是「没有规则用到就完全不采」的落点。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) reset()
    }

    /** 最近一次更新的包名（决定 [latest] 返回谁）。 */
    @Volatile
    private var lastPackage: String? = null

    /**
     * 最近活跃的那份快照。
     *
     * 注入规则快照时用它：**不按通知来源包名取**——用户要的是「我在听什么」，
     * 而不是「发这条通知的 App 在播什么」（来一条微信通知时，音乐仍在放）。
     */
    fun latest(): MediaSnapshot? {
        lastPackage?.let { pkg -> _byPackage.value[pkg]?.let { return it } }
        return _byPackage.value.values.maxByOrNull { it.updatedAtMs }
    }

    /** 指定 App 的媒体快照（没有则 null）。 */
    fun forPackage(pkg: String?): MediaSnapshot? = pkg?.let { _byPackage.value[it] }

    /** 写入一份快照（同值不写，避免高频重组）。 */
    fun update(snapshot: MediaSnapshot) {
        if (!enabled) return
        val prev = _byPackage.value[snapshot.packageName]
        if (prev != null && prev.copy(updatedAtMs = 0L) == snapshot.copy(updatedAtMs = 0L)) {
            // 内容没变：只刷新时间戳，避免订阅者被无意义唤醒
            if (prev.updatedAtMs != snapshot.updatedAtMs) {
                _byPackage.value = _byPackage.value +
                    (snapshot.packageName to prev.copy(updatedAtMs = snapshot.updatedAtMs))
            }
            return
        }
        lastPackage = snapshot.packageName
        _byPackage.value = _byPackage.value + (snapshot.packageName to snapshot)
    }

    fun remove(pkg: String) {
        if (!_byPackage.value.containsKey(pkg)) return
        _byPackage.value = _byPackage.value - pkg
        if (lastPackage == pkg) lastPackage = null
    }

    fun reset() {
        if (_byPackage.value.isEmpty()) return
        _byPackage.value = emptyMap()
        lastPackage = null
    }
}
