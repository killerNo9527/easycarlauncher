package com.lf.launcher.settings

/**
 * 右栏卡片的显示顺序。
 *
 * 存的是 `Keys.CARD_ORDER`，逗号分隔，**顺序即从上到下**（"time,music,monitor"）。
 * 地图卡在左边、宽度由布局约束决定，不参与排序；这里只管右栏这一列。
 *
 * 读的时候做了一次归一化：配置里可能有旧版本留下的未知 key、可能缺项（以后加新卡片），
 * 缺的按默认顺序补在末尾、未知的丢掉 —— 比"配置坏了就整列错乱"稳。
 */
object CardOrder {

    const val TIME = "time"
    const val MUSIC = "music"
    const val MONITOR = "monitor"
    const val WEATHER = "weather"

    /** 默认：时间 → 音乐 → 负载 → 天气（新加的卡片默认排在最后，不打扰原来的观感） */
    val DEFAULT = listOf(TIME, MUSIC, MONITOR, WEATHER)

    fun label(key: String): String = when (key) {
        TIME -> "时间日期卡"
        MUSIC -> "音乐卡"
        MONITOR -> "负载卡"
        WEATHER -> "天气卡"
        else -> key
    }

    fun current(): List<String> {
        val raw = SettingsStore.getString(Keys.CARD_ORDER, "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val known = raw.filter { it in DEFAULT }.distinct()
        return known + DEFAULT.filter { it !in known }
    }

    fun save(list: List<String>) {
        val clean = list.filter { it in DEFAULT }.distinct()
        SettingsStore.setString(
            Keys.CARD_ORDER,
            (clean + DEFAULT.filter { it !in clean }).joinToString(","),
        )
    }

    /** 上移 / 下移一格；越界什么都不做（按钮那边会先压暗） */
    fun move(key: String, delta: Int) {
        val list = current().toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return
        val to = from + delta
        if (to !in list.indices) return
        list.removeAt(from)
        list.add(to, key)
        save(list)
    }

    /** 设置页右侧的摘要：时间日期 › 音乐 › 负载 */
    fun summary(): String = current().joinToString(" › ") { label(it) }
}
