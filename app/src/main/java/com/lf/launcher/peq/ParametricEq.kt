package com.lf.launcher.peq

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 参数化均衡器处理器。
 *
 * ## 设计要点（与 Poweramp 的形态对齐）
 * 1. **块处理**：每 [blockFrames] 帧为一个处理块，只有块边界才重算系数。
 *    Poweramp 设置里那个 "Block Size"（提示"频段多时需手动调大以获得更高频率分辨率"）
 *    就是同一件事——系数更新粒度。
 * 2. **增益平滑**：拖动滑块时目标值逐块逼近，避免系数突变造成 zipper noise
 *    （对应它的 "Smooth Equalizer/Tone Gains"）。
 * 3. **零增益旁路**：增益≈0 的频段直接跳过，多频段时省 CPU。
 *
 * ## 线程模型（重要）
 * [applyProfile] 可从**任意线程**调用（通常是 UI 线程），它只把配置丢进一个 volatile 槽位；
 * 真正的切换发生在下一次 [process] 的块边界。所以 `process` 与 `applyProfile` 并发是安全的。
 * 但 [currentBands] / [preampDb] 这类 getter 只适合**读来显示**，不要拿它做 DSP 判断。
 *
 * ## 两种 PCM 入口
 * - [processPcm16]：交错 16-bit PCM（Android `AudioTrack` 绝大多数用法），内部转浮点处理。
 * - [process]：交错 32-bit float PCM（`ENCODING_PCM_FLOAT`），零转换开销。
 *
 * 用法：
 * ```kotlin
 * val eq = ParametricEq(sampleRateHz = 48000, channelCount = 2)
 * eq.applyProfile(profile)          // 任意线程
 * eq.processPcm16(pcm, frames)      // 音频线程
 * ```
 */
class ParametricEq(
    val sampleRateHz: Int,
    val channelCount: Int,
    /** 处理块大小（帧）。256~2048；频段越多、要求分辨率越高就调大 */
    val blockFrames: Int = 512,
    private val maxBands: Int = 32,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz 必须 > 0" }
        require(channelCount in 1..8) { "channelCount 支持 1..8" }
        require(blockFrames in 16..8192) { "blockFrames 支持 16..8192" }
        require(maxBands in 1..64) { "maxBands 支持 1..64" }
    }

    /** 当前**已生效**（平滑到位）的频段，仅供读取显示 */
    var currentBands: List<EqBand> = emptyList()
        private set

    /** 目标频段，仅供读取显示 */
    var targetBands: List<EqBand> = emptyList()
        private set

    /** 当前生效的前级增益（dB），仅供读取显示 */
    var preampDb: Float = 0f
        private set

    /** 每块把当前值向目标推进的比例，0.35 ≈ 20~30ms 收敛，听感自然 */
    @Volatile
    var smoothingFactor: Float = 0.35f

    /** 跨线程投递的待生效配置 */
    @Volatile
    private var pending: EqProfile? = null

    private var currentPreampDb = 0f
    private var targetPreampDb = 0f

    // [band][channel] 系数
    private var coeffs: Array<Array<Biquad>> = emptyArray()
    // [band][channel][4] = x1, x2, y1, y2
    private var states: Array<Array<FloatArray>> = emptyArray()
    private var active: BooleanArray = BooleanArray(0)
    private var dirty = true

    // PCM16 转换暂存区，避免逐帧分配
    private var scratch = FloatArray(0)

    /**
     * 应用新的频段配置。可在任意线程调用；实际切换发生在下一个块边界。
     * @throws IllegalArgumentException 频段数超过 [maxBands]
     */
    fun applyProfile(profile: EqProfile) {
        require(profile.bands.size <= maxBands) {
            "频段数 ${profile.bands.size} 超过上限 $maxBands"
        }
        pending = profile
    }

    /** 重置为平坦（全部 0dB）。同样在块边界生效。 */
    fun reset() {
        pending = EqProfile.flat()
    }

    /**
     * 处理交错 **float** PCM（范围 [-1, 1]），就地覆盖。
     * @param buffer 长度 >= frames * [channelCount]
     */
    fun process(buffer: FloatArray, frames: Int) {
        require(frames >= 0) { "frames 不能为负" }
        require(buffer.size >= frames * channelCount) {
            "buffer 长度 ${buffer.size} 不足 ${frames * channelCount}"
        }
        drainPending()
        var offset = 0
        var remaining = frames
        while (remaining > 0) {
            val n = minOf(blockFrames, remaining)
            if (dirty) {
                smoothAndDesign()
                dirty = false
            }
            processBlock(buffer, offset, n)
            offset += n * channelCount
            remaining -= n
        }
        if (needsMoreSmoothing()) dirty = true
    }

    /**
     * 处理交错 **16-bit** PCM，就地覆盖。
     *
     * 短整型与浮点间用 32768 换算（2 的幂，二进制浮点下往返**精确无损**），
     * 因此配置为空时能保证 bit-exact 直通。
     *
     * @param pcm 长度 >= frames * [channelCount]
     */
    fun processPcm16(pcm: ShortArray, frames: Int) {
        require(frames >= 0) { "frames 不能为负" }
        val need = frames * channelCount
        require(pcm.size >= need) { "pcm 长度 ${pcm.size} 不足 $need" }
        if (scratch.size < need) scratch = FloatArray(need)
        for (i in 0 until need) scratch[i] = pcm[i] / 32768f
        process(scratch, frames)
        for (i in 0 until need) {
            val v = (scratch[i] * 32768f).roundToInt()
            pcm[i] = if (v > 32767) 32767 else if (v < -32768) -32768 else v.toShort()
        }
    }

    /** 清空滤波器状态（切歌/切输出设备时调用，避免残留瞬态） */
    fun resetState() {
        for (b in states) for (ch in b) ch.fill(0f)
    }

    /** 取走待生效配置（音频线程调用） */
    private fun drainPending() {
        val p = pending ?: return
        pending = null
        // 首次设置直接到位，避免启动时从平坦态"滑"下来
        if (currentBands.isEmpty() && targetBands.isEmpty()) {
            currentBands = p.bands
            currentPreampDb = p.preampDb
            preampDb = p.preampDb
        }
        targetBands = p.bands
        targetPreampDb = p.preampDb
        dirty = true
    }

    private fun needsMoreSmoothing(): Boolean {
        if (abs(currentPreampDb - targetPreampDb) > 0.01f) return true
        if (currentBands.size != targetBands.size) return true
        for (i in targetBands.indices) {
            val c = currentBands[i]
            val t = targetBands[i]
            if (abs(c.gainDb - t.gainDb) > 0.01f) return true
            if (c.type != t.type) return true
            if (abs(c.freqHz - t.freqHz) > 0.5f) return true
            if (abs(c.q - t.q) > 0.005f) return true
            if (c.enabled != t.enabled) return true
        }
        return false
    }

    private fun smoothAndDesign() {
        val smooth = { cur: Float, tgt: Float ->
            if (abs(tgt - cur) < 0.01f) tgt else cur + (tgt - cur) * smoothingFactor
        }

        // 频段数量变化不做插值，直接替换（增删频段本身就该是瞬时的）
        currentBands = if (currentBands.size != targetBands.size) {
            targetBands
        } else {
            targetBands.mapIndexed { i, tgt ->
                val cur = currentBands[i]
                tgt.copy(
                    gainDb = smooth(cur.gainDb, tgt.gainDb),
                    freqHz = if (abs(cur.freqHz - tgt.freqHz) < 0.5f) tgt.freqHz
                    else cur.freqHz + (tgt.freqHz - cur.freqHz) * smoothingFactor,
                    q = if (abs(cur.q - tgt.q) < 0.005f) tgt.q
                    else cur.q + (tgt.q - cur.q) * smoothingFactor,
                )
            }
        }
        currentPreampDb = smooth(currentPreampDb, targetPreampDb)
        preampDb = currentPreampDb

        val bandCount = currentBands.size
        if (coeffs.size != bandCount) {
            coeffs = Array(bandCount) { Array(channelCount) { Biquad.BYPASS } }
            states = Array(bandCount) { Array(channelCount) { FloatArray(4) } }
            active = BooleanArray(bandCount)
        }
        for (i in 0 until bandCount) {
            val band = currentBands[i]
            var anyActive = false
            for (ch in 0 until channelCount) {
                val c = if (!band.enabled) Biquad.BYPASS
                else Biquad.designOrBypass(
                    band.type, sampleRateHz, band.freqHz, band.q, band.effectiveGainDb(ch)
                )
                coeffs[i][ch] = c
                if (c !== Biquad.BYPASS) anyActive = true
            }
            active[i] = anyActive
        }
    }

    private fun processBlock(buffer: FloatArray, offset: Int, frames: Int) {
        val preamp = if (abs(currentPreampDb) < 0.01f) 1f
        else Math.pow(10.0, currentPreampDb / 20.0).toFloat()
        val bandCount = coeffs.size

        var anyActive = false
        for (i in 0 until bandCount) if (active[i]) { anyActive = true; break }

        for (ch in 0 until channelCount) {
            val chOffset = offset + ch
            if (!anyActive) {
                if (preamp != 1f) {
                    var idx = chOffset
                    for (f in 0 until frames) {
                        buffer[idx] *= preamp
                        idx += channelCount
                    }
                }
                continue
            }
            for (f in 0 until frames) {
                val idx = chOffset + f * channelCount
                var s = buffer[idx] * preamp
                for (i in 0 until bandCount) {
                    if (!active[i]) continue
                    val c = coeffs[i][ch]
                    val st = states[i][ch]
                    val x1 = st[0]
                    val x2 = st[1]
                    val y1 = st[2]
                    val y2 = st[3]
                    // Direct Form I
                    val y = c.b0 * s + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
                    st[0] = s
                    st[1] = x1
                    st[2] = y
                    st[3] = y1
                    s = y
                }
                buffer[idx] = s
            }
        }
    }

    /** 每帧每声道的近似乘加次数，用于评估频段数上限 */
    fun estimateOpsPerFrame(): Int = currentBands.count { it.enabled } * 5 * channelCount

    /** 建议的前级 headroom（dB，<=0）：抵消所有正增益之和，避免削顶 */
    fun suggestedHeadroomDb(): Float {
        var sum = 0f
        for (b in currentBands) if (b.enabled) sum += max(0f, b.gainDb)
        return -sum.coerceAtMost(24f)
    }
}
