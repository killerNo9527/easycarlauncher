package com.lf.launcher.peq

import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一份 EQ 配置：频段列表 + 前级增益。
 *
 * 可直接序列化成 JSON 存 SharedPreferences / 文件，对应 Poweramp 的预设（preset）概念，
 * 也是"自定义频段"能被保存下来的前提。
 */
data class EqProfile(
    val name: String = "Custom",
    val bands: List<EqBand> = emptyList(),
    val preampDb: Float = 0f,
) {
    val isEmpty: Boolean get() = bands.isEmpty()

    fun withBand(band: EqBand): EqProfile = copy(bands = bands + band)

    fun withoutBand(index: Int): EqProfile =
        if (index !in bands.indices) this else copy(bands = bands.filterIndexed { i, _ -> i != index })

    fun updateBand(index: Int, transform: (EqBand) -> EqBand): EqProfile =
        if (index !in bands.indices) this
        else copy(bands = bands.mapIndexed { i, b -> if (i == index) transform(b) else b })

    fun toJson(): String {
        val arr = JSONArray()
        bands.forEach { b ->
            arr.put(
                JSONObject()
                    .put("type", b.type.name)
                    .put("freq", b.freqHz.toDouble())
                    .put("q", b.q.toDouble())
                    .put("gain", b.gainDb.toDouble())
                    .put("enabled", b.enabled)
                    .put("name", b.name)
                    .put("mask", b.channelMask)
            )
        }
        return JSONObject()
            .put("name", name)
            .put("preamp", preampDb.toDouble())
            .put("bands", arr)
            .toString()
    }

    companion object {
        fun fromJson(json: String): EqProfile {
            val o = JSONObject(json)
            val arr = o.optJSONArray("bands") ?: JSONArray()
            val bands = ArrayList<EqBand>(arr.length())
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                bands += EqBand(
                    type = runCatching { BandType.valueOf(b.optString("type", "PEAK")) }
                        .getOrDefault(BandType.PEAK),
                    freqHz = b.optDouble("freq", 1000.0).toFloat(),
                    q = b.optDouble("q", 1.41).toFloat(),
                    gainDb = b.optDouble("gain", 0.0).toFloat(),
                    enabled = b.optBoolean("enabled", true),
                    name = b.optString("name", ""),
                    channelMask = b.optInt("mask", 0),
                )
            }
            return EqProfile(
                name = o.optString("name", "Custom"),
                bands = bands,
                preampDb = o.optDouble("preamp", 0.0).toFloat(),
            )
        }

        /** 平坦配置 */
        fun flat() = EqProfile("Flat", emptyList(), 0f)

        /**
         * 图形模式（Graphic mode）：生成 [bandCount] 个对数等间距的峰值频段。
         *
         * 对应 Poweramp 的 "Custom Number of Bands" + "First Frequency" / "Last Frequency"：
         * 频段数可在 5~32 之间自定义，首末频率可配，中间按对数均匀分布。
         * 之所以用对数分布而不是线性，是为了让每个频段覆盖相同的倍频程宽度，听感上"等宽"。
         *
         * @param bandCount 频段数，建议 5~32
         * @param firstHz  第一个频段中心频率
         * @param lastHz   最后一个频段中心频率
         */
        fun graphic(
            bandCount: Int = 10,
            firstHz: Float = 31.5f,
            lastHz: Float = 16000f,
            q: Float = 1.41f,
        ): EqProfile {
            require(bandCount in 1..64) { "bandCount 建议 5~32" }
            require(firstHz > 0f && lastHz > firstHz) { "需要 firstHz < lastHz" }
            val bands = ArrayList<EqBand>(bandCount)
            if (bandCount == 1) {
                bands += EqBand(BandType.PEAK, firstHz, q, 0f)
            } else {
                val ratio = lastHz / firstHz
                for (i in 0 until bandCount) {
                    // 对数插值：f_i = f_first * (f_last/f_first)^(i/(n-1))
                    val t = i.toDouble() / (bandCount - 1)
                    val f = (firstHz * ratio.toDouble().pow(t)).toFloat()
                    bands += EqBand(BandType.PEAK, f, q, 0f)
                }
            }
            return EqProfile("Graphic $bandCount", bands, 0f)
        }

        /**
         * 参量模式（Parametric mode）的初始配置：低频架 + 几个可拖的峰值 + 高频架。
         * 对应 Poweramp "each band is added and configured manually" 的起手式。
         */
        fun parametricStarter(): EqProfile = EqProfile(
            name = "Parametric",
            bands = listOf(
                EqBand(BandType.LOW_SHELF, 80f, 0.707f, 0f, name = "Low Shelf"),
                EqBand(BandType.PEAK, 250f, 1.41f, 0f, name = "Band 2"),
                EqBand(BandType.PEAK, 1000f, 1.41f, 0f, name = "Band 3"),
                EqBand(BandType.PEAK, 4000f, 1.41f, 0f, name = "Band 4"),
                EqBand(BandType.HIGH_SHELF, 10000f, 0.707f, 0f, name = "High Shelf"),
            ),
            preampDb = 0f,
        )
    }
}
