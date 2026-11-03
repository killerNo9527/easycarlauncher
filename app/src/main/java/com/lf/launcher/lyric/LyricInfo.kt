package com.lf.launcher.lyric

import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.abs

/**
 * LyricInfo 解析器 + 位置查询
 * ------------------------------------------------------------------
 * 数据来源：md3Music（com.md3music.md3music）把整首歌词 JSON 写进
 * Media3 会话的 `MediaMetadata.extras["lyricInfo"]`。
 *
 * 读取方式（不需要通知使用权）：
 *
 * ```kotlin
 * val token = SessionToken(context, ComponentName(
 *     "com.md3music.md3music",
 *     "com.md3music.md3music.MD3MusicMediaSessionService"))
 * val browser = MediaBrowser.Builder(context, token).build()
 * // 连上后
 * val json = browser.metadata?.extras?.getString("lyricInfo")
 * val timeline = LyricInfoParser.parse(json)      // 整首，切歌会换
 * ```
 *
 * 两种 JSON 格式（由音乐 App 的「ColorOS 歌词」开关决定），本解析器自动识别：
 *  - 默认（lidesheng LyricInfo 规范）：lyric / rawLyric / translation / roma
 *  - ColorOS Bridge：lyric / rawLyric / translationLyric / lyricType / provider
 *
 * 关键点：它给的是「整首」，不是「当前行」。
 * 所以当前行 = 用播放位置在时间轴上二分出来的，位置由 [PlaybackClock] 插值。
 */

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val roma: String? = null,
) {
    val hasWordTiming: Boolean get() = words.isNotEmpty()
}

object LyricInfoParser {

    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})[.:](\d{1,3})]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})[.:](\d{1,3})>""")
    private val META_TAG = Regex("""^\[(ti|ar|al|by|offset|length|kana|re|ve):""", RegexOption.IGNORE_CASE)

    /** 解析失败或没有歌词时返回空列表，调用方应显示「暂无歌词」。 */
    fun parse(json: String?): List<LyricLine> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            if (root.optBoolean("noLyric", false)) return emptyList()

            val offset = parseOffset(root.optString("lyric", ""))
            val colorOs = root.has("translationLyric") || root.has("lyricType") ||
                root.optString("provider").isNotEmpty()

            val wordLane = root.optString("rawLyric", "")
            val plainLane = root.optString("lyric", "")
            val translationLane = root.optString(if (colorOs) "translationLyric" else "translation", "")
            val romaLane = root.optString("roma", "")

            // 有逐字就用逐字轨做骨架，没有就退回逐行轨
            var lines = parseElrc(wordLane, offset)
            if (lines.isEmpty()) lines = parseLrc(plainLane, offset)
            if (lines.isEmpty()) return emptyList()

            // 逐字轨做骨架（有逐字才有卡拉OK），但显示文本优先用逐行轨——
            // 部分源的 rawLyric 会省略末尾虚词，直接用会显示不全
            if (wordLane.isNotEmpty()) {
                val plain = parseLrc(plainLane, offset)
                if (plain.isNotEmpty()) lines = preferPlainText(lines, plain)
            }

            lines = mergeLane(lines, parseLrc(translationLane, offset)) { line, v -> line.copy(translation = v) }
            lines = mergeLane(lines, parseLrc(romaLane, offset)) { line, v -> line.copy(roma = v) }

            closeEndTimes(dedupeByStart(lines))
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- 解析 ----------

    /** 标准 LRC 的 `[offset:+200]`，单位毫秒。按多数播放器约定：显示时间 = 标签时间 + offset */
    private fun parseOffset(lrc: String): Long {
        val m = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(lrc) ?: return 0L
        return m.groupValues[1].toLongOrNull().orZero()
    }

    private fun parseLrc(lrc: String, offset: Long): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val out = ArrayList<LyricLine>(64)
        for (raw in lrc.split('\n')) {
            val s = raw.trim()
            if (s.isEmpty() || META_TAG.containsMatchIn(s)) continue

            // 一行可以有多个时间标签：[00:01.00][00:05.00]同一句
            // 只取"前缀里全是时间标签/空白"的那些，避免把正文里的方括号误判
            val tags = LINE_TAG.findAll(s).filter { m ->
                val prefix = s.substring(0, m.range.first)
                prefix.isBlank() || LINE_TAG.replace(prefix, "").isBlank()
            }.toList()
            if (tags.isEmpty()) continue
            val text = s.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (t in tags) {
                out += LyricLine(
                    startMs = (tagMs(t.groupValues) + offset).coerceAtLeast(0L),
                    endMs = 0L,
                    text = text,
                )
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * ELRC：`[00:12.345]<00:12.345>字<00:12.500>字<00:13.000>`
     * 行末那个后面没有文字的 `<...>` 是结束标记（ColorOS 模式才有）。
     */
    private fun parseElrc(elrc: String, offset: Long): List<LyricLine> {
        if (elrc.isBlank()) return emptyList()
        val out = ArrayList<LyricLine>(64)
        for (raw in elrc.split('\n')) {
            val s = raw.trim()
            if (s.isEmpty()) continue

            val lineTag = LINE_TAG.find(s) ?: continue
            if (lineTag.range.first != 0) continue
            val lineStart = (tagMs(lineTag.groupValues) + offset).coerceAtLeast(0L)
            val body = s.substring(lineTag.range.last + 1)

            val wordTags = WORD_TAG.findAll(body).toList()
            if (wordTags.isEmpty()) {
                val text = body.trim()
                if (text.isNotEmpty()) out += LyricLine(lineStart, 0L, text)
                continue
            }

            val words = ArrayList<LyricWord>(wordTags.size)
            var pendingEnd: Long? = null
            for ((i, wt) in wordTags.withIndex()) {
                val start = (tagMs(wt.groupValues) + offset).coerceAtLeast(0L)
                val from = wt.range.last + 1
                val to = if (i + 1 < wordTags.size) wordTags[i + 1].range.first else body.length
                val text = body.substring(from, to)
                if (text.isBlank()) {
                    // 空文本 = 结束标记，给上一个字收尾
                    pendingEnd = start
                    if (words.isNotEmpty()) {
                        val last = words.removeAt(words.lastIndex)
                        words += last.copy(endMs = start.coerceAtLeast(last.startMs))
                    }
                } else {
                    words += LyricWord(text, start, 0L)
                }
            }
            if (words.isEmpty()) continue
            // 用下一个字的起点补本字的终点
            for (i in 0 until words.size - 1) {
                if (words[i].endMs <= words[i].startMs) {
                    words[i] = words[i].copy(endMs = words[i + 1].startMs)
                }
            }
            val lineText = words.joinToString("") { it.text }.trim()
            if (lineText.isEmpty()) continue
            out += LyricLine(
                startMs = lineStart,
                endMs = pendingEnd ?: words.last().endMs,
                text = lineText,
                words = words,
            )
        }
        return out.sortedBy { it.startMs }
    }

    // ---------- 合并 ----------

    private fun preferPlainText(lines: List<LyricLine>, plain: List<LyricLine>): List<LyricLine> =
        lines.map { line ->
            val p = plain.nearest(line.startMs)
            if (p != null && p.text.isNotBlank() && p.text != line.text) line.copy(text = p.text) else line
        }

    /** 同一时间戳出现两条（部分源把翻译也塞进 lyric 轨）时，保留文本更长的那条 */
    private fun dedupeByStart(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines
        val out = ArrayList<LyricLine>(lines.size)
        for (l in lines) {
            val last = out.lastOrNull()
            if (last != null && last.startMs == l.startMs) {
                val replace = l.text.length > last.text.length ||
                    (l.words.isNotEmpty() && last.words.isEmpty())
                if (replace) out[out.lastIndex] = l
            } else {
                out += l
            }
        }
        return out
    }

    /** 翻译/罗马音是独立 lane，按起始时间对齐（容差 300 ms） */
    private fun mergeLane(
        lines: List<LyricLine>,
        lane: List<LyricLine>,
        apply: (LyricLine, String) -> LyricLine,
    ): List<LyricLine> {
        if (lane.isEmpty()) return lines
        val map = HashMap<Long, String>(lane.size)
        for (l in lane) map[l.startMs] = l.text
        return lines.map { line ->
            val exact = map[line.startMs]
            if (exact != null) apply(line, exact)
            else lane.nearest(line.startMs, 300L)?.let { apply(line, it.text) } ?: line
        }
    }

    private fun closeEndTimes(lines: List<LyricLine>): List<LyricLine> {
        return lines.mapIndexed { i, line ->
            val next = lines.getOrNull(i + 1)?.startMs
            val end = when {
                next != null && next > line.startMs -> next
                line.endMs > line.startMs -> line.endMs
                else -> line.startMs + 5_000L
            }
            line.copy(endMs = end, words = line.words.map { w ->
                if (w.endMs > w.startMs) w else w.copy(endMs = end.coerceAtMost(w.startMs + 3_000L))
            })
        }
    }

    // ---------- 小工具 ----------

    private fun tagMs(g: List<String>): Long {
        val mm = g[1].toLongOrNull().orZero()
        val ss = g[2].toLongOrNull().orZero()
        val frac = g[3]
        val ms = when (frac.length) {
            1 -> frac.toLongOrNull().orZero() * 100
            2 -> frac.toLongOrNull().orZero() * 10
            else -> frac.padEnd(3, '0').take(3).toLongOrNull().orZero()
        }
        return mm * 60_000 + ss * 1_000 + ms
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun List<LyricLine>.nearest(ms: Long, tolerance: Long = 200L): LyricLine? {
        if (isEmpty()) return null
        var best: LyricLine? = null
        var bestDiff = Long.MAX_VALUE
        for (l in this) {
            val d = abs(l.startMs - ms)
            if (d < bestDiff) { bestDiff = d; best = l }
            if (l.startMs > ms && d > bestDiff) break
        }
        return if (bestDiff <= tolerance) best else null
    }
}

/** 时间轴查询：给定播放位置，返回当前行与逐字进度 */
class LyricTimeline(private val lines: List<LyricLine>) {

    val isEmpty: Boolean get() = lines.isEmpty()

    fun lineAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun line(index: Int): LyricLine? = lines.getOrNull(index)

    /** 0f..1f：当前行整体进度，用于没有逐字时的整行扫光 */
    fun lineProgress(index: Int, positionMs: Long): Float {
        val l = lines.getOrNull(index) ?: return 0f
        val span = (l.endMs - l.startMs).coerceAtLeast(1L)
        return ((positionMs - l.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /**
     * 逐字进度：返回 [当前字下标, 该字内进度 0f..1f]。
     * 没有逐字信息时返回 [-1, 行进度]，调用方退回整行渲染。
     */
    fun wordProgress(index: Int, positionMs: Long): Pair<Int, Float> {
        val l = lines.getOrNull(index) ?: return -1 to 0f
        if (!l.hasWordTiming) return -1 to lineProgress(index, positionMs)

        var lo = 0
        var hi = l.words.size - 1
        var cur = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (l.words[mid].startMs <= positionMs) { cur = mid; lo = mid + 1 } else hi = mid - 1
        }
        val w = l.words[cur]
        val span = (w.endMs - w.startMs).coerceAtLeast(1L)
        val p = ((positionMs - w.startMs).toFloat() / span).coerceIn(0f, 1f)
        return cur to p
    }
}

/**
 * 播放位置插值器
 * ------------------------------------------------------------------
 * MediaController 的 position 不是每帧都更新（大约 1 s 一次 + seek 事件），
 * 直接拿它画逐字扫光会一顿一顿的。做法：收到位置时记基准，读的时候按
 * 单调时钟 + 播放速度外推。
 *
 * 用法：
 * ```
 * // 每帧
 * val pos = clock.now()
 * val idx = timeline.lineAt(pos)
 * // 控制器回调
 * onPositionChanged  -> clock.sync(position, speed)
 * onIsPlayingChanged -> clock.sync(position, if (playing) speed else 0f)
 * onSeek             -> clock.sync(position, speed)
 * ```
 */
class PlaybackClock {
    private var baseMs: Long = 0L
    private var baseAt: Long = 0L
    private var speed: Float = 0f
    private var durationMs: Long = 0L

    fun sync(positionMs: Long, playbackSpeed: Float, duration: Long = -1L) {
        baseMs = positionMs.coerceAtLeast(0L)
        baseAt = SystemClock.elapsedRealtime()
        speed = playbackSpeed.coerceAtLeast(0f)
        if (duration > 0) durationMs = duration
    }

    fun now(): Long {
        val elapsed = SystemClock.elapsedRealtime() - baseAt
        val pos = baseMs + (elapsed * speed).toLong()
        return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
    }

    fun reset() {
        baseMs = 0; baseAt = SystemClock.elapsedRealtime(); speed = 0f; durationMs = 0
    }
}
