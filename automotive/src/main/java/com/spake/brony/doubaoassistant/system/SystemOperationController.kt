package com.spake.brony.doubaoassistant.system

import android.content.Context
import com.spake.brony.doubaoassistant.apps.AppLauncher
import com.spake.brony.doubaoassistant.conversation.SystemOperations
import com.spake.brony.doubaoassistant.infrared.TvIrController
import com.spake.brony.doubaoassistant.media.MusicController
import com.spake.brony.doubaoassistant.media.VolumeController
import com.spake.brony.doubaoassistant.navigation.NavigationLauncher
import com.spake.brony.doubaoassistant.phone.PhoneLauncher

/** 汇总 Android 系统调用，保留各控制器原有的权限申请、应用匹配和错误处理。 */
internal class SystemOperationController(context: Context) : SystemOperations {
    private val apps = AppLauncher(context.applicationContext)
    private val navigation = NavigationLauncher(context.applicationContext)
    private val phone = PhoneLauncher(context.applicationContext)
    private val music = MusicController(context.applicationContext)
    private val volume = VolumeController(context.applicationContext)
    private val television = TvIrController(context.applicationContext)

    override suspend fun openApp(appName: String): Boolean = apps.open(appName)
    override suspend fun navigate(destination: String): Boolean = navigation.navigate(destination)
    override suspend fun dialPhone(phoneNumber: String?, contactName: String?): Boolean = phone.dial(phoneNumber, contactName)
    override suspend fun controlMusic(action: String): Boolean = music.control(action)
    override suspend fun playSong(song: String): Boolean = music.playSong(song)
    override suspend fun playFavorites(): Boolean = music.playFavorites()
    override suspend fun changeVolume(channel: String, percent: Int?, direction: String?, amount: Int): Boolean =
        volume.change(channel, percent, direction, amount)
    override suspend fun setTvPower(enabled: Boolean): Boolean = television.setPower(enabled)
}
