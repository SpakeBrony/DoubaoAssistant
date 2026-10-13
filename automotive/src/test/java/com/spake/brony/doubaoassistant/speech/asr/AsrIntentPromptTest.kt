package com.spake.brony.doubaoassistant.speech.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrIntentPromptTest {
    private fun promptsFor(vararg intents: String) = (listOf(AsrIntentPrompt.systemString) +
        AsrIntentPrompt.configs.filter { config -> config.intents.any { it in intents } }
            .map { it.prompt }).joinToString("\n\n")
    @Test fun navigationPhrasesIncludeOnlyMatchedPrompts() {
        for (command in listOf("导航去杭州东站", "我要去上海虹桥机场", "现在去西湖", "我要去我要去杭州东站",
            "带我去最近的停车场", "导航去空调维修店", "我想去座椅维修店", "现在去哪里玩好")) {
            val expected = when (command) {
                "导航去空调维修店" -> promptsFor("navigate", "set_ac_power")
                "我想去座椅维修店" -> promptsFor("navigate", "set_seat_heating")
                else -> promptsFor("navigate")
            }
            assertEquals(command, expected, AsrIntentPrompt.instructionFor(command))
        }
    }

    @Test fun fanCommandsWithoutAirConditionerKeywordReachAirConditionerPrompt() {
        for (command in listOf("风速调到三档", "风量调到五档", "风量大一点", "风量小一点", "风速降低两档")) {
            assertEquals(command, promptsFor("set_ac_power"), AsrIntentPrompt.instructionFor(command))
        }
    }

    @Test fun heatingCommandsIncludingSeatAliasesReachSeatPrompt() {
        for (command in listOf("开启座椅加热", "关闭座椅加热", "主驾加热调到一档", "副驾座椅加热三档", "驾驶员加热", "驾驶位加热")) {
            val expected = if (command == "开启座椅加热") promptsFor("open_app", "set_seat_heating")
                else promptsFor("set_seat_heating")
            assertEquals(command, expected, AsrIntentPrompt.instructionFor(command))
        }
    }

    @Test fun ventilationCommandsIncludingSeatAliasesReachSeatPrompt() {
        for (command in listOf("开启座椅通风", "关闭座椅通风", "副驾通风调到二档", "驾驶位通风三档", "驾驶员座位通风", "主驾座，椅通风")) {
            val expected = if (command == "开启座椅通风") promptsFor("open_app", "set_seat_ventilation")
                else promptsFor("set_seat_ventilation")
            assertEquals(command, expected, AsrIntentPrompt.instructionFor(command))
        }
    }

    @Test fun coolingAndAutoCommandsSelectAirConditionerPromptWithoutRequiringAirConditionerKeyword() {
        for (command in listOf("开启制冷", "关闭制冷", "开启自动空调模式", "关闭自动空调模式", "开启ac", "关闭A/C", "打开auto")) {
            val expected = when (command) {
                "开启制冷", "开启自动空调模式", "开启ac", "打开auto" -> promptsFor("open_app", "set_ac_power")
                else -> promptsFor("set_ac_power")
            }
            assertEquals(command, expected, AsrIntentPrompt.instructionFor(command))
        }
    }

    @Test fun matchedKeywordsIncludeOnlyMatchedPromptsAndOrdinaryChatHasNone() {
        assertEquals(promptsFor("set_ac_power"), AsrIntentPrompt.instructionFor("把空调调到28度"))
        assertEquals(promptsFor("set_seat_ventilation"), AsrIntentPrompt.instructionFor("主驾座椅通风开到三档"))
        assertEquals(promptsFor("set_seat_ventilation"), AsrIntentPrompt.instructionFor("主驾座，椅通风开到三档"))
        assertEquals("", AsrIntentPrompt.instructionFor("讲个故事"))
        assertEquals("", AsrIntentPrompt.instructionFor(""))
        assertEquals(promptsFor("open_app", "set_ac_power", "set_seat_ventilation"),
            AsrIntentPrompt.instructionFor("打开空调和座椅通风"))
    }
}
