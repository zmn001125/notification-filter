package com.local.notificationfilter.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.local.notificationfilter.util.NfLog
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TTS 朗读管理（P0-8 降级 + P0-2 按需初始化）。
 *
 * 设计要点：
 * 1. **惰性初始化**：构造函数只保存 applicationContext，**不**创建 [TextToSpeech]。
 *    系统 TTS 引擎是独立进程（常驻 20-60MB），构造 `TextToSpeech` 会立刻
 *    `bindService` 把它拉起来；只有真正收到「朗读」请求时才绑定（P0-2 省电）。
 * 2. **空闲释放**：连续 [IDLE_RELEASE_MS] 毫秒没有朗读请求，自动 `shutdown()` 引擎并复位
 *    全部状态，下次朗读再重新绑定。实现为 Handler 单次延时任务（无周期轮询、无 wake lock）；
 *    若释放时仍在合成，则延后 [RELEASE_RETRY_DELAY_MS] 重试，最多 [MAX_RELEASE_RETRY] 次。
 * 3. **降级**：初始化失败或缺少中文语音时 `speak()` 返回 false，调用方记
 *    `READ_ALOUD_SKIPPED`，绝不影响其他动作。
 * 4. **时序坑（P1-1）**：`TextToSpeech(context, listener)` 可能在构造期**同步**回调
 *    `onInit`，此时字段 `tts` 还没有被赋值。`onInit` 遇到 `tts == null` 只记日志并返回，
 *    语言设置（`setLanguage(Locale.CHINA)`）由 [ensureEngine] 在赋值后补做一次。
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    /** 主线程 Handler：仅用于「空闲释放引擎」的单次延时任务。 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 保护引擎的创建 / 释放与各状态标志（speak 可能跑在 IO 线程，onInit 在主线程）。
     * 可重入，[ensureEngine] 创建引擎时**不**持锁，避免构造期同步回调被阻塞。
     */
    private val lock = Any()

    @Volatile
    private var tts: TextToSpeech? = null

    /** 是否已经发起过引擎创建（创建中或已创建）。 */
    @Volatile
    private var initStarted = false

    /** 引擎初始化是否已有结论（成功或失败）。语义与旧实现一致。 */
    @Volatile
    private var initDone = false

    /** 中文语音是否可用。语义与旧实现一致。 */
    @Volatile
    private var chineseAvailable = false

    /** 语言是否已设置过（每个引擎实例只设置一次）。 */
    @Volatile
    private var languageConfigured = false

    /** [onInit] 回传的 status；[ON_INIT_PENDING] 表示回调尚未到达。 */
    @Volatile
    private var onInitStatus = ON_INIT_PENDING

    /** 空闲释放的重试计数（0 表示未重试）。 */
    private var idleReleaseAttempt = 0

    /** 空闲释放任务（单次延时，可被 [release] / [scheduleIdleRelease] 移除）。 */
    private val idleReleaseTask = Runnable { performIdleRelease() }

    override fun onInit(status: Int) {
        onInitStatus = status
        initDone = true
        if (status != TextToSpeech.SUCCESS) {
            chineseAvailable = false
            NfLog.w(this, "TTS onInit 失败 status=$status，朗读动作将降级")
            return
        }
        val engine = tts
        if (engine == null) {
            // 构造期同步回调：字段尚未赋值，语言设置留给 ensureEngine() 赋值后补做。
            NfLog.w(this, "TTS onInit 早于引擎赋值（构造期同步回调），语言设置延后到首次朗读")
            return
        }
        configureLanguage(engine)
    }

    /** 引擎是否可用（已初始化且中文语音可用）；不触发引擎创建。 */
    fun isAvailable(): Boolean = initDone && chineseAvailable

    /**
     * 朗读标题 + 正文；需要时才绑定引擎，等待引擎就绪最多 [INIT_WAIT_MS] 毫秒。
     *
     * @param title 通知标题，可为 null。
     * @param text 通知正文，可为 null。
     * @return true = 已播放；false = 内容为空 / 不可用 / 失败（调用方记 READ_ALOUD_SKIPPED）。
     */
    suspend fun speak(title: String?, text: String?): Boolean {
        val content = buildContent(title, text)
        // 空内容直接跳过：不为了说一句空话把 TTS 引擎进程拉起来（P0-2）。
        if (content.isBlank()) return false
        val engine = awaitEngine()
        if (engine == null) {
            // 引擎可能已经绑定但不支持中文：同样要排空闲释放，否则引擎进程会一直常驻
            scheduleIdleReleaseIfBound()
            return false
        }
        // 每次朗读都重置空闲计时，停读 5 分钟后自动释放引擎。
        scheduleIdleRelease()
        return runCatching {
            engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            true
        }.getOrElse {
            NfLog.w(this, "TTS 播放失败: ${it.message}")
            false
        }
    }

    /**
     * 释放引擎并复位状态（幂等，无参，可重复调用）。
     *
     * 外部显式释放与空闲自动释放共用；释放后下次 [speak] 会重新惰性绑定。
     */
    fun release() {
        handler.removeCallbacks(idleReleaseTask)
        val engine = synchronized(lock) {
            val current = tts
            tts = null
            initStarted = false
            initDone = false
            chineseAvailable = false
            languageConfigured = false
            onInitStatus = ON_INIT_PENDING
            idleReleaseAttempt = 0
            current
        }
        if (engine == null) return
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
        NfLog.i(this, "TTS 引擎已释放")
    }

    // ==================== 引擎生命周期 ====================

    /**
     * 确保引擎已创建（惰性绑定）。
     *
     * @return 已创建的引擎；正在初始化中或创建失败返回 null。
     */
    private fun ensureEngine(): TextToSpeech? {
        synchronized(lock) {
            tts?.let { engine ->
                // 构造期同步回调漏掉的语言设置，在这里补做一次（仅在 onInit 成功后）
                if (onInitStatus == TextToSpeech.SUCCESS && !languageConfigured) {
                    configureLanguage(engine)
                }
                return engine
            }
            if (initStarted) return null
            initStarted = true
            // 新建引擎前复位状态，避免上一次 release 留下的旧值被误用
            initDone = false
            chineseAvailable = false
            languageConfigured = false
            onInitStatus = ON_INIT_PENDING
        }

        NfLog.i(this, "收到朗读请求，惰性初始化 TTS 引擎")
        // 构造放在锁外：构造期可能同步回调 onInit，持锁会让状态机难以推理
        val created = runCatching { TextToSpeech(appContext, this) }.getOrElse { e ->
            NfLog.w(this, "TTS 初始化失败: ${e.message}")
            synchronized(lock) {
                initStarted = false
                initDone = false
                chineseAvailable = false
                languageConfigured = false
                onInitStatus = ON_INIT_PENDING
            }
            return null
        }

        synchronized(lock) {
            tts = created
            // 构造期同步回调已成功 → onInit 当时拿不到 tts，这里补做语言设置
            if (onInitStatus == TextToSpeech.SUCCESS && !languageConfigured) {
                configureLanguage(created)
            }
        }
        return created
    }

    /** 设置中文语言（每个引擎实例只做一次）。 */
    private fun configureLanguage(engine: TextToSpeech) {
        synchronized(lock) {
            if (languageConfigured) return
            val result = runCatching { engine.setLanguage(Locale.CHINA) }.getOrNull()
            chineseAvailable = result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            languageConfigured = true
            if (chineseAvailable) {
                NfLog.i(this, "TTS 中文语音就绪，朗读动作可用")
            } else {
                NfLog.w(this, "TTS 无中文语音数据（code=$result），朗读动作将降级")
            }
        }
    }

    /** 等待异步初始化完成；超时或不可用返回 null。 */
    private suspend fun awaitEngine(): TextToSpeech? {
        ensureEngine()
        if (!initDone) {
            withTimeoutOrNull(INIT_WAIT_MS) {
                while (!initDone) {
                    delay(50)
                }
            }
        }
        // 再取一次：可能刚等到 onInit，需要补做语言设置
        val engine = ensureEngine() ?: return null
        if (!initDone || !chineseAvailable) return null
        return engine
    }

    // ==================== 空闲释放（P0-2） ====================

    /** 重置空闲计时：移除旧任务后重新排一次单次延时任务。 */
    private fun scheduleIdleRelease() {
        handler.removeCallbacks(idleReleaseTask)
        idleReleaseAttempt = 0
        handler.postDelayed(idleReleaseTask, IDLE_RELEASE_MS)
    }

    /** 引擎已绑定（哪怕本次朗读不可用）时也排一次空闲释放，避免引擎进程无谓常驻。 */
    private fun scheduleIdleReleaseIfBound() {
        if (tts != null) scheduleIdleRelease()
    }

    /** 空闲计时到点：仍在合成则延后重试（最多 [MAX_RELEASE_RETRY] 次），否则释放引擎。 */
    private fun performIdleRelease() {
        if (isSpeaking()) {
            if (idleReleaseAttempt < MAX_RELEASE_RETRY) {
                idleReleaseAttempt++
                NfLog.i(
                    this,
                    "TTS 仍在合成，${RELEASE_RETRY_DELAY_MS / 1_000} 秒后重试释放" +
                        "（第 $idleReleaseAttempt/$MAX_RELEASE_RETRY 次）",
                )
                handler.postDelayed(idleReleaseTask, RELEASE_RETRY_DELAY_MS)
                return
            }
            NfLog.w(this, "TTS 重试 $MAX_RELEASE_RETRY 次仍在合成，直接释放")
        }
        idleReleaseAttempt = 0
        if (tts == null) return
        NfLog.i(this, "TTS 空闲 ${IDLE_RELEASE_MS / 60_000} 分钟，释放引擎以节省内存与电量")
        release()
    }

    /** 引擎当前是否正在合成（读取失败按「未在合成」处理）。 */
    private fun isSpeaking(): Boolean {
        val engine = tts ?: return false
        return runCatching { engine.isSpeaking }.getOrDefault(false)
    }

    private fun buildContent(title: String?, text: String?): String {
        val t = title?.trim().orEmpty()
        val b = text?.trim().orEmpty()
        return when {
            t.isBlank() && b.isBlank() -> ""
            t.isBlank() -> b
            b.isBlank() -> t
            t == b -> t
            else -> "$t。$b"
        }
    }

    private companion object {
        const val INIT_WAIT_MS = 2_000L
        const val UTTERANCE_ID = "nf_read_aloud"

        /** 空闲多久释放引擎：5 分钟。 */
        const val IDLE_RELEASE_MS = 5 * 60 * 1_000L

        /** 合成中被打断时的重试间隔：30 秒。 */
        const val RELEASE_RETRY_DELAY_MS = 30 * 1_000L

        /** 合成中最多重试几次就强制释放（避免死循环）。 */
        const val MAX_RELEASE_RETRY = 3

        /** onInit 尚未回调的哨兵值（必须避开 TextToSpeech.SUCCESS=0 / ERROR=-1）。 */
        const val ON_INIT_PENDING = Int.MIN_VALUE
    }
}
