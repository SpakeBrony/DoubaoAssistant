package com.spake.brony.doubaoassistant.conversation

import org.json.JSONObject

/** 车机能力：当前由吉利控制器实现，Executor 不依赖具体车型 SDK。 */
internal interface CarOperations {
    suspend fun setAcPower(enabled: Boolean): Boolean
    suspend fun setCooling(enabled: Boolean): Boolean
    suspend fun setAutoMode(enabled: Boolean): Boolean
    suspend fun setTemperature(temperature: Float, seat: String): Boolean
    suspend fun adjustTemperature(direction: String, amount: Float, seat: String): Boolean
    suspend fun setFanSpeed(level: Int): Boolean
    suspend fun adjustFanSpeed(direction: String, amount: Int): Boolean
    suspend fun setSeatVentilation(seat: String, level: Int): Boolean
    suspend fun setSeatHeating(seat: String, level: Int): Boolean
    suspend fun setCirculation(mode: String): Boolean
    suspend fun setRearWindowHeating(enabled: Boolean): Boolean
}

/** 系统能力：Android 启动应用、导航、电话、音乐、音量和红外调用。 */
internal interface SystemOperations {
    suspend fun openApp(appName: String): Boolean
    suspend fun navigate(destination: String): Boolean
    suspend fun dialPhone(phoneNumber: String?, contactName: String?): Boolean
    suspend fun controlMusic(action: String): Boolean
    suspend fun playSong(song: String): Boolean
    suspend fun playFavorites(): Boolean
    suspend fun changeVolume(channel: String, percent: Int?, direction: String?, amount: Int): Boolean
    suspend fun setTvPower(enabled: Boolean): Boolean
}

/** 查询服务：天气取第三方数据；时间使用设备时钟，国外地点需要解析时区。 */
internal interface ApiOperations {
    suspend fun getWeather(slots: JSONObject): String
    suspend fun getTime(slots: JSONObject): String
}
