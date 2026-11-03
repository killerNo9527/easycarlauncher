package com.lf.launcher.settings

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lf.launcher.R
import com.lf.launcher.weather.WeatherApi
import com.lf.launcher.weather.WeatherImportServer
import com.lf.launcher.weather.WeatherStore

/**
 * 和风 Key 输入框。
 *
 * Key 存在这台车机上，App 自己和和风说话，没有中间服务。这是「API KEY」认证方式的入口；
 * 新项目只能走 JWT，那边是 [WeatherJwtDialog]。
 *
 * 表单里同时收 **API Host**：新版和风每个账号一个专属域名，光填 Key 会 403。
 * 留空的语义是「不改」，不是「清掉」—— 清掉会悄悄退回官方 devapi 域名。
 *
 * 手打当然是有的，但 32 位 Key 在车机软键盘上敲一遍、再核对一遍是很痛苦的事，
 * 所以和 JWT 那边一样提供了「用手机填」：车机起一个只活两分钟的局域网页面，
 * 手机浏览器上粘贴、点保存，车机直接收下。手机复制粘贴天然不会丢字符。
 *
 * ## 为什么保存时要顺手验证一次
 *
 * 和风的 Key 是 32 位字符，复制时少一位、多一个空格都很常见；Host 也容易填成网页控制台的域名。
 * 如果只存不验证，用户看到的只是天气卡上「离线」，压根不知道错在哪。所以这里保存后**立刻拉一次**：
 * 成功就把服务端返回的城市名回填、打开天气卡，失败就把和风的具体错误码翻译成人话留在框里。
 */
object WeatherKeyDialog {

    fun show(context: Context, onSaved: () -> Unit = {}) {
        val pad = dp(context, 20)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(context, 4), pad, dp(context, 4))
        }

        val hint = TextView(context).apply {
            text = "Key 只保存在这台车机上，不经过任何中间服务。\n" +
                "新控制台的 Key 还要填 API Host —— 在项目页顶部「API Host」那行复制，留空只有老账号能用"
            textSize = 13f
            setTextColor(context.getColor(R.color.text_tertiary))
            setPadding(0, 0, 0, dp(context, 10))
        }
        box.addView(hint, lp())

        val key = field(context, "和风 Key（32 位字符）", WeatherStore.qwKey())
        // 不用密码圆点：这里是要核对有没有粘贴错的，看得见比看不见有用
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        box.addView(key, lp())

        val host = field(context, "API Host（新账号必填，形如 abc.re.qweatherapi.com）", WeatherStore.qwHost())
        host.inputType = InputType.TYPE_TEXT_VARIATION_URI
        box.addView(host, lp())

        val city = field(context, "城市，如 北京 / 上海", WeatherStore.city())
        box.addView(city, lp())

        val status = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.warning))
            setPadding(0, dp(context, 6), 0, 0)
            visibility = TextView.GONE
        }
        box.addView(status, lp())

        val dialog = AlertDialog.Builder(context)
            .setTitle("和风直连")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNeutralButton("用手机填", null)
            .setNegativeButton("取消") { _, _ -> WeatherImportServer.stop() }
            .create()

        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val phone = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            /** 用**已落盘**的配置去请求一次；手打和手机导入两条路最后都走到这儿 */
            fun verify() {
                status.visibility = TextView.GONE
                save.isEnabled = false
                save.text = "验证中…"

                WeatherApi.fetchForVerify(context) { result ->
                    save.isEnabled = true
                    save.text = "保存"
                    result.onSuccess { w ->
                        WeatherStore.saveCache(w)
                        WeatherStore.setCity(w.city)
                        SettingsStore.setBool(Keys.CARD_WEATHER, true)
                        Toast.makeText(
                            context,
                            "已连上和风：${w.displayName} ${w.temp}° ${w.text}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        WeatherImportServer.stop()
                        dialog.dismiss()
                        onSaved()
                    }.onFailure { e ->
                        warn(status, e.message ?: "验证失败")
                    }
                }
            }

            fun submit() {
                val k = key.text.toString().trim()
                val h = host.text.toString().trim()
                val c = city.text.toString().trim()
                if (k.isEmpty()) return warn(status, "先填 Key")

                // 先落盘再验证：验证本身就走的这套配置
                WeatherStore.setAuth(WeatherStore.AUTH_KEY)
                WeatherStore.setQwKey(k)
                WeatherStore.setQwHost(h)
                WeatherStore.setCity(c.ifBlank { "北京" })
                verify()
            }

            save.setOnClickListener { submit() }

            phone.setOnClickListener {
                val url = WeatherImportServer.start(
                    context,
                    WeatherImportServer.Mode.KEY,
                    onSaved = {
                        // 手机上提交成功 → 把刚存下的值回填到框里（免得框里还是空的，看着像没成），
                        // 然后直接验证，用户不用再点一次保存
                        key.setText(WeatherStore.qwKey())
                        host.setText(WeatherStore.qwHost())
                        Toast.makeText(context, "Key 已写入车机，正在验证…", Toast.LENGTH_SHORT).show()
                        verify()
                    },
                    onError = { msg -> warn(status, "开不了局域网页面：$msg") },
                )
                if (url == null) {
                    warn(status, "拿不到局域网 IP，检查车机 Wi-Fi 是否连上")
                } else {
                    status.setTextColor(context.getColor(R.color.accent))
                    status.text = "手机浏览器打开：$url\n（两分钟内有效，保存成功后自动失效）"
                    status.visibility = TextView.VISIBLE
                }
            }

            // 键盘上的「完成」和车机的回车都当成保存
            city.imeOptions = EditorInfo.IME_ACTION_DONE
            city.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        dialog.setOnDismissListener { WeatherImportServer.stop() }
        dialog.show()
    }

    private fun warn(view: TextView, msg: String) {
        view.setTextColor(view.context.getColor(R.color.warning))
        view.text = msg
        view.visibility = TextView.VISIBLE
    }

    private fun field(context: Context, hintText: String, value: String): EditText =
        EditText(context).apply {
            hint = hintText
            setText(value)
            textSize = 15f
            setSingleLine()
        }

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
