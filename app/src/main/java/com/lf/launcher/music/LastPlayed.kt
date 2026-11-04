package com.lf.launcher.music

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 上一次播放过的歌。
 *
 * ## 解决什么
 *
 * 桌面的音乐卡以前在没连上播放器时显示「暂无播放」—— 一张纯空白的卡。
 * 但车主看到这张卡时的心理预期不是"这里什么都没有"，而是"我上次听到哪儿了"。
 * 而且冷启动那几秒（md3Music 还在建会话）恰好就是这个状态，空卡看起来像坏了。
 *
 * 现在把最后一次有效元数据落盘，连不上时显示它，点播放键会拉起音乐 App 续播。
 *
 * ## 为什么封面存文件
 *
 * 歌名/歌手是字符串，扔 SharedPreferences 就够了；封面是几十 KB 的位图，
 * 塞进 XML 会拖慢整个 prefs 的读取（[com.lf.launcher.settings.SettingsStore] 同一个进程里
 * 每次读写都要解析）。所以封面单独压缩成 PNG 放 filesDir，只存路径关系不存二进制。
 *
 * 写文件走单线程队列：换歌瞬间会连着来好几次，同步写盘会卡主线程。
 */
object LastPlayed {

    private const val TAG = "LastPlayed"
    private const val FILE = "last_played"
    private const val K_TITLE = "title"
    private const val K_ARTIST = "artist"
    private const val ART_NAME = "last_played_art.png"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private var lastTitle = ""
    private var lastArtist = ""
    private var lastArt: Bitmap? = null
    private var artLoaded = false
    private var initialized = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "last-played-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        lastTitle = p.getString(K_TITLE, "").orEmpty()
        lastArtist = p.getString(K_ARTIST, "").orEmpty()
        // 封面读盘放到后台：这是启动路径，别在 onCreate 里做 IO
        if (lastTitle.isNotEmpty()) {
            io.execute {
                val f = File(app.filesDir, ART_NAME)
                lastArt = if (f.exists()) {
                    runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
                } else {
                    null
                }
                artLoaded = true
            }
        } else {
            artLoaded = true
        }
    }

    fun title(): String = lastTitle

    fun artist(): String = lastArtist

    fun art(): Bitmap? = if (artLoaded) lastArt else null

    fun has(): Boolean = lastTitle.isNotBlank()

    /**
     * 记一次有效元数据。
     *
     * 只在新歌（或新封面）时落盘：同一首反复推 metadata 是常态，
     * 每次都写一遍既没必要也会让闪存白挨写。
     */
    fun remember(context: Context, title: String, artist: String, art: Bitmap?) {
        if (title.isBlank()) return
        init(context)
        val artChanged = art != null && art !== lastArt
        if (title == lastTitle && artist == lastArtist && !artChanged) return

        lastTitle = title
        lastArtist = artist
        if (art != null) {
            lastArt = art
            artLoaded = true
        }
        prefs?.edit()
            ?.putString(K_TITLE, title)
            ?.putString(K_ARTIST, artist)
            ?.apply()

        // 只有真的换了封面才重写文件；新歌没带封面时要删掉旧图，
        // 否则下次冷启动会把上一首的封面配到这一首的歌名上
        val app = appContext ?: return
        val snapshotArt = art
        io.execute {
            runCatching {
                val f = File(app.filesDir, ART_NAME)
                if (snapshotArt == null) {
                    // 新歌没带封面：删掉旧图，否则下次冷启动会显示上一首的封面
                    if (f.exists()) f.delete()
                } else {
                    FileOutputStream(f).use {
                        snapshotArt.compress(Bitmap.CompressFormat.PNG, 90, it)
                    }
                }
            }.onFailure { Log.w(TAG, "封面落盘失败：$it") }
        }
    }
}
