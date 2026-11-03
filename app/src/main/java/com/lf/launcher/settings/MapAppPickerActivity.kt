package com.lf.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lf.launcher.ui.ThemedActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lf.launcher.R
import com.lf.launcher.apps.AppEntry
import com.lf.launcher.apps.AppRepository
import com.lf.launcher.databinding.ActivityMapAppPickerBinding
import com.lf.launcher.home.MapAppLauncher

/**
 * 悬浮地图的应用选择器 —— 对应原版 settings_map 里的「当前地图」入口。
 * 选完即写入配置并关闭，回到设置页会刷新显示名。
 */
class MapAppPickerActivity : ThemedActivity() {

    private lateinit var binding: ActivityMapAppPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)

        binding = ActivityMapAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        binding.btnBack.setOnClickListener { finish() }
        binding.pickerCurrent.text = "当前：${MapAppLauncher.selectedLabel(this)}"

        val widthDp = resources.configuration.screenWidthDp
        val spanCount = (widthDp / 140).coerceIn(3, 8)

        binding.pickerGrid.layoutManager = GridLayoutManager(this, spanCount)
        binding.pickerGrid.itemAnimator = null
        binding.pickerGrid.adapter = PickerAdapter(AppRepository.loadLaunchable(this)) { entry ->
            SettingsStore.setString(Keys.MAP_APP, entry.packageName)
            SettingsStore.setString(Keys.MAP_APP_LABEL, entry.label)
            finish()
        }
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

    private class PickerAdapter(
        private val items: List<AppEntry>,
        private val onClick: (AppEntry) -> Unit,
    ) : RecyclerView.Adapter<PickerAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.label.text = entry.label
            holder.icon.setImageDrawable(entry.icon)
            val chosen = entry.packageName == MapAppLauncher.selectedPackage()
            holder.itemView.alpha = if (chosen) 1f else 0.86f
            holder.itemView.setOnClickListener { onClick(entry) }
        }
    }
}
