package com.lf.launcher.settings

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lf.launcher.R
import com.lf.launcher.weather.QWeatherJwt
import com.lf.launcher.weather.WeatherApi
import com.lf.launcher.weather.WeatherImportServer
import com.lf.launcher.weather.WeatherStore

/**
 * 和风 JWT 凭据：Project ID / Key ID / 私钥。
 *
 * ## 主路径是「从手机导入」，不是手打
 *
 * 私钥是一整段 PEM，在车机软键盘上输入不现实。所以这个对话框一打开就有个
 * 「用手机填」按钮：车机起一个只活两分钟的局域网页面，手机上粘贴、提交，车机直接收下。
 * 手动那两个输入框是给「只有 Project ID 写错了要改一个字符」这种情况留的。
 *
 * 保存后立刻拉一次天气验证 —— 和 API KEY 那条路一样，错在哪儿当场说清楚，
 * 而不是等卡片上出现一个「离线」让人干猜。
 */
object WeatherJwtDialog {

    fun show(context: Context, onSaved: () -> Unit = {}) {
        val pad = dp(context, 20)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(context, 4), pad, dp(context, 4))
        }

        val hint = TextView(context).apply {
            text = "私钥只存这台车机。先点下面「用手机填」，比在车机上打字快得多"
            textSize = 13f
            setTextColor(context.getColor(R.color.text_tertiary))
            setPadding(0, 0, 0, dp(context, 10))
        }
        box.addView(hint, lp())

        val project = field(context, "Project ID", WeatherStore.jwtProjectId())
        box.addView(project, lp())

        val keyId = field(context, "Key ID", WeatherStore.jwtKeyId())
        box.addView(keyId, lp())

        val pem = field(context, "私钥（-----BEGIN PRIVATE KEY----- 整段）", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 3
            maxLines = 5
            textSize = 12f
            typeface = Typeface.MONOSPACE
            if (WeatherStore.jwtSeed() != null) {
                // 已经导入过就不回显私钥（没必要把秘密一直摊在屏幕上），只标一句。
                // 用 this.hint 而不是 hint：外层那个同名变量是上面那句提示的 TextView
                this.hint = "已经导入过。要换就整段重新粘贴"
            }
        }
        box.addView(pem, lp())

        val status = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.accent))
            setPadding(0, dp(context, 8), 0, 0)
            visibility = TextView.GONE
        }
        box.addView(status, lp())

        val dialog = AlertDialog.Builder(context)
            .setTitle("和风 JWT")
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
                        Toast.makeText(context, "JWT 可用：${w.displayName} ${w.temp}° ${w.text}", Toast.LENGTH_SHORT).show()
                        WeatherImportServer.stop()
                        dialog.dismiss()
                        onSaved()
                    }.onFailure { e ->
                        warn(status, e.message ?: "验证失败", context)
                    }
                }
            }

            fun submit() {
                val p = project.text.toString().trim()
                val k = keyId.text.toString().trim()
                val raw = pem.text.toString()

                if (p.isEmpty()) return warn(status, "先填 Project ID", context)
                if (k.isEmpty()) return warn(status, "先填 Key ID", context)

                // 已经存过私钥、这次又没重贴 → 沿用手上那份
                val seed = if (raw.isBlank()) WeatherStore.jwtSeed() else QWeatherJwt.parseSeed(raw)
                if (seed == null || seed.size != 32) {
                    return warn(status, "私钥没认出来，要整段 PEM（或 32 字节 Base64）", context)
                }

                WeatherStore.setAuth(WeatherStore.AUTH_JWT)
                WeatherStore.saveJwt(p, k, seed)
                verify()
            }

            save.setOnClickListener { submit() }

            phone.setOnClickListener {
                val url = WeatherImportServer.start(
                    context,
                    WeatherImportServer.Mode.JWT,
                    onSaved = {
                        // 手机上提交成功 → 把刚落盘的两项回填到框里（框里还是空的会让人以为没成），
                        // 再直接验证。私钥栏保持留白：已经导入过就不该再把秘密摊在屏幕上。
                        project.setText(WeatherStore.jwtProjectId())
                        keyId.setText(WeatherStore.jwtKeyId())
                        Toast.makeText(context, "私钥已写入车机，正在验证…", Toast.LENGTH_SHORT).show()
                        verify()
                    },
                    onError = { msg ->
                        warn(status, "开不了局域网页面：$msg", context)
                    },
                )
                if (url == null) {
                    warn(status, "拿不到局域网 IP，检查车机 Wi-Fi 是否连上", context)
                } else {
                    status.setTextColor(context.getColor(R.color.accent))
                    status.text = "手机浏览器打开：$url\n（两分钟内有效，保存成功后自动失效）"
                    status.visibility = TextView.VISIBLE
                }
            }
        }
        dialog.setOnDismissListener { WeatherImportServer.stop() }
        dialog.show()
    }

    private fun warn(view: TextView, msg: String, context: Context) {
        view.setTextColor(context.getColor(R.color.warning))
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
