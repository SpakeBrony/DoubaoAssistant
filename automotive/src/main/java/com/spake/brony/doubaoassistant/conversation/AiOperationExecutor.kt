package com.spake.brony.doubaoassistant.conversation

internal class AiOperationExecutor(
    private val setTemperature: suspend (temperature: Float, seat: String) -> Boolean = { _, _ -> false },
) {
    // 未接入的操作返回 false，交给对话流程播放 failure_reply，避免误报成功。
    suspend fun execute(operation: AiOperation): Boolean = when (operation.intent) {
        // 空调开关：slots.enabled 为 true/false，尚未接入车控。
        "set_ac_power" -> false
        // 设置目标温度：slots.temperature 为数字；seat 可选 driver/passenger，默认主驾。
        "set_temperature" -> executeTemperature(operation)
        // 相对升降温：direction 为 increase/decrease，amount 为可选度数，尚未接入。
        "adjust_temperature" -> false
        // 空调风速：slots.level 为档位数字，尚未接入。
        "set_fan_speed" -> false
        // 座椅通风：seat 为座位，enabled 为开关，level 为可选档位，尚未接入。
        "set_seat_ventilation" -> false
        // 座椅加热：参数同座椅通风，尚未接入。
        "set_seat_heating" -> false
        // 内外循环：slots.mode 为 internal/external，尚未接入。
        "set_circulation" -> false
        // 后窗加热：slots.enabled 为开关，尚未接入。
        "set_rear_window_heating" -> false
        // 音乐控制：slots.action 为 next/previous/play/pause，尚未接入。
        "media_control" -> false
        // 打开应用：slots.app 为用户说出的应用名称，尚未接入。
        "open_app" -> false
        // 关闭助手收音：优先由本地 AsrCommands 处理；此 AI 分支尚未接入。
        "close_microphone" -> false
        // 停止助手回答：优先由本地 AsrCommands 处理；此 AI 分支尚未接入。
        "stop_reply" -> false
        // 退出助手页面：优先由本地 AsrCommands 处理；此 AI 分支尚未接入。
        "return_home" -> false
        // 未知或未支持的意图均不执行。
        else -> false
    }

    private suspend fun executeTemperature(operation: AiOperation): Boolean {
        // 模型输出是不可信输入：字符串、缺失值和不支持的座位不能直接送入车控。
        val number = operation.slots.opt("temperature") as? Number ?: return false
        val temperature = number.toFloat()
        if (!temperature.isFinite()) return false
        val seat = if (operation.slots.has("seat")) operation.slots.opt("seat") as? String ?: return false else "driver"
        if (seat != "driver" && seat != "passenger") return false
        return setTemperature(temperature, seat)
    }
}
