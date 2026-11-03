package com.lf.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lf.launcher.R
import com.lf.launcher.apps.AppEntry
import com.lf.launcher.apps.AppRepository
import com.lf.launcher.databinding.ActivityShortcutPickerBinding
import com.lf.launcher.ui.ThemedActivity

/**
 * 状态栏左上角快捷应用的选择与排序页。
 *
 * 交互：上半是「已选」（按顺序，↑ / ↓ / ✕），下半是「全部应用」（点一下加、再点一下减）。
 * 每次改动立刻写盘 —— 没有「保存」按钮，右上角那个是「完成」，只是关页面。
 * 这样即使用户直接按 Home 键走人，改过的东西也不会丢。
 */
class ShortcutPickerActivity : ThemedActivity() {

    private lateinit var binding: ActivityShortcutPickerBinding

    /** 全部可启动应用，进页面时查一次（列表页不需要跟着装包实时变） */
    private lateinit var all: List<AppEntry>

    /** 当前已选，顺序即显示顺序；和 [StatusShortcuts] 里存的是同一份东西 */
    private val selected = mutableListOf<String>()

    private lateinit var adapter: AllAppsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)

        binding = ActivityShortcutPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        binding.btnBack.setOnClickListener { finish() }

        all = AppRepository.loadLaunchable(this)
        val installed = all.map { it.packageName }.toSet()
        // 已卸载 / 暂时查不到的包不在这里显示，但也**不主动清掉**配置：
        // 应用升级中被临时停用是常事，等到用户真的改了一次再落盘
        selected += StatusShortcuts.packages().filter { it in installed }

        val widthDp = resources.configuration.screenWidthDp
        val spanCount = (widthDp / 150).coerceIn(3, 8)
        binding.pickerAll.layoutManager = GridLayoutManager(this, spanCount)
        binding.pickerAll.itemAnimator = null
        adapter = AllAppsAdapter(all, selected) { entry -> toggle(entry) }
        binding.pickerAll.adapter = adapter

        renderSelected()
    }

    // ------------------------------------------------------------ 增删与排序

    private fun toggle(entry: AppEntry) {
        val index = selected.indexOf(entry.packageName)
        if (index >= 0) {
            selected.removeAt(index)
        } else {
            if (selected.size >= StatusShortcuts.MAX) {
                Toast.makeText(this, "最多 ${StatusShortcuts.MAX} 个", Toast.LENGTH_SHORT).show()
                return
            }
            selected += entry.packageName
        }
        persist()
    }

    private fun move(packageName: String, delta: Int) {
        val from = selected.indexOf(packageName)
        if (from < 0) return
        val to = from + delta
        if (to !in selected.indices) return
        selected.removeAt(from)
        selected.add(to, packageName)
        persist()
    }

    private fun remove(packageName: String) {
        if (selected.remove(packageName)) persist()
    }

    private fun persist() {
        StatusShortcuts.save(selected)
        renderSelected()
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- 渲染

    private fun renderSelected() {
        val box = binding.pickerSelected
        box.removeAllViews()

        binding.pickerSelectedCount.text = when {
            selected.isEmpty() -> "已选 0 / ${StatusShortcuts.MAX} · 还没选，点下面的应用添加"
            selected.size < StatusShortcuts.MIN ->
                "已选 ${selected.size} / ${StatusShortcuts.MAX} · 建议至少 ${StatusShortcuts.MIN} 个"
            else -> "已选 ${selected.size} / ${StatusShortcuts.MAX} · 顺序即从左到右"
        }

        if (selected.isEmpty()) {
            box.addView(emptyHint())
            return
        }

        val inflater = LayoutInflater.from(this)
        // 行与行之间留 6dp：行背景是透明的，不留缝五条会连成一片，看不出是几个应用
        val gap = (6 * resources.displayMetrics.density).toInt()
        selected.forEachIndexed { index, pkg ->
            val entry = all.firstOrNull { it.packageName == pkg } ?: return@forEachIndexed
            val row = inflater.inflate(R.layout.item_shortcut_selected, box, false)
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap }
            row.findViewById<TextView>(R.id.row_order).text = "${index + 1}"
            row.findViewById<ImageView>(R.id.row_icon).setImageDrawable(entry.icon)
            row.findViewById<TextView>(R.id.row_label).text = entry.label

            val up = row.findViewById<TextView>(R.id.row_up)
            val down = row.findViewById<TextView>(R.id.row_down)
            // 首行不能上移、末行不能下移：按钮留着但压暗，比"点了没反应"好懂
            up.alpha = if (index == 0) 0.3f else 1f
            down.alpha = if (index == selected.lastIndex) 0.3f else 1f
            up.setOnClickListener { move(pkg, -1) }
            down.setOnClickListener { move(pkg, 1) }
            row.findViewById<TextView>(R.id.row_remove).setOnClickListener { remove(pkg) }

            box.addView(row)
        }
    }

    private fun emptyHint(): View = TextView(this).apply {
        text = "还没有选择任何应用"
        setTextColor(getColor(R.color.text_tertiary))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.setting_desc_text_size))
        setPadding(0, resources.getDimensionPixelSize(R.dimen.setting_row_padding), 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun enterFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /** 全部应用网格：已选的加个 ✓ 前缀并压暗，避免和上半部分对不上号 */
    private class AllAppsAdapter(
        private val items: List<AppEntry>,
        /** 直接持有 Activity 里那个可变列表，改完 notifyDataSetChanged 就能重新判"已选" */
        private val selected: List<String>,
        private val onClick: (AppEntry) -> Unit,
    ) : RecyclerView.Adapter<AllAppsAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val chosen = selected.contains(entry.packageName)
            holder.label.text = if (chosen) "✓ ${entry.label}" else entry.label
            holder.label.setTextColor(
                holder.itemView.context.getColor(if (chosen) R.color.accent else R.color.app_label)
            )
            holder.icon.setImageDrawable(entry.icon)
            holder.itemView.alpha = if (chosen) 1f else 0.86f
            holder.itemView.setOnClickListener { onClick(entry) }
        }
    }
}
