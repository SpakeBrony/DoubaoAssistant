package com.spake.brony.doubaoassistant.car

import android.content.Context
import android.util.Log
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
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    suspend fun setTemperature(temperature: Float, seat: String): Boolean = withContext(Dispatchers.IO) {
        val zone = when (seat) {
            "driver" -> 1 // 前排左侧，即主驾。
            "passenger" -> 4 // 前排右侧，即副驾。
            else -> return@withContext false
        }
        if (!temperature.isFinite()) return@withContext false
        // 等上一条指令释放连接后再操作，防止打断回答时新旧连接互相干扰。
        mutex.withLock {
            val connection = Connection()
            try {
                // 包含连接、读取范围和写入后的确认；等待期间可被“闭嘴”等本地命令取消。
                val result = withTimeoutOrNull(5_000) {
                    val function = connection.open()
                    val functionApi = loadClass("com.ecarx.xui.adaptapi.car.base.ICarFunction")
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
                    val steps = (temperature - min) / step
                    if (temperature !in min..max || !steps.isFinite() || abs(steps - round(steps)) > 0.001f) {
                        Log.w(TAG, "Rejected temperature=$temperature; range=$min..$max step=$step zone=$zone")
                        return@withTimeoutOrNull false
                    }

                    currentCoroutineContext().ensureActive()
                    val set = functionApi.getMethod("setCustomizeFunctionValue", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    val accepted = set.invoke(function, TEMP, zone, temperature) == true
                    Log.i(TAG, "Set temperature=$temperature zone=$zone accepted=$accepted")
                    if (!accepted) return@withTimeoutOrNull false

                    // SDK 接受请求还不代表温度已改变；回读同一区域后才播放成功文案。
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val actual = read(TEMP, zone)
                        if (actual.isFinite() && abs(actual - temperature) < 0.05f) {
                            Log.i(TAG, "Confirmed temperature=$actual zone=$zone")
                            break
                        }
                        delay(100)
                    }
                    true
                }
                if (result == null) Log.w(TAG, "Timed out waiting for ECARX connection, temperature range or confirmation")
                result ?: false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val cause = (error as? InvocationTargetException)?.targetException ?: error
                Log.e(TAG, "Temperature control failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
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
        const val TEMP = 0x10060100 // 目标温度，Float。
        const val TEMP_MAX = 0x10060200 // 车机支持的最高温度。
        const val TEMP_MIN = 0x10060300 // 车机支持的最低温度。
        const val TEMP_STEP = 0x10060400 // 每档温差。
        const val GLOBAL_ZONE = Int.MIN_VALUE // 原项目无区域参数时使用此值。
    }
}
