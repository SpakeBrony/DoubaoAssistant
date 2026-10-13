package com.spake.brony.doubaoassistant.infrared

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.conversation.AiOperationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 红外只发送电视指令，无法确认电视的实际电源状态。 */
internal class TvIrController(context: Context) {
    private val appContext = context.applicationContext

    suspend fun setPower(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ir = appContext.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir?.hasIrEmitter() != true) {
            Log.w("TvIr", "设备不支持红外功能")
            throw AiOperationFailure(R.string.tv_ir_unavailable)
        }
        val pattern = if (enabled) TvIrCodes.powerOnPattern else TvIrCodes.powerOffPattern
        if (pattern.isEmpty()) throw AiOperationFailure(R.string.tv_ir_not_configured)
        // Android 要求正数微秒序列、总时长小于 2 秒；不接受模型生成的原始红外码。
        if (TvIrCodes.carrierFrequency <= 0 || pattern.any { it <= 0 } ||
            pattern.sumOf { it.toLong() } >= 2_000_000L) throw AiOperationFailure(R.string.tv_ir_invalid_code)
        val ranges = ir.carrierFrequencies ?: return@withContext false
        if (ranges.none { TvIrCodes.carrierFrequency in it.minFrequency..it.maxFrequency })
            throw AiOperationFailure(R.string.tv_ir_frequency_unsupported)
        currentCoroutineContext().ensureActive()
        ir.transmit(TvIrCodes.carrierFrequency, pattern)
        true // 只代表信号发送完成，电视是否响应由用户确认。
    }
}

internal object TvIrCodes {
    const val carrierFrequency = 40_000 // Sony SIRC，单位 Hz。
    // Sony12，设备地址 1，独立开机 46 / 关机 47；单位微秒，每帧 45 ms，发送三帧。
    // 码源：https://github.com/probonopd/irdb/blob/master/codes/Sony/TV/1,-1.csv
    // shortcut: 默认使用索尼电视码；具体型号不响应时需替换为匹配码。
    val powerOnPattern = intArrayOf(
        2400, 600,
        600, 600, 1200, 600, 1200, 600, 1200, 600,
        600, 600, 1200, 600, 600, 600,
        1200, 600, 600, 600, 600, 600, 600, 600, 600, 25200
    ).let { it + it + it }
    val powerOffPattern = intArrayOf(
        2400, 600,
        1200, 600, 1200, 600, 1200, 600, 1200, 600,
        600, 600, 1200, 600, 600, 600,
        1200, 600, 600, 600, 600, 600, 600, 600, 600, 24600
    ).let { it + it + it }
}
