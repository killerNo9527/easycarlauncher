package com.lf.launcher.settings

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lf.launcher.R

class SettingsAdapter(
    items: List<SettingItem>,
    private val onChanged: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items: MutableList<SettingItem> = items.toMutableList()

    /** 设置项里有「当前地图」这类需要重新读取的显示值，从子页面返回时整体刷新 */
    fun submit(newItems: List<SettingItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    companion object {
        private const val T_SECTION = 0
        private const val T_SWITCH = 1
        private const val T_CHOICE = 2
        private const val T_INFO = 3
        private const val T_ACTION = 4
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingItem.Section -> T_SECTION
        is SettingItem.SwitchItem -> T_SWITCH
        is SettingItem.ChoiceItem -> T_CHOICE
        is SettingItem.InfoItem -> T_INFO
        is SettingItem.ActionItem -> T_ACTION
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            T_SECTION -> SectionHolder(inflater.inflate(R.layout.item_setting_section, parent, false))
            T_SWITCH -> SwitchHolder(inflater.inflate(R.layout.item_setting_switch, parent, false), onChanged)
            T_CHOICE -> ChoiceHolder(inflater.inflate(R.layout.item_setting_choice, parent, false), onChanged)
            T_ACTION -> ChoiceHolder(inflater.inflate(R.layout.item_setting_choice, parent, false), onChanged)
            else -> InfoHolder(inflater.inflate(R.layout.item_setting_info, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingItem.Section -> (holder as SectionHolder).bind(item)
            is SettingItem.SwitchItem -> (holder as SwitchHolder).bind(item)
            is SettingItem.ChoiceItem -> (holder as ChoiceHolder).bindChoice(item)
            is SettingItem.InfoItem -> (holder as InfoHolder).bind(item)
            is SettingItem.ActionItem -> (holder as ChoiceHolder).bindAction(item)
        }
    }

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        fun bind(item: SettingItem.Section) {
            title.text = item.title
        }
    }

    class SwitchHolder(view: View, private val onChanged: () -> Unit) :
        RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.item_title)
        private val desc: TextView = view.findViewById(R.id.item_desc)
        private val toggle: Switch = view.findViewById(R.id.item_switch)
        private var key: String? = null
        private var inverted = false
        private var item: SettingItem.SwitchItem? = null

        init {
            toggle.setOnCheckedChangeListener { _, checked ->
                key?.let { k ->
                    SettingsStore.setBool(k, if (inverted) !checked else checked)
                    item?.onToggle?.invoke(checked)
                    onChanged()
                }
            }
            view.setOnClickListener { toggle.toggle() }
        }

        fun bind(item: SettingItem.SwitchItem) {
            key = null
            this.item = item
            title.text = item.title
            desc.text = item.desc ?: ""
            desc.visibility = if (item.desc.isNullOrBlank()) View.GONE else View.VISIBLE
            inverted = item.inverted
            val stored = SettingsStore.getBool(item.key, item.default)
            toggle.isChecked = if (item.inverted) !stored else stored
            toggle.isEnabled = item.ready
            itemView.alpha = if (item.ready) 1f else 0.55f
            key = item.key
        }
    }

    class ChoiceHolder(view: View, private val onChanged: () -> Unit) :
        RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.item_title)
        private val desc: TextView = view.findViewById(R.id.item_desc)
        private val value: TextView = view.findViewById(R.id.item_value)

        fun bindChoice(item: SettingItem.ChoiceItem) {
            title.text = item.title
            desc.text = item.desc ?: ""
            desc.visibility = if (item.desc.isNullOrBlank()) View.GONE else View.VISIBLE

            val raw = SettingsStore.getString(item.key, item.default)
            // 旧版本可能存着已经删掉的选项（classic / music_card …），一律落到默认项，
            // 否则右侧会显示一串原始 key，而且弹窗里没有任何一项被选中
            val current = if (item.options.any { it.first == raw }) raw else item.default
            val label = item.options.firstOrNull { it.first == current }?.second ?: current
            value.text = if (item.ready) label else "$label（待接入）"
            itemView.alpha = if (item.ready) 1f else 0.55f

            itemView.setOnClickListener {
                if (!item.ready) return@setOnClickListener
                val ctx: Context = itemView.context
                val names = item.options.map { it.second }.toTypedArray()
                val checked = item.options.indexOfFirst { it.first == current }.coerceAtLeast(0)
                android.app.AlertDialog.Builder(ctx)
                    .setTitle(item.title)
                    .setSingleChoiceItems(names, checked) { dialog, which ->
                        SettingsStore.setString(item.key, item.options[which].first)
                        dialog.dismiss()
                        onChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        fun bindAction(item: SettingItem.ActionItem) {
            title.text = item.title
            desc.text = item.desc ?: ""
            desc.visibility = if (item.desc.isNullOrBlank()) View.GONE else View.VISIBLE
            value.text = item.value ?: "›"
            itemView.alpha = 1f
            itemView.setOnClickListener { item.action() }
        }
    }

    class InfoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.item_title)
        private val value: TextView = view.findViewById(R.id.item_value)
        fun bind(item: SettingItem.InfoItem) {
            title.text = item.title
            value.text = item.value
        }
    }
}
