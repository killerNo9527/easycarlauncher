package com.lf.launcher.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 流程日志：logcat 之外再留一份，落在应用私有目录里。
 *
 * ## 为什么
 *
 * 车机上插不了 adb。以前排查"地图为什么没出来""音乐为什么没连上"，
 * 只能让车主描述现象，我再猜。有了这份环形日志，诊断页能直接把最近几百行摊开，
 * 车主拍张照发过来就够定位了。
 *
 * ## 怎么写
 *
 * 只收**决策类**日志（谁要抢前台、为什么放弃、连接成败），不收每帧渲染那种。
 * 写盘走单线程队列，调用方永远不阻塞；文件超过 [MAX_BYTES] 就丢掉前半段重写，
 * 所以它不会无限增长，也不需要用户清理。
 */
object FlowLog {

    private const val FILE_NAME = "flow.log"
    private const val TAG = "FlowLog"

    /** 单文件上限。256KB 大约等于几千行，够看一次开机 + 一次操作 */
    private const val MAX_BYTES = 256 * 1024

    /** 内存里同时留多少行，诊断页直接读它，不用每次都读盘 */
    private const val KEEP_LINES = 600

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    private var file: File? = null
    private var initialized = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flow-log-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        // 只读尾部：开机时间紧张，别为了 256KB 的日志拖慢 Application.onCreate
        io.execute {
            runCatching {
                if (!f.exists()) return@runCatching
                val start = (f.length() - 64 * 1024).coerceAtLeast(0)
                val text = f.inputStream().use { ins ->
                    ins.skip(start)
                    ins.readBytes().toString(Charsets.UTF_8)
                }
                val tail = text.lineSequence().filter { it.isNotBlank() }.toList()
                synchronized(lock) {
                    lines.clear()
                    tail.takeLast(KEEP_LINES).forEach { lines.addLast(it) }
                }
            }.onFailure { Log.w(TAG, "读旧日志失败：$it") }
        }
    }

    fun i(tag: String, message: String) = write(tag, message, Log.INFO)

    fun w(tag: String, message: String) = write(tag, message, Log.WARN)

    fun e(tag: String, message: String) = write(tag, message, Log.ERROR)

    /** 最近 [n] 行，按时间正序（老的在前） */
    fun tail(n: Int = 200): List<String> = synchronized(lock) {
        val list = lines.toList()
        if (list.size <= n) list else list.takeLast(n)
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        val f = file ?: return
        io.execute { runCatching { if (f.exists()) f.delete() } }
    }

    private fun write(tag: String, message: String, priority: Int) {
        when (priority) {
            Log.WARN -> Log.w(tag, message)
            Log.ERROR -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
        val line = "${stamp()} $tag/${clip(message)}"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > KEEP_LINES) lines.removeFirst()
        }
        val f = file ?: return
        io.execute { appendToFile(f, line) }
    }

    /**
     * 写文件 + 超限裁剪。
     *
     * 裁剪用"读后半段再整体重写"，比逐行 trim 简单，而且这个文件最大也就 256KB，
     * 一次重写的代价可以忽略（而且它在 IO 线程上）。
     */
    private fun appendToFile(f: File, line: String) {
        runCatching {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep)
            }
            f.appendText(line + "\n")
        }.onFailure { Log.w(TAG, "写日志失败：$it") }
    }

    /** `11-04 14:32:07.315` —— 带日期，跨天看日志不至于混淆 */
    private fun stamp(): String {
        val now = java.util.Calendar.getInstance()
        return String.format(
            "%02d-%02d %02d:%02d:%02d.%03d",
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH),
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            now.get(java.util.Calendar.SECOND),
            now.get(java.util.Calendar.MILLISECOND),
        )
    }

    /** 单行超过 400 字截断：诊断页一行太长会挤成好几行，反而看不清 */
    private fun clip(m: String) = if (m.length <= 400) m else m.take(400) + "…"
}
