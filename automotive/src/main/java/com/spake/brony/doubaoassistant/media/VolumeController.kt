package com.spake.brony.doubaoassistant.media

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 车机按用途调整音量组；普通 Android 设备只支持媒体和通话音量。 */
internal class VolumeController(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    suspend fun change(channel: String, percent: Int?, direction: String?, amount: Int): Boolean {
        if (channel !in listOf("media", "navigation", "call")) return false
        if (percent != null) {
            if (percent !in 0..100 || direction != null) return false
        } else if (direction !in listOf("increase", "decrease") || amount !in 1..100) return false
        val carApi = try { appContext.classLoader.loadClass("android.car.Car") }
        catch (_: ClassNotFoundException) { null }
        return try {
            if (carApi != null) changeCarVolume(carApi, channel, percent, direction, amount)
            else changeStreamVolume(channel, percent, direction, amount)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            Log.w("VolumeController", "Cannot change $channel volume", cause)
            false
        } catch (error: LinkageError) {
            Log.w("VolumeController", "Car audio API unavailable", error)
            false
        }
    }

    private suspend fun changeStreamVolume(channel: String, percent: Int?, direction: String?, amount: Int): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val manager = audio ?: return@withContext false
            val stream = when (channel) {
                "media" -> AudioManager.STREAM_MUSIC
                "call" -> AudioManager.STREAM_VOICE_CALL
                else -> return@withContext false // 导航不能冒用媒体、通知或铃声音量。
            }
            if (manager.isVolumeFixed) return@withContext false
            val current = manager.getStreamVolume(stream)
            val target = targetIndex(manager.getStreamMinVolume(stream), manager.getStreamMaxVolume(stream),
                current, percent, direction, amount) ?: return@withContext false
            currentCoroutineContext().ensureActive()
            manager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            withTimeoutOrNull(1_000) {
                while (manager.getStreamVolume(stream) != target) delay(100)
                true
            } ?: false
        }

    private suspend fun changeCarVolume(carApi: Class<*>, channel: String, percent: Int?, direction: String?, amount: Int): Boolean =
        withContext(Dispatchers.Main.immediate) {
            var car: Any? = null
            try {
                withTimeoutOrNull(5_000) {
                    val connected = CompletableDeferred<Unit>()
                    val listener = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { connected.complete(Unit) }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            connected.completeExceptionally(IllegalStateException("Car audio disconnected"))
                        }
                    }
                    // 与吉利 GlyAudioImpl 一致：连接 Android Car 服务，再取得 audio manager。
                    val instance = carApi.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                        .invoke(null, appContext, listener) ?: return@withTimeoutOrNull false
                    car = instance
                    carApi.getMethod("connect").invoke(instance)
                    connected.await()
                    val manager = carApi.getMethod("getCarManager", String::class.java).invoke(instance, "audio")
                        ?: return@withTimeoutOrNull false
                    val api = appContext.classLoader.loadClass("android.car.media.CarAudioManager")
                    fun read(method: String, argument: Int): Int = (api.getMethod(method, Int::class.javaPrimitiveType)
                        .invoke(manager, argument) as Number).toInt()
                    val usage = usageFor(channel)
                    if (usage <= 0) return@withTimeoutOrNull false
                    val group = read("getVolumeGroupIdForUsage", usage)
                    if (group < 0) return@withTimeoutOrNull false
                    if (channel == "navigation") {
                        val mediaGroup = read("getVolumeGroupIdForUsage", usageFor("media"))
                        if (group == mediaGroup) return@withTimeoutOrNull false
                    }
                    val target = targetIndex(read("getGroupMinVolume", group), read("getGroupMaxVolume", group),
                        read("getGroupVolume", group), percent, direction, amount) ?: return@withTimeoutOrNull false
                    currentCoroutineContext().ensureActive()
                    api.getMethod("setGroupVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).invoke(manager, group, target, AudioManager.FLAG_SHOW_UI)
                    while (read("getGroupVolume", group) != target) delay(100)
                    true
                } ?: false
            } finally {
                withContext(NonCancellable) {
                    car?.let { runCatching { carApi.getMethod("disconnect").invoke(it) }
                        .onFailure { error -> Log.w("VolumeController", "Cannot disconnect car audio", error) } }
                }
            }
        }

    private fun usageFor(channel: String): Int {
        val standard = when (channel) {
            "media" -> AudioAttributes.USAGE_MEDIA
            "navigation" -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            else -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        }
        val policyApi = try { appContext.classLoader.loadClass("com.ecarx.xui.adaptapi.policy.Policy") }
        catch (_: ClassNotFoundException) { return standard }
        val policy = policyApi.getMethod("create", Context::class.java).invoke(null, appContext) ?: return -1
        val attributes = policyApi.getMethod("getAudioAttributes").invoke(policy) ?: return -1
        val key = when (channel) {
            "media" -> "MEDIA"
            "navigation" -> "NAVIGATION_GUIDANCE"
            else -> "VOICE_COMMUNICATION"
        }
        // 来源：GlyAudioImpl / GlyAudioPropertyValue；使用固件映射，不硬编码音量组编号。
        return (appContext.classLoader.loadClass("com.ecarx.xui.adaptapi.policy.IAudioAttributes")
            .getMethod("getAudioAtrributesUsage", String::class.java).invoke(attributes, key) as Number).toInt()
    }

    private fun targetIndex(min: Int, max: Int, current: Int, percent: Int?, direction: String?, amount: Int): Int? {
        if (min < 0 || max <= min || current !in min..max) return null
        // 百分比映射至设备的可调范围，通话最低档不一定为零；结果取最近的实际档位。
        if (percent != null) return min + ((max - min) * percent / 100.0).roundToInt()
        val target = (current.toLong() + if (direction == "increase") amount else -amount)
            .coerceIn(min.toLong(), max.toLong()).toInt()
        return target.takeIf { it != current }
    }
}
