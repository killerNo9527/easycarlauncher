package com.lf.launcher.settings

import android.content.Context
import com.lf.launcher.apps.AppEntry
import com.lf.launcher.apps.AppRepository

/**
 * 状态栏左上角的「快捷应用」。
 *
 * ## 数据怎么存
 *
 * 只存一串包名（`Keys.STATUS_SHORTCUT_APPS`，逗号分隔），**顺序就是显示顺序** ——
 * 用户排序改的就是这个字符串里元素的先后。图标和名字不存：应用升级换了图标，
 * 存下来的旧图就是一张过期的画，每次按包名现查一遍最省事。
 *
 * ## 为什么不定死 2 个起步
 *
 * 需求是「2~5 个」。这里允许只选 1 个（甚至 0 个）：用户很可能先把旧的删掉再添新的，
 * 中间那一瞬间不该被拦住。真正要守的是上限 5 —— 超过就不是"快捷"了，是一排图标墙。
 */
object StatusShortcuts {

    /** 建议下限（只用于提示，不硬拦） */
    const val MIN = 2

    /** 硬上限：状态栏左侧最多放这么多个 */
    const val MAX = 5

    fun enabled(): Boolean = SettingsStore.getBool(Keys.STATUS_SHORTCUTS, false)

    fun setEnabled(on: Boolean) = SettingsStore.setBool(Keys.STATUS_SHORTCUTS, on)

    /** 已选包名，顺序即显示顺序 */
    fun packages(): List<String> = parse(SettingsStore.getString(Keys.STATUS_SHORTCUT_APPS, ""))

    fun save(list: List<String>) {
        SettingsStore.setString(
            Keys.STATUS_SHORTCUT_APPS,
            list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX).joinToString(","),
        )
    }

    fun add(pkg: String) {
        val list = packages().toMutableList()
        if (list.contains(pkg) || list.size >= MAX) return
        list.add(pkg)
        save(list)
    }

    fun remove(pkg: String) = save(packages().filter { it != pkg })

    /** 上移 / 下移一格；到边界时什么都不做 */
    fun move(pkg: String, delta: Int) {
        val list = packages().toMutableList()
        val from = list.indexOf(pkg)
        if (from < 0) return
        val to = from + delta
        if (to < 0 || to >= list.size) return
        list.removeAt(from)
        list.add(to, pkg)
        save(list)
    }

    /**
     * 按包名解析成可启动项。
     *
     * 已经卸载的应用直接跳过，但**不写回配置** —— 车机上应用被临时停用 / 升级中
     * 都会短暂查不到，写回去等于把用户的选择删了。配置本身有上限 5，脏数据不会堆积。
     */
    fun resolve(context: Context, packages: List<String> = packages()): List<AppEntry> {
        val byPackage = AppRepository.loadLaunchable(context).associateBy { it.packageName }
        return packages.mapNotNull { byPackage[it] }
    }

    /** 设置页右侧显示的摘要 */
    fun summary(context: Context): String {
        val valid = resolve(context)
        return if (valid.isEmpty()) "未选择" else "${valid.size} 个：" + valid.joinToString("、") { it.label }
    }

    private fun parse(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)
}
