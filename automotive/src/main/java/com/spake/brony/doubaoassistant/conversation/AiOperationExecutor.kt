package com.spake.brony.doubaoassistant.conversation

import org.json.JSONObject

internal class AiOperationExecutor(
    private val car: CarOperations,
    private val system: SystemOperations,
    private val api: ApiOperations,
) {
    // 查询及开度控制自带真实回复；其他操作由对话流程选择模型提供的候选文案。
    data class Result(val succeeded: Boolean, val reply: String? = null)

    // 所有操作和查询都从这里分发；未接入或未知意图失败，不能误报成功。
    suspend fun execute(operation: AiOperation): Result {
        val succeeded = when (operation.intent) {
            // 车机
            // 空调总开关：slots.enabled 必须为 JSON 布尔值，写入并回读确认。
            "set_ac_power" -> executeSwitch(operation, car::setAcPower)
            // AC 制冷开关：slots.enabled 必须是 JSON 布尔值，与空调总开关分开。
            "set_ac_cooling" -> executeSwitch(operation, car::setCooling)
            // AUTO 自动空调模式：slots.enabled 为 true 开启、false 关闭。
            "set_ac_auto" -> executeSwitch(operation, car::setAutoMode)
            // 设置目标温度：slots.temperature 为数字；seat 可选 driver/passenger，默认主驾。
            "set_temperature" -> executeTemperature(operation)
            // 相对调温：冷热表达默认升降 2 度，先读取对应座位的当前温度。
            "adjust_temperature" -> executeTemperatureAdjustment(operation)
            // 前排风速：level 必须是整数 1～9；AUTO 的五档上限由车控按当前模式核对。
            "set_fan_speed" -> executeFanSpeed(operation)
            // 前排风速升降：direction 为 increase/decrease，amount 省略时调一档。
            "adjust_fan_speed" -> executeFanAdjustment(operation)
            // 座椅通风：默认主驾；开启默认二档，关闭为零档；显式档位只接受整数 0～3。
            "set_seat_ventilation" -> executeSeatOperation(operation, car::setSeatVentilation)
            // 座椅加热：同样默认主驾、开启二档，关闭零档，与通风分别执行。
            "set_seat_heating" -> executeSeatOperation(operation, car::setSeatHeating)
            // 内外循环：slots.mode 只接受 internal/external，核对车机支持值后写入并确认。
            "set_circulation" -> executeCirculation(operation)
            // 后窗加热/后除霜：slots.enabled 必须是 JSON 布尔值，交给车控写入并确认。
            "set_rear_window_heating" -> executeSwitch(operation, car::setRearWindowHeating)
            "control_sunroof", "control_sunshade", "control_window" -> return executePosition(operation)
            // 系统
            // 音乐控制：只接受 next/previous/play/pause，向 QQ 音乐发送指令。
            "media_control" -> executeMusicControl(operation)
            // 点播歌曲与恢复当前播放分开，不能丢掉用户指定的歌名。
            "play_song" -> executeSong(operation)
            // 请求 QQ 音乐播放当前账户的收藏歌单，不用普通播放键代替。
            "play_favorites" -> if (operation.slots.length() == 0) system.playFavorites() else false
            // 音量：默认媒体，支持导航/通话；助手音量不允许语音修改。
            "set_volume" -> executeVolume(operation, relative = false)
            "adjust_volume" -> executeVolume(operation, relative = true)
            // 电视开关：先检测本机红外，再发送已配置的独立开机/关机指令。
            "set_tv_power" -> executeSwitch(operation, system::setTvPower)
            // 打开应用：slots.app 为用户说出的应用名称，由本机匹配已安装应用及别名。
            "open_app" -> executeOpenApp(operation)
            // 导航：只接收目的地名称，由 Android 按默认地图及已安装应用选择接收方。
            "navigate" -> executeNavigation(operation)
            // 电话：phone_number 或 contact_name 二选一；授权后交给系统直接呼叫。
            "call_phone" -> executePhoneCall(operation)
            // 第三方 API 与信息查询
            // 天气查询：验证地点/日期后取真实数据；未指定地点时申请定位权限。
            "get_weather" -> return executeQuery(operation, api::getWeather)
            // 时间/日期查询：读取设备时钟并按指定地点转换，只返回用户询问的部分。
            "get_time" -> return executeQuery(operation, api::getTime)
            // 未知或未支持的意图均不执行。
            else -> false
        }
        return Result(succeeded)
    }

    private suspend fun executeQuery(operation: AiOperation, query: suspend (JSONObject) -> String?): Result {
        val reply = query(operation.slots)?.takeIf { it.isNotBlank() }
        return Result(succeeded = reply != null, reply = reply)
    }

    private suspend fun executeSwitch(operation: AiOperation, execute: suspend (Boolean) -> Boolean): Boolean {
        val enabled = operation.slots.opt("enabled") as? Boolean ?: return false
        return execute(enabled)
    }

    private suspend fun executePosition(operation: AiOperation): Result {
        val window = operation.intent == "control_window"
        val hasSeat = window && operation.slots.has("seat")
        val seat = if (hasSeat) operation.slots.opt("seat") as? String ?: return Result(false) else "driver"
        if (window && seat !in listOf("driver", "passenger", "rear_left", "rear_right", "all", "front", "rear")) return Result(false)
        if (operation.slots.length() != if (hasSeat) 2 else 1) return Result(false)
        val relative = operation.slots.has("adjustment")
        val percent = (operation.slots.opt(if (relative) "adjustment" else "percent") as? Number)?.toDouble()
            ?: return Result(false)
        if (!percent.isFinite() || percent % 1.0 != 0.0) return Result(false)
        if (relative) {
            if (percent != -10.0 && percent != 10.0) return Result(false)
        } else if (percent !in 0.0..100.0) return Result(false)
        val reply = when (operation.intent) {
            "control_sunroof" -> car.controlSunroof(percent.toInt(), relative)
            "control_sunshade" -> car.controlSunshade(percent.toInt(), relative)
            "control_window" -> return car.controlWindow(seat, percent.toInt(), relative)
            else -> null
        }?.takeIf { it.isNotBlank() }
        return Result(succeeded = reply != null, reply = reply)
    }

    private suspend fun executeCirculation(operation: AiOperation): Boolean {
        val mode = operation.slots.opt("mode") as? String ?: return false
        if (mode != "internal" && mode != "external") return false
        return car.setCirculation(mode)
    }

    private suspend fun executeMusicControl(operation: AiOperation): Boolean {
        val action = operation.slots.opt("action") as? String ?: return false
        if (action !in listOf("next", "previous", "play", "pause")) return false
        return system.controlMusic(action)
    }

    private suspend fun executeSong(operation: AiOperation): Boolean {
        val song = (operation.slots.opt("song") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: return false
        return system.playSong(song)
    }

    private suspend fun executeVolume(operation: AiOperation, relative: Boolean): Boolean {
        val slots = operation.slots
        val channel = if (slots.has("channel")) slots.opt("channel") as? String ?: return false else "media"
        if (channel !in listOf("media", "navigation", "call")) return false
        fun integer(key: String, range: IntRange): Int? {
            val number = (slots.opt(key) as? Number)?.toDouble() ?: return null
            if (!number.isFinite() || number % 1.0 != 0.0 || number < range.first || number > range.last) return null
            return number.toInt()
        }
        if (!relative) {
            if (slots.has("direction") || slots.has("amount")) return false
            return system.changeVolume(channel, integer("percent", 0..100) ?: return false, null, 1)
        }
        if (slots.has("percent")) return false
        val direction = slots.opt("direction") as? String ?: return false
        if (direction !in listOf("increase", "decrease")) return false
        val amount = if (slots.has("amount")) integer("amount", 1..100) ?: return false else 1
        return system.changeVolume(channel, null, direction, amount)
    }

    private suspend fun executeOpenApp(operation: AiOperation): Boolean {
        val name = (operation.slots.opt("app") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return system.openApp(name)
    }

    private suspend fun executeNavigation(operation: AiOperation): Boolean {
        val destination = (operation.slots.opt("destination") as? String)?.trim() ?: return false
        if (destination.isEmpty()) return false
        return system.navigate(destination)
    }

    private suspend fun executePhoneCall(operation: AiOperation): Boolean {
        val slots = operation.slots
        if (slots.has("phone_number") == slots.has("contact_name")) return false
        if (slots.has("phone_number")) {
            // 号码必须是字符串，保留前导零和国际区号，拒绝模型输出 JSON 数字。
            val number = (slots.opt("phone_number") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return system.dialPhone(number, null)
        }
        val name = (slots.opt("contact_name") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return system.dialPhone(null, name)
    }

    private suspend fun executeFanSpeed(operation: AiOperation): Boolean {
        val level = (operation.slots.opt("level") as? Number)?.toDouble() ?: return false
        if (level !in 1.0..9.0 || level % 1.0 != 0.0) return false
        return car.setFanSpeed(level.toInt())
    }

    private suspend fun executeFanAdjustment(operation: AiOperation): Boolean {
        val direction = operation.slots.opt("direction") as? String ?: return false
        if (direction != "increase" && direction != "decrease") return false
        val amount = if (operation.slots.has("amount")) {
            (operation.slots.opt("amount") as? Number)?.toDouble() ?: return false
        } else 1.0
        if (amount !in 1.0..8.0 || amount % 1.0 != 0.0) return false
        return car.adjustFanSpeed(direction, amount.toInt())
    }

    private suspend fun executeSeatOperation(operation: AiOperation, execute: suspend (String, Int) -> Boolean): Boolean {
        val seat = if (operation.slots.has("seat")) operation.slots.opt("seat") as? String ?: return false else "driver"
        if (seat != "driver" && seat != "passenger") return false
        val enabled = operation.slots.opt("enabled") as? Boolean ?: return false
        val level = if (operation.slots.has("level")) {
            val number = (operation.slots.opt("level") as? Number)?.toDouble() ?: return false
            if (number !in 0.0..3.0 || number % 1.0 != 0.0) return false
            number.toInt()
        } else if (enabled) 2 else 0
        // 不执行“开启零档”“关闭三档”这类开关与档位矛盾的模型结果。
        if (enabled != (level > 0)) return false
        return execute(seat, level)
    }

    private suspend fun executeTemperatureAdjustment(operation: AiOperation): Boolean {
        val slots = operation.slots
        val direction = slots.opt("direction") as? String ?: return false
        if (direction !in listOf("increase", "decrease") || slots.has("temperature")) return false
        val amount = if (slots.has("amount")) (slots.opt("amount") as? Number)?.toFloat() ?: return false else 2f
        if (!amount.isFinite() || amount <= 0f) return false
        val seat = if (slots.has("seat")) slots.opt("seat") as? String ?: return false else "driver"
        if (seat !in listOf("driver", "passenger")) return false
        return car.adjustTemperature(direction, amount, seat)
    }

    private suspend fun executeTemperature(operation: AiOperation): Boolean {
        // 模型输出是不可信输入：字符串、缺失值和不支持的座位不能直接送入车控。
        val number = operation.slots.opt("temperature") as? Number ?: return false
        val temperature = number.toFloat()
        if (!temperature.isFinite()) return false
        val seat = if (operation.slots.has("seat")) operation.slots.opt("seat") as? String ?: return false else "driver"
        if (seat != "driver" && seat != "passenger") return false
        return car.setTemperature(temperature, seat)
    }
}
