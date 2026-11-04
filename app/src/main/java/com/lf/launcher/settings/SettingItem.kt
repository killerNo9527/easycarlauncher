package com.lf.launcher.settings

/** 设置列表的条目模型。分组与命名对照原版 settings_* 结构。 */
sealed class SettingItem {
    data class Section(val title: String) : SettingItem()

    data class SwitchItem(
        val key: String,
        val title: String,
        val desc: String? = null,
        val default: Boolean = true,
        /** 选项尚未接入实现时置 false，UI 上显示"待接入" */
        val ready: Boolean = true,
        /** true 表示界面语义与存储值相反（对应原版的"不绘制…"这类反向开关） */
        val inverted: Boolean = false,
        /**
         * 开关写入之后再做点别的（比如「城市跟随定位」打开时顺手申请定位权限）。
         * 值是**界面上**的开关状态，不是存储值 —— 反向开关时才不会传反。
         */
        val onToggle: ((Boolean) -> Unit)? = null,
    ) : SettingItem()

    data class ChoiceItem(
        val key: String,
        val title: String,
        val desc: String? = null,
        val options: List<Pair<String, String>>,
        val default: String,
        val ready: Boolean = true,
    ) : SettingItem()

    data class InfoItem(val title: String, val value: String) : SettingItem()

    data class ActionItem(
        val title: String,
        val desc: String? = null,
        /** 右侧显示的当前值，null 时显示 › */
        val value: String? = null,
        /**
         * 右侧值的语义色。
         *
         * 车机上扫一眼就知道哪一项要处理 —— 权限被收走、地图没选、音乐没连，
         * 这些"缺了会静默失效"的项一律用 [Tone.WARNING]，不用点进去才发现。
         */
        val tone: Tone = Tone.NORMAL,
        val action: () -> Unit,
    ) : SettingItem()

    /** 设置项右侧值的语义色 */
    enum class Tone { NORMAL, WARNING, SUCCESS }
}
