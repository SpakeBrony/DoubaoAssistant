package com.spake.brony.doubaoassistant.car

import android.content.Context
import android.util.Log
import com.spake.brony.doubaoassistant.conversation.CarOperations
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.round

/** 使用车机系统提供的 ECARX API；手机缺少该 API 时返回失败，不打包占位 SDK。 */
internal class GeelyHvacController(
    context: Context,
    private val loadClass: (String) -> Class<*> = context.classLoader::loadClass,
) : CarOperations {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    override suspend fun setTemperature(temperature: Float, seat: String): Boolean {
        if (!temperature.isFinite()) return false
        return changeTemperature(temperature, seat, 0f)
    }

    // 相对调温读取对应座位的当前设置温度，再升降；不猜目标温度。
    override suspend fun adjustTemperature(direction: String, amount: Float, seat: String): Boolean {
        if (direction !in listOf("increase", "decrease") || !amount.isFinite() || amount <= 0f) return false
        return changeTemperature(null, seat, if (direction == "increase") amount else -amount)
    }

    private suspend fun changeTemperature(temperature: Float?, seat: String, adjustment: Float): Boolean {
        val zone = when (seat) {
            "driver" -> 1 // 前排左侧，即主驾。
            "passenger" -> 4 // 前排右侧，即副驾。
            else -> return false
        }
        return withConnection("temperature=$temperature adjustment=$adjustment zone=$zone") { function, functionApi ->
            val get = functionApi.getMethod("getCustomizeFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            fun read(property: Int, area: Int): Float = (get.invoke(function, property, area) as Number).toFloat()

            // 原项目从车机读取温度上下限和步长，不为不同车型硬编码 16～32 度。
            var min: Float
            var max: Float
            var step: Float
            do {
                min = read(TEMP_MIN, GLOBAL_ZONE)
                max = read(TEMP_MAX, GLOBAL_ZONE)
                step = read(TEMP_STEP, GLOBAL_ZONE)
                val ready = min.isFinite() && max.isFinite() && step.isFinite() && min > 0 && max > min && step > 0
                if (!ready) delay(100)
            } while (!ready)
            val target = temperature ?: run {
                val current = read(TEMP, zone)
                if (!current.isFinite() || current !in min..max) return@withConnection false
                current + adjustment
            }
            val steps = (target - min) / step
            if (!target.isFinite() || target !in min..max || !steps.isFinite() || abs(steps - round(steps)) > 0.001f) {
                Log.w(TAG, "Rejected temperature=$target; range=$min..$max step=$step zone=$zone")
                return@withConnection false
            }

            currentCoroutineContext().ensureActive()
            val set = functionApi.getMethod("setCustomizeFunctionValue", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val accepted = set.invoke(function, TEMP, zone, target) == true
            Log.i(TAG, "Set temperature=$target zone=$zone accepted=$accepted")
            if (!accepted) return@withConnection false

            // SDK 接受请求还不代表温度已改变；回读同一区域后才播放成功文案。
            while (true) {
                currentCoroutineContext().ensureActive()
                val actual = read(TEMP, zone)
                if (actual.isFinite() && abs(actual - target) < 0.05f) {
                    Log.i(TAG, "Confirmed temperature=$actual zone=$zone")
                    break
                }
                delay(100)
            }
            true
        }
    }

    // 对应原项目 globalPowerClick/globalPowerOff：空调总开关使用全局区域、整数 1/0。
    override suspend fun setAcPower(enabled: Boolean): Boolean = setSwitch(POWER, GLOBAL_ZONE, enabled)

    // 对应原项目 cycleClick：直接设置目标内/外循环，不能用“切换下一种模式”代替。
    override suspend fun setCirculation(mode: String): Boolean {
        val value = when (mode) {
            "internal" -> CIRCULATION_INNER
            "external" -> CIRCULATION_OUTSIDE
            else -> return false
        }
        return withConnection("circulation=$mode") { function, functionApi ->
            val supported = functionApi.getMethod("getSupportedFunctionValue", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(function, CIRCULATION, GLOBAL_ZONE) as? IntArray
            if (supported == null || value !in supported) return@withConnection false
            setIntegerAndConfirm(function, functionApi, CIRCULATION, GLOBAL_ZONE, value)
        }
    }

    // AC 制冷使用全局区域，不修改空调总电源或 AUTO 开关。
    override suspend fun setCooling(enabled: Boolean): Boolean = setSwitch(AC, GLOBAL_ZONE, enabled)

    // 对应原项目 autoGlobalClick：AUTO 属性使用前排区域 1。
    override suspend fun setAutoMode(enabled: Boolean): Boolean = setSwitch(AUTO, 1, enabled)

    // 对应原项目 behindDefrostClick：后窗电加热/后除霜使用全局区域，回读确认开关状态。
    override suspend fun setRearWindowHeating(enabled: Boolean): Boolean = setSwitch(REAR_DEFROST, GLOBAL_ZONE, enabled)

    // 前排共享风速：保持当前手动/AUTO 模式，手动 1～9 档，AUTO 1～5 档。
    override suspend fun setFanSpeed(level: Int): Boolean {
        if (level !in 1..9) return false
        return changeFanSpeed(level, 0)
    }

    // 相对调档先读取当前档位；不猜档位，不越界，也不切换 AUTO 模式。
    override suspend fun adjustFanSpeed(direction: String, amount: Int): Boolean {
        if (direction !in listOf("increase", "decrease") || amount !in 1..8) return false
        return changeFanSpeed(null, if (direction == "increase") amount else -amount)
    }

    private suspend fun changeFanSpeed(level: Int?, adjustment: Int): Boolean =
        withConnection("fan level=$level adjustment=$adjustment") { function, functionApi ->
            val get = functionApi.getMethod("getFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val autoValue = get.invoke(function, FAN_AUTO, FRONT_ZONE) as Int
            // 原项目 isFanAuto：自动风速属性含有效档位时才使用自动风速接口。
            val automatic = autoValue in FAN_AUTO + 1..FAN_AUTO + 5
            val property = if (automatic) FAN_AUTO else FAN
            // AUTO 的界面档位顺序不是属性值顺序：1～5 档依次编码为 04、01、02、03、05。
            val values = if (automatic) intArrayOf(FAN_AUTO + 4, FAN_AUTO + 1, FAN_AUTO + 2, FAN_AUTO + 3, FAN_AUTO + 5)
                else IntArray(9) { FAN + it + 1 }
            val targetLevel = if (level != null) level else {
                val current = if (automatic) autoValue else get.invoke(function, FAN, FRONT_ZONE) as Int
                val index = values.indexOf(current)
                if (index < 0) return@withConnection false
                index + 1 + adjustment
            }
            if (targetLevel !in 1..values.size) return@withConnection false
            val value = values[targetLevel - 1]
            // 同时核对本车固件支持的编码，不能将 SDK 的最高档位当成本车能力。
            val supported = functionApi.getMethod("getSupportedFunctionValue", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(function, property, FRONT_ZONE) as? IntArray
            if (supported == null || value !in supported) return@withConnection false
            setIntegerAndConfirm(function, functionApi, property, FRONT_ZONE, value)
        }

    // 手动座椅通风：0 关闭，1～3 为档位，使用通风专用属性与编码。
    override suspend fun setSeatVentilation(seat: String, level: Int): Boolean =
        setSeatLevel(seat, level, SEAT_VENTILATION, SEAT_VENTILATION_AUTO)

    // 手动座椅加热：0 关闭，1～3 为档位，使用加热专用属性与编码。
    override suspend fun setSeatHeating(seat: String, level: Int): Boolean =
        setSeatLevel(seat, level, SEAT_HEATING, SEAT_HEATING_AUTO)

    private suspend fun setSeatLevel(seat: String, level: Int, property: Int, autoProperty: Int): Boolean {
        val zone = when (seat) {
            "driver" -> 1
            "passenger" -> 4
            else -> return false
        }
        if (level !in 0..3) return false
        return withConnection("seat property=0x${property.toString(16)} zone=$zone level=$level") { function, functionApi ->
            val get = functionApi.getMethod("getFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            // 原项目手动调档前取消该座位对应的自动加热/通风，避免自动模式覆盖手动档位。
            if (get.invoke(function, autoProperty, zone) == 1 &&
                !setIntegerAndConfirm(function, functionApi, autoProperty, zone, 0)) return@withConnection false
            // 关闭编码是 0，非零档位为“属性 ID + 档位”，不能直接写 1～3。
            val value = if (level == 0) 0 else property + level
            setIntegerAndConfirm(function, functionApi, property, zone, value)
        }
    }

    private suspend fun setSwitch(property: Int, zone: Int, enabled: Boolean): Boolean =
        withConnection("switch=0x${property.toString(16)} zone=$zone enabled=$enabled") { function, functionApi ->
            // 原项目 bool2AdaptInt 直接将 true/false 转为整数 1/0。
            val value = if (enabled) 1 else 0
            setIntegerAndConfirm(function, functionApi, property, zone, value)
        }

    private suspend fun setIntegerAndConfirm(function: Any, functionApi: Class<*>, property: Int, zone: Int, value: Int): Boolean {
        val set = functionApi.getMethod("setFunctionValue", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val get = functionApi.getMethod("getFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        currentCoroutineContext().ensureActive()
        val accepted = set.invoke(function, property, zone, value) == true
        Log.i(TAG, "Set property=0x${property.toString(16)} zone=$zone value=$value accepted=$accepted")
        if (!accepted) return false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (get.invoke(function, property, zone) == value) {
                Log.i(TAG, "Confirmed property=0x${property.toString(16)} zone=$zone value=$value")
                return true
            }
            delay(100)
        }
    }

    private suspend fun withConnection(
        description: String,
        action: suspend (function: Any, functionApi: Class<*>) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        // 等上一条指令释放连接后再操作，防止打断回答时新旧连接互相干扰。
        mutex.withLock {
            val connection = Connection()
            try {
                // 包含连接和写入后的确认；等待期间可被“闭嘴”等本地命令取消。
                val result = withTimeoutOrNull(5_000) {
                    val function = connection.open()
                    val functionApi = loadClass("com.ecarx.xui.adaptapi.car.base.ICarFunction")
                    action(function, functionApi)
                }
                if (result == null) Log.w(TAG, "Timed out waiting for ECARX connection or confirmation: $description")
                result ?: false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val cause = (error as? InvocationTargetException)?.targetException ?: error
                Log.e(TAG, "HVAC control failed ($description): ${cause.javaClass.simpleName}: ${cause.message}", cause)
                false
            } catch (error: LinkageError) {
                Log.e(TAG, "ECARX API is unavailable on this firmware", error)
                false
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { connection.close() }
            }
        }
    }

    private inner class Connection {
        private var car: Any? = null
        private var connectable: Class<*>? = null
        private var watching = false

        suspend fun open(): Any = withContext(Dispatchers.Main.immediate) {
            val carApi = loadClass("com.ecarx.xui.adaptapi.car.Car")
            val instance = checkNotNull(carApi.getMethod("create", Context::class.java).invoke(null, appContext)) {
                "ECARX Car.create returned null"
            }
            car = instance
            val connectionApi = loadClass("com.ecarx.xui.adaptapi.binder.IConnectable")
            if (connectionApi.isInstance(instance)) {
                connectable = connectionApi
                val watcherApi = loadClass("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
                val connected = CompletableDeferred<Unit>()
                val watcher = Proxy.newProxyInstance(watcherApi.classLoader, arrayOf(watcherApi)) { proxy, method, arguments ->
                    when (method.name) {
                        "onConnected" -> { connected.complete(Unit); null }
                        "onDisConnected" -> { connected.completeExceptionally(IllegalStateException("ECARX disconnected")); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.firstOrNull()
                        "toString" -> "DoubaoHvacConnectionWatcher"
                        else -> null
                    }
                }
                // 与原项目一致：Car.create 发起连接，registerConnectWatcher 等待就绪回调。
                watching = true
                connectionApi.getMethod("registerConnectWatcher", watcherApi).invoke(instance, watcher)
                connected.await()
            }
            checkNotNull(loadClass("com.ecarx.xui.adaptapi.car.ICar").getMethod("getICarFunction").invoke(instance)) {
                "ECARX getICarFunction returned null"
            }
        }

        fun close() {
            val instance = car ?: return
            val api = connectable ?: return
            if (watching) runCatching { api.getMethod("unregisterConnectWatcher").invoke(instance) }
                .onFailure { Log.w(TAG, "Cannot unregister ECARX watcher", it) }
            runCatching { api.getMethod("disconnect").invoke(instance) }
                .onFailure { Log.w(TAG, "Cannot disconnect ECARX", it) }
        }
    }

    private companion object {
        const val TAG = "GeelyHvac"
        // 来源：Geely_Monjaro_oneOS_Hvac 的 GlyCarImpl / MainViewModel / GlyCarPropertyIds。
        // https://github.com/Schum-io/Geely_Monjaro_oneOS_Hvac
        const val POWER = 0x10010100 // HVAC_FUNC_POWER：空调总开关，1 开启、0 关闭。
        const val CIRCULATION = 0x10030100 // HVAC_FUNC_CIRCULATION：循环模式属性，使用全局区域。
        const val CIRCULATION_INNER = 0x10030101 // 内循环；不是整数 1。
        const val CIRCULATION_OUTSIDE = 0x10030102 // 外循环；不是整数 2。
        const val AC = 0x10010300 // AC 制冷开关，Int：1 开启、0 关闭。
        const val AUTO = 0x10010200 // AUTO 自动空调模式，Int：1 开启、0 关闭。
        const val REAR_DEFROST = 0x10040300 // HVAC_FUNC_DEFROST_REAR：后窗加热/后除霜，1 开启、0 关闭。
        const val FAN = 0x10020100 // 手动风速，档位编码 0x10020101～09。
        const val FAN_AUTO = 0x10020200 // 自动风速，采用原项目的五档映射。
        const val FRONT_ZONE = 8 // 原项目前排风速区域；不是温度使用的主驾区域 1。
        const val SEAT_VENTILATION = 0x10050100 // 座椅通风档位属性。
        const val SEAT_VENTILATION_AUTO = 0x10141200 // 座椅自动通风开关，手动操作时关闭。
        const val SEAT_HEATING = 0x10050200 // 座椅加热档位属性，档位编码 0x10050201～03。
        const val SEAT_HEATING_AUTO = 0x10141300 // 座椅自动加热开关，手动操作时关闭。
        const val TEMP = 0x10060100 // 目标温度，Float。
        const val TEMP_MAX = 0x10060200 // 车机支持的最高温度。
        const val TEMP_MIN = 0x10060300 // 车机支持的最低温度。
        const val TEMP_STEP = 0x10060400 // 每档温差。
        const val GLOBAL_ZONE = Int.MIN_VALUE // 原项目无区域参数时使用此值。
    }
}
