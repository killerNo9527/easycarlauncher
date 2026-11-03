package com.lf.launcher.music

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lf.launcher.lyric.LyricInfoParser
import com.lf.launcher.lyric.LyricLine
import com.lf.launcher.lyric.LyricTimeline
import com.lf.launcher.lyric.PlaybackClock

/**
 * 播放器接入（md3Music）。
 *
 * ## 为什么必须用 Media3 的 MediaController
 *
 * md3Music 把整首歌词 JSON 写进 **MediaItem.mediaMetadata.extras["lyricInfo"]**
 * （源码 `AudioPlayer.applySessionTitleArtistAndLyricInfo`，注释写明「让媒体3会话的
 * MediaItem.mediaMetadata.extras 携带该字段」）。
 *
 * 这是 Media3 自己的 MediaMetadata，它有 extras 字段；
 * 而 framework 的 `android.media.MediaMetadata` **根本没有 extras**（只有
 * MediaMetadataCompat 才有），所以走 `android.media.session.MediaController`
 * 永远读不到歌词 —— 只拿得到歌名、歌手、封面、进度。
 *
 * 用 Media3 的 MediaController 连同一个 MediaSessionService，`onMediaMetadataChanged`
 * 给出的就是带 extras 的 MediaMetadata，歌词一步到位。
 *
 * ## 前提
 * md3Music 设置里要打开「歌词转发」（`settings_lyric_info_enabled`）。
 *
 * ## 换歌为什么还要重连（重要）
 * 这台设备上的 md3Music 6.2.0 换歌时只更新会话元数据，**不推送**变更事件：
 * `onMediaMetadataChanged` / `onMediaItemTransition` 都不触发，控制器的 `mediaMetadata`
 * 会一直停在上一首。因此由 [detectTrackChange] 从位置/时长推断换歌，再用
 * [resyncForTrackChange] 重连一次把元数据和歌词重新读出来。
 */
object MusicController {

    private const val TAG = "MusicController"

    /** 换歌推断：位置从 >4s 掉回 <1.2s 视为「回到开头」（换歌 / 重播） */
    private const val RESYNC_MIN_POS_MS = 4_000L
    private const val RESYNC_RESET_POS_MS = 1_200L

    /** 时长变化超过 0.8s 视为换了一首歌 */
    private const val RESYNC_DURATION_TOLERANCE_MS = 800L

    /** 两次重连的最小间隔，避免连锁重连 */
    private const val RESYNC_DEBOUNCE_MS = 3_000L

    /** 事件触发重连的延迟：留时间给对方把新歌装好 */
    private const val RESYNC_DELAY_MS = 350L

    /** 重连刚完成后的静默期：期间不吃「事件触发的重连」，防止自激 */
    private const val RESYNC_QUIET_MS = 2_500L

    /** 用户主动切歌后的重连延迟（留时间给对方把新曲目写进会话） */
    private const val RESYNC_AFTER_SKIP_MS = 800L

    /** 重连后元数据仍为空时的重试间隔与次数 */
    private const val RESYNC_RETRY_MS = 700L
    private const val MAX_EMPTY_META_RETRIES = 4

    /** 刚 attach 完的这段时间里来的 isPlaying=true 是重连回放，不是用户按的播放 */
    private const val LYRIC_RETRY_QUIET_MS = 2_000L

    const val MD3_PKG = "com.md3music.md3music"
    const val MD3_SERVICE = "com.md3music.md3music.MD3MusicMediaSessionService"
    const val EXTRA_LYRIC_INFO = "lyricInfo"

    /** 收藏命令名：车载桌面与播放器约定的自定义命令 */
    private const val CMD_FAVORITE = "com.md3music.FAVORITE"

    /** 收藏字段的候选 key，按常见命名排 */
    private val FAVORITE_KEYS = listOf(
        "is_favorite", "isFavorite", "favorite", "favourite",
        "is_favourite", "isCollected", "is_collected", "collect",
    )

    data class Snapshot(
        val connected: Boolean,
        val title: String = "",
        val artist: String = "",
        val albumArt: Bitmap? = null,
        val isPlaying: Boolean = false,
        val isFavorite: Boolean = false,
        val durationMs: Long = 0L,
        val timeline: LyricTimeline? = null,
        val hasLyric: Boolean = false,
        val lyricLineCount: Int = 0,
        /** 正在补拉歌词：这时的「0 行」是暂时的，界面不该立刻说「暂无歌词」 */
        val lyricPending: Boolean = false,
        val error: String = "",
    )

    private var context: Context? = null
    private var controller: MediaController? = null
    private var pending: ListenableFuture<MediaController>? = null
    private var connecting = false
    private var lastError = ""

    private val clock = PlaybackClock()
    private val handler = Handler(Looper.getMainLooper())

    private var title = ""
    private var artist = ""
    private var albumArt: Bitmap? = null
    private var isPlaying = false
    private var favorite = false
    private var durationMs = 0L
    private var lyricRaw: String? = null
    private var timeline: LyricTimeline? = null
    private var lyricCount = 0

    private var listener: ((Snapshot) -> Unit)? = null

    /** 上一次 tick 看到的位置/时长，用来推断「换歌了」 */
    private var lastPos = 0L
    private var lastDur = 0L

    /** 上一次因换歌重连的时刻（elapsedRealtime），用来做去抖 */
    private var lastResyncAt = 0L

    /** 正在重连，避免 tick 连续触发 */
    private var resyncing = false

    /** 重连读回空元数据的重试次数（换歌瞬间会话可能还没准备好） */
    private var emptyMetaRetries = 0

    /** 重连刚完成的一小段时间内，忽略「事件触发的重连」，否则会自激成死循环 */
    private var suppressResyncUntil = 0L

    /** 歌词字段对应的曲目名，用来区分「同一首的精简 metadata」和「真的换歌了」 */
    private var lyricTitle = ""

    /**
     * 歌词补拉的重试节奏（elapsedRealtime 间隔）。
     *
     * 为什么需要：实测 md3Music 换歌瞬间推来的 metadata **extras 是空的**（没有 lyricInfo），
     * 等它把歌词加载好之后**不会**再补推一条 —— 控制器缓存就一直停在「无歌词」。
     * 但我们重新连一次会话，同一时刻读回来就是完整的歌词（同一首歌 34 秒后重连：
     * `hasLyric=false` → `61 行`）。所以这不是「我们读得不够勤」，是对方不补推；
     * 只能自己按节奏重连去要。
     *
     * 注：media3 1.4.1 的 `MediaController` 没有 `requestMetadata()`（那是 MediaSession 侧的能力），
     * 所以这里只能靠重连。四次（约 11 秒）足够（实测第二次内就能拿到），不会一直骚扰对方。
     */
    private val lyricRetryDelays = longArrayOf(600L, 1_500L, 3_000L, 6_000L)
    private var lyricRetry = 0
    private var nextLyricRetryAt = 0L

    /** 最近一次 attach 的时刻。重连后对方会补发一次 isPlaying=true，那不是用户按的播放 */
    private var lastAttachAt = 0L

    /** 合并短时间内的多次重连请求（连点下一首时只重连一次） */
    private val resyncTask = Runnable { resyncForTrackChange() }

    private val tick = object : Runnable {
        override fun run() {
            controller?.let { c ->
                if (c.isPlaying) clock.sync(c.currentPosition, 1f, durationMs)
                detectTrackChange(c)
            }
            maybeRetryLyric()
            publish()
            handler.postDelayed(this, 800L)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    fun start(ctx: Context) {
        context = ctx.applicationContext
        if (controller != null || connecting) return
        connect()
    }

    private fun connect() {
        val ctx = context ?: return
        if (connecting) return

        connecting = true
        lastError = ""

        // SessionToken 的构造会校验目标 service 是否声明了 MediaSessionService /
        // MediaBrowserService 等能力。装了音乐应用但版本不对（或根本不是 Media3 实现）时，
        // 这里会直接抛 IllegalArgumentException —— 必须在启动桌面时兜住，
        // 否则整个 Launcher 会被一个第三方应用拖崩。
        val token = runCatching { SessionToken(ctx, ComponentName(MD3_PKG, MD3_SERVICE)) }
            .onFailure {
                connecting = false
                resyncing = false
                lastError = "md3Music 不支持 Media3 会话：${it.javaClass.simpleName}"
                Log.w(TAG, lastError, it)
                publish()
            }
            .getOrNull() ?: return
        val future = MediaController.Builder(ctx, token).buildAsync()
        pending = future

        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                resyncing = false
                lastError = "连接失败：md3Music 未安装或未启动"
                Log.w(TAG, lastError)
                publish()
                return@addListener
            }
            attach(c)
        }, ContextCompat.getMainExecutor(ctx))

        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun attach(c: MediaController) {
        if (controller === c) return
        val recovering = resyncing
        controller?.removeListener(playerListener)
        controller = c
        c.addListener(playerListener)
        resyncing = false
        lastAttachAt = SystemClock.elapsedRealtime()
        // 位置/时长的基线要按新连接重来，否则重连后的第一帧会被自己的推断逻辑误判成又一次换歌
        lastPos = c.currentPosition
        lastDur = c.duration.let { if (it == C.TIME_UNSET) 0L else it }
        suppressResyncUntil = SystemClock.elapsedRealtime() + RESYNC_QUIET_MS

        val meta = c.mediaMetadata
        if (meta.title.isNullOrEmpty() && meta.artist.isNullOrEmpty() && meta.artworkData == null) {
            // 刚换歌就重连时，对方可能还没把新曲目写进会话，读回来是空的。
            // 这时**不要**把旧值清掉（会闪成「暂无播放」），保留并稍后重试。
            if (recovering && emptyMetaRetries < MAX_EMPTY_META_RETRIES) {
                emptyMetaRetries++
                Log.i(TAG, "重连后元数据为空，稍后重试（第 $emptyMetaRetries 次）")
                publish()
                scheduleResync(RESYNC_RETRY_MS, force = true, retry = true)
                return
            }
            publish()
            return
        }

        emptyMetaRetries = 0
        readPlayer(c)
        Log.i(TAG, "已连接 md3Music（Media3），title=$title artist=$artist")
        publish()
    }

    fun setListener(l: ((Snapshot) -> Unit)?) {
        listener = l
        l?.invoke(snapshot())
    }

    /**
     * 换歌探测。
     *
     * ## 为什么需要它
     *
     * 实测 md3Music（6.2.0）换歌时推来的事件里**没有**元数据/时间轴/条目切换，
     * 控制器的 title / duration / currentPosition 全冻结在上一首：
     *
     * ```
     * ev[13] pos=108513 dur=371121 title=那些年 ...   ← 换歌后事件到了，状态还是旧的
     * tick   title=那些年 pos=116276 dur=371121       ← 8 秒后仍然冻结
     * dumpsys media_session → 千百度 / 许嵩 / position=11486   ← 会话那边早就是新歌了
     * ```
     *
     * 会话状态是新的，只是没有能力推增量；**重新建一次连接就能读到最新元数据和歌词**
     * （已验证：重连后立刻拿到新歌名、新时长、新歌词时间轴）。
     *
     * 所以这里做兜底推断：位置归零 / 时长变化 → 换歌。真正的主力是
     * [playerListener] 的 onEvents（事件到了但没带元数据）和 [next] / [previous] 的显式请求。
     */
    private fun detectTrackChange(c: MediaController) {
        val pos = c.currentPosition
        val dur = c.duration.let { if (it == C.TIME_UNSET) 0L else it }

        // 换歌时进度回到开头；一首歌播到一半再被用户拖回开头也会命中，重连一次无副作用
        val jumpedToStart = lastPos > RESYNC_MIN_POS_MS && pos < RESYNC_RESET_POS_MS
        val durationChanged = dur > 0 && lastDur > 0 && kotlin.math.abs(dur - lastDur) > RESYNC_DURATION_TOLERANCE_MS
        val cooledDown = SystemClock.elapsedRealtime() - lastResyncAt > RESYNC_DEBOUNCE_MS

        lastPos = pos
        if (dur > 0) lastDur = dur

        if (resyncing || !cooledDown) return
        if (!jumpedToStart && !durationChanged) return

        resyncForTrackChange()
    }

    /**
     * 歌词为空时按 [lyricRetryDelays] 重连补拉。
     *
     * 判定条件很保守：正在播放 + 有歌名 + 歌词 0 行。所以「这首本来就没歌词」只会白试 4 次
     * （约 11 秒）就停，不会一直骚扰对方；一旦拿到歌词或换歌，计数立刻清零。
     */
    private fun maybeRetryLyric() {
        if (lyricCount > 0 || title.isBlank() || !isPlaying) {
            lyricRetry = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lyricRetry >= lyricRetryDelays.size) return
        if (now < nextLyricRetryAt) return

        nextLyricRetryAt = now + lyricRetryDelays[lyricRetry]
        lyricRetry++
        Log.i(TAG, "歌词为空（title=$title），第 $lyricRetry 次重连补拉")
        scheduleResync(0L, force = true)
    }

    /**
     * 排一次重连。
     *
     * @param delayMs 延迟，给会话一点时间把新一首歌装好
     * @param force 用户主动切歌时用 true，跳过「刚连上」的静默期
     */
    private fun scheduleResync(
        delayMs: Long = RESYNC_DELAY_MS,
        force: Boolean = false,
        retry: Boolean = false,
    ) {
        if (resyncing) return
        if (!force && SystemClock.elapsedRealtime() < suppressResyncUntil) return
        // 新的一轮（用户切歌 / 事件触发）重新给满重试次数，只有重试自己不清零
        if (!retry) emptyMetaRetries = 0
        handler.removeCallbacks(resyncTask)
        handler.postDelayed(resyncTask, delayMs)
    }

    /**
     * 重连会话以重新读取元数据/歌词。
     *
     * 只释放并重建 MediaController，**不会**影响对方播放（控制器只是观察者+遥控器）。
     * 期间 [resyncing] 保持为 true，[snapshot] 仍报 connected，避免界面闪成「未连接播放器」。
     */
    private fun resyncForTrackChange() {
        val c = controller ?: return
        if (connecting) return
        resyncing = true
        lastResyncAt = SystemClock.elapsedRealtime()
        controller = null
        c.removeListener(playerListener)
        runCatching { c.release() }
        Log.i(TAG, "换歌后重连会话，刷新元数据与歌词")
        connect()
    }

    fun snapshot() = Snapshot(
        // 重连期间不要报「未连接」：那会让底栏和音乐卡闪一下「未连接播放器」
        connected = controller != null || resyncing,
        title = title,
        artist = artist,
        albumArt = albumArt,
        isPlaying = isPlaying,
        isFavorite = favorite,
        durationMs = durationMs,
        timeline = timeline,
        hasLyric = timeline != null && !timeline!!.isEmpty,
        lyricLineCount = lyricCount,
        lyricPending = lyricCount == 0 && lyricRetry > 0,
        error = lastError,
    )

    fun positionMs(): Long = controller?.currentPosition ?: clock.now()

    // ---------------------------------------------------------------- 读取

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            applyMetadata(mediaMetadata)
            publish()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            // 暂停再播相当于又给了对方一次机会，重试计数要放回去。
            // 但**重连之后**对方也会补一次 isPlaying=true —— 那不是用户按的播放。
            // 不排掉它就会：补拉 → 重连 → 计数清零 → tick 又补拉 → 无限重连。
            val userResumed = SystemClock.elapsedRealtime() - lastAttachAt > LYRIC_RETRY_QUIET_MS
            if (playing && lyricCount == 0 && userResumed) {
                lyricRetry = 0
                nextLyricRetryAt = 0L
            }
            controller?.let { clock.sync(it.currentPosition, if (playing) 1f else 0f, durationMs) }
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { readPlayer(it) }
            publish()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // md3Music 6.2.0 换歌时推给控制器的事件里**不含**元数据/时间轴/条目切换
            // （实测只有 AVAILABLE_COMMANDS_CHANGED），控制器的 title/duration/position
            // 会一直冻结在上一首。所以：
            //   带元数据的事件 → 就地重读；
            //   不带 → 说明这批事件没有刷新能力，排一次重连去拿真实状态。
            val metaDelivered = events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            if (metaDelivered) {
                readPlayer(player)
                publish()
            } else {
                scheduleResync()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            lastError = error.errorCodeName
            publish()
        }
    }

    private fun readPlayer(c: Player) {
        applyMetadata(c.mediaMetadata)
        isPlaying = c.isPlaying
        val d = c.duration
        if (d != C.TIME_UNSET && d > 0) durationMs = d
        clock.sync(c.currentPosition, if (isPlaying) 1f else 0f, durationMs)
    }

    private fun applyMetadata(metadata: MediaMetadata) {
        val newTitle = metadata.title?.toString() ?: ""
        // 真的换歌了才重置补拉进度；同一首反复推 metadata 不该把它清零
        if (newTitle != title) {
            lyricRetry = 0
            nextLyricRetryAt = 0L
        }
        title = newTitle
        artist = metadata.artist?.toString()
            ?: metadata.albumArtist?.toString() ?: ""

        val data = metadata.artworkData
        albumArt = if (data == null) null else runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }.getOrNull()

        val d = metadata.durationMs
        if (d != null && d > 0) durationMs = d

        // 关键：Media3 的 MediaMetadata 才有 extras
        val extras = metadata.extras
        favorite = readFavorite(extras)
        val lyric = extras?.getString(EXTRA_LYRIC_INFO)
        // 元数据推送时序探针：md3Music 到底是一次性把歌词塞进 metadata，还是先推元数据、
        // 稍后再补一条带 lyricInfo 的。看这一行的时间戳就能分辨「对方推得晚」还是「我们没接住」
        Log.d(TAG, "metadata: title=$title hasLyric=${lyric != null} keys=${extras?.keySet()}")
        if (lyric == null) {
            // 同一首曲目重推精简 metadata（md3Music 换歌后常这样）不该把已经拿到的歌词抹掉，
            // 只有换到别的歌才清 —— 否则界面会「先有歌词、几秒后变暂无歌词」
            if (lyricRaw != null && title == lyricTitle) {
                Log.d(TAG, "同曲 metadata 没带 lyricInfo，保留原歌词")
            } else {
                if (lyricRaw != null) Log.i(TAG, "extras 里没有 lyricInfo（歌词转发可能没开）")
                clearLyric()
            }
        } else if (lyric != lyricRaw) {
            applyLyric(lyric)
        }
    }

    /**
     * 收藏状态的字段名没有统一标准，各家音乐应用自己定。这里按常见命名逐个探，
     * 探不到就沿用上一次的值（宁可不显示错，也不要每次刷新都跳成未收藏）。
     */
    private fun readFavorite(extras: android.os.Bundle?): Boolean {
        if (extras == null) return favorite
        for (k in FAVORITE_KEYS) {
            if (!extras.containsKey(k)) continue
            return when (val v = extras.get(k)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v == "1" || v.equals("true", ignoreCase = true)
                else -> favorite
            }
        }
        return favorite
    }

    /**
     * 切换收藏。
     *
     * 只能走 Media3 的自定义命令：MediaSession 是只读的元数据通道，
     * 想反过来改对方的状态，只有 [MediaController.sendCustomCommand] 这一条路。
     * 对方没注册这个命令时会返回非 SUCCESS，这时不改 UI，避免「点了变红心但其实没收藏」。
     */
    fun toggleFavorite() {
        val c = controller
        val ctx = context
        if (c == null || ctx == null) return

        val target = !favorite
        val args = android.os.Bundle().apply {
            putBoolean("favorite", target)
            putBoolean("isFavorite", target)
        }

        val sent = runCatching {
            c.sendCustomCommand(SessionCommand(CMD_FAVORITE, android.os.Bundle.EMPTY), args)
        }.getOrNull()

        if (sent == null) {
            Log.w(TAG, "sendCustomCommand 不可用")
            return
        }

        sent.addListener({
            val result = runCatching { sent.get() }.getOrNull()
            val ok = result != null && result.resultCode == SessionResult.RESULT_SUCCESS
            handler.post {
                if (ok) {
                    favorite = target
                    publish()
                } else {
                    Log.w(TAG, "收藏命令未被接受 code=${result?.resultCode}")
                }
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun applyLyric(raw: String) {
        lyricRaw = raw
        lyricTitle = title
        lyricRetry = 0
        nextLyricRetryAt = 0L
        val lines: List<LyricLine> = LyricInfoParser.parse(raw)
        timeline = LyricTimeline(lines)
        lyricCount = lines.size
        val tr = lines.count { !it.translation.isNullOrBlank() }
        Log.i(
            TAG,
            "解析歌词：${lines.size} 行（含翻译 $tr 行），长度 ${raw.length}，" +
                "首行=${lines.firstOrNull()?.text}",
        )
    }

    private fun clearLyric() {
        lyricRaw = null
        timeline = null
        lyricCount = 0
    }

    private fun publish() {
        listener?.invoke(snapshot())
    }

    // ---------------------------------------------------------------- 控制

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        if (controller?.seekToNextMediaItem() == null) return
        // 对方换歌不推元数据，自己主动排一次重连；force 是为了跳过刚连上时的静默期
        scheduleResync(RESYNC_AFTER_SKIP_MS, force = true)
    }

    fun previous() {
        if (controller?.seekToPreviousMediaItem() == null) return
        scheduleResync(RESYNC_AFTER_SKIP_MS, force = true)
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        clock.sync(ms, if (isPlaying) 1f else 0f, durationMs)
        publish()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(resyncTask)
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        pending = null
        resyncing = false
        lastPos = 0L
        lastDur = 0L
        lastResyncAt = 0L
    }
}
