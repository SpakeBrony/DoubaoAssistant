package com.spake.brony.doubaoassistant.conversation

import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AsrSnapshot
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationMessage
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.data.model.ResponseProgress
import com.spake.brony.doubaoassistant.data.model.TtsProgress
import com.spake.brony.doubaoassistant.data.model.AnswerMode
import com.spake.brony.doubaoassistant.data.repository.ConversationRepository
import com.spake.brony.doubaoassistant.speech.tts.StreamingTts
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiOperationTest {
    // 回调仅用于现有用例的模拟模块；生产 Executor 必须传入完整的三个模块。
    private fun createExecutor(
        setTemperature: suspend (temperature: Float, seat: String) -> Boolean = { _, _ -> false },
        setCooling: suspend (enabled: Boolean) -> Boolean = { _ -> false },
        setAutoMode: suspend (enabled: Boolean) -> Boolean = { _ -> false },
        setFanSpeed: suspend (level: Int) -> Boolean = { _ -> false },
        adjustFanSpeed: suspend (direction: String, amount: Int) -> Boolean = { _, _ -> false },
        setSeatVentilation: suspend (seat: String, level: Int) -> Boolean = { _, _ -> false },
        setSeatHeating: suspend (seat: String, level: Int) -> Boolean = { _, _ -> false },
        setRearWindowHeating: suspend (enabled: Boolean) -> Boolean = { _ -> false },
        setAcPower: suspend (enabled: Boolean) -> Boolean = { _ -> false },
        setCirculation: suspend (mode: String) -> Boolean = { _ -> false },
        adjustTemperature: suspend (direction: String, amount: Float, seat: String) -> Boolean = { _, _, _ -> false },
        navigate: suspend (destination: String) -> Boolean = { _ -> false },
        dialPhone: suspend (phoneNumber: String?, contactName: String?) -> Boolean = { _, _ -> false },
        openApp: suspend (appName: String) -> Boolean = { _ -> false },
        controlMusic: suspend (action: String) -> Boolean = { _ -> false },
        changeVolume: suspend (channel: String, percent: Int?, direction: String?, amount: Int) -> Boolean = { _, _, _, _ -> false },
        setTvPower: suspend (enabled: Boolean) -> Boolean = { _ -> false },
        playSong: suspend (song: String) -> Boolean = { _ -> false },
        playFavorites: suspend () -> Boolean = { false },
        getWeather: suspend (slots: JSONObject) -> String = { _ -> "" },
        getTime: suspend (slots: JSONObject) -> String = { _ -> "" },
    ): AiOperationExecutor {
        val setTemperatureCallback = setTemperature
        val setCoolingCallback = setCooling
        val setAutoModeCallback = setAutoMode
        val setFanSpeedCallback = setFanSpeed
        val adjustFanSpeedCallback = adjustFanSpeed
        val setSeatVentilationCallback = setSeatVentilation
        val setSeatHeatingCallback = setSeatHeating
        val setRearWindowHeatingCallback = setRearWindowHeating
        val setAcPowerCallback = setAcPower
        val setCirculationCallback = setCirculation
        val adjustTemperatureCallback = adjustTemperature
        val navigateCallback = navigate
        val dialPhoneCallback = dialPhone
        val openAppCallback = openApp
        val controlMusicCallback = controlMusic
        val changeVolumeCallback = changeVolume
        val setTvPowerCallback = setTvPower
        val playSongCallback = playSong
        val playFavoritesCallback = playFavorites
        val getWeatherCallback = getWeather
        val getTimeCallback = getTime
        return AiOperationExecutor(
            car = object : CarOperations {
                override suspend fun setTemperature(temperature: Float, seat: String): Boolean = setTemperatureCallback(temperature, seat)
                override suspend fun setCooling(enabled: Boolean): Boolean = setCoolingCallback(enabled)
                override suspend fun setAutoMode(enabled: Boolean): Boolean = setAutoModeCallback(enabled)
                override suspend fun setFanSpeed(level: Int): Boolean = setFanSpeedCallback(level)
                override suspend fun adjustFanSpeed(direction: String, amount: Int): Boolean = adjustFanSpeedCallback(direction, amount)
                override suspend fun setSeatVentilation(seat: String, level: Int): Boolean = setSeatVentilationCallback(seat, level)
                override suspend fun setSeatHeating(seat: String, level: Int): Boolean = setSeatHeatingCallback(seat, level)
                override suspend fun setRearWindowHeating(enabled: Boolean): Boolean = setRearWindowHeatingCallback(enabled)
                override suspend fun setAcPower(enabled: Boolean): Boolean = setAcPowerCallback(enabled)
                override suspend fun setCirculation(mode: String): Boolean = setCirculationCallback(mode)
                override suspend fun adjustTemperature(direction: String, amount: Float, seat: String): Boolean = adjustTemperatureCallback(direction, amount, seat)
            },
            system = object : SystemOperations {
                override suspend fun navigate(destination: String): Boolean = navigateCallback(destination)
                override suspend fun dialPhone(phoneNumber: String?, contactName: String?): Boolean = dialPhoneCallback(phoneNumber, contactName)
                override suspend fun openApp(appName: String): Boolean = openAppCallback(appName)
                override suspend fun controlMusic(action: String): Boolean = controlMusicCallback(action)
                override suspend fun changeVolume(channel: String, percent: Int?, direction: String?, amount: Int): Boolean = changeVolumeCallback(channel, percent, direction, amount)
                override suspend fun setTvPower(enabled: Boolean): Boolean = setTvPowerCallback(enabled)
                override suspend fun playSong(song: String): Boolean = playSongCallback(song)
                override suspend fun playFavorites(): Boolean = playFavoritesCallback()
            },
            api = object : ApiOperations {
                override suspend fun getWeather(slots: JSONObject): String = getWeatherCallback(slots)
                override suspend fun getTime(slots: JSONObject): String = getTimeCallback(slots)
            },
        )
    }

    private val json = """{"intent":"set_temperature","slots":{"temperature":28},"success_reply":"空调已调到28度","failure_reply":"空调调节失败"}"""
    // 提示词已压缩，执行器用例保留自己的输入样本，不再从生产提示词提取长示例。
    private val examples = mapOf(
        "导航去杭州东站" to """{"intent":"navigate","slots":{"destination":"杭州东站"},"success_reply":"已发起前往杭州东站的导航请求，请在地图中确认目的地","failure_reply":"未能打开地图，请重试"}""",
        "我要去上海虹桥机场" to """{"intent":"navigate","slots":{"destination":"上海虹桥机场"},"success_reply":"已发起前往上海虹桥机场的导航请求，请在地图中确认目的地","failure_reply":"未能打开地图，请重试"}""",
        "现在去西湖" to """{"intent":"navigate","slots":{"destination":"西湖"},"success_reply":"已发起前往西湖的导航请求，请在地图中确认目的地","failure_reply":"未能打开地图，请重试"}""",
        "我要去我要去杭州东站" to """{"intent":"navigate","slots":{"destination":"杭州东站"},"success_reply":"已发起前往杭州东站的导航请求，请在地图中确认目的地","failure_reply":"未能打开地图，请重试"}""",
        "开启制冷" to """{"intent":"set_ac_cooling","slots":{"enabled":true},"success_reply":"已开启制冷","failure_reply":"开启制冷失败，请重试"}""",
        "关闭制冷" to """{"intent":"set_ac_cooling","slots":{"enabled":false},"success_reply":"已关闭制冷","failure_reply":"关闭制冷失败，请重试"}""",
        "开启自动空调模式" to """{"intent":"set_ac_auto","slots":{"enabled":true},"success_reply":"已开启自动空调模式","failure_reply":"开启自动空调模式失败，请重试"}""",
        "关闭自动空调模式" to """{"intent":"set_ac_auto","slots":{"enabled":false},"success_reply":"已关闭自动空调模式","failure_reply":"关闭自动空调模式失败，请重试"}""",
        "把空调调到28度" to """{"intent":"set_temperature","slots":{"temperature":28},"success_reply":"空调已调到28度","failure_reply":"空调温度调节失败，请重试"}""",
        "风速调到三档" to """{"intent":"set_fan_speed","slots":{"level":3},"success_reply":"空调风速已调到三档","failure_reply":"空调风速调节失败，请重试"}""",
        "风量大一点" to """{"intent":"adjust_fan_speed","slots":{"direction":"increase"},"success_reply":"空调风量已调大一档","failure_reply":"空调风量调节失败，请重试"}""",
        "风速降低两档" to """{"intent":"adjust_fan_speed","slots":{"direction":"decrease","amount":2},"success_reply":"空调风速已降低两档","failure_reply":"空调风速调节失败，请重试"}""",
        "主驾座椅通风三档" to """{"intent":"set_seat_ventilation","slots":{"seat":"driver","enabled":true,"level":3},"success_reply":"主驾座椅通风已调到三档","failure_reply":"主驾座椅通风调节失败，请重试"}""",
        "开启座椅通风" to """{"intent":"set_seat_ventilation","slots":{"enabled":true},"success_reply":"主驾座椅通风已开启","failure_reply":"主驾座椅通风开启失败，请重试"}""",
        "关闭座椅通风" to """{"intent":"set_seat_ventilation","slots":{"enabled":false},"success_reply":"主驾座椅通风已关闭","failure_reply":"主驾座椅通风关闭失败，请重试"}""",
        "副驾通风调到二档" to """{"intent":"set_seat_ventilation","slots":{"seat":"passenger","enabled":true,"level":2},"success_reply":"副驾座椅通风已调到二档","failure_reply":"副驾座椅通风调节失败，请重试"}""",
        "关闭副驾座椅通风" to """{"intent":"set_seat_ventilation","slots":{"seat":"passenger","enabled":false},"success_reply":"副驾座椅通风已关闭","failure_reply":"副驾座椅通风关闭失败，请重试"}""",
        "开启座椅加热" to """{"intent":"set_seat_heating","slots":{"enabled":true},"success_reply":"主驾座椅加热已开启","failure_reply":"主驾座椅加热开启失败，请重试"}""",
        "关闭座椅加热" to """{"intent":"set_seat_heating","slots":{"enabled":false},"success_reply":"主驾座椅加热已关闭","failure_reply":"主驾座椅加热关闭失败，请重试"}""",
        "主驾加热调到一档" to """{"intent":"set_seat_heating","slots":{"seat":"driver","enabled":true,"level":1},"success_reply":"主驾座椅加热已调到一档","failure_reply":"主驾座椅加热调节失败，请重试"}""",
        "副驾座椅加热三档" to """{"intent":"set_seat_heating","slots":{"seat":"passenger","enabled":true,"level":3},"success_reply":"副驾座椅加热已调到三档","failure_reply":"副驾座椅加热调节失败，请重试"}""",
        "关闭副驾座椅加热" to """{"intent":"set_seat_heating","slots":{"seat":"passenger","enabled":false},"success_reply":"副驾座椅加热已关闭","failure_reply":"副驾座椅加热关闭失败，请重试"}""",
    )
    private val structuredJson = """{"type":"actions","actions":[{"intent":"set_temperature","slots":{"temperature":28},"success_reply":"空调已调到28度","failure_reply":"空调调节失败"}]}"""
    private val getString: (Int) -> String = { "localized:$it" }
    @Before fun setUp() { ConversationRepository.reset() }
    @After fun tearDown() { ConversationRepository.reset() }

    @Test fun navigationExamplesPassDestinationToAndroidAndPropagateLaunchFailure() = runTest {
        val destinations = mutableListOf<String>()
        val executor = createExecutor(navigate = { destinations += it; true })
        for (command in listOf("导航去杭州东站", "我要去上海虹桥机场", "现在去西湖", "我要去我要去杭州东站")) {
            val example = examples.getValue(command)
            val operation = checkNotNull(AiOperation.parse(example))
            assertTrue(executor.execute(operation).succeeded)
            assertFalse(createExecutor(navigate = { false }).execute(operation).succeeded)
        }
        assertEquals(listOf("杭州东站", "上海虹桥机场", "西湖", "杭州东站"), destinations)
        assertTrue(executor.execute(AiOperation("navigate", JSONObject("""{"destination":"  南京南站  "}"""), "成功", "失败")).succeeded)
        assertEquals("南京南站", destinations.last())
    }

    @Test fun navigationRejectsMissingOrNonTextDestinationsBeforeOpeningMap() = runTest {
        var calls = 0
        val executor = createExecutor(navigate = { calls++; true })
        for (slots in listOf("{}", """{"destination":null}""", """{"destination":123}""",
            """{"destination":true}""", """{"destination":{}}""", """{"destination":""}""", """{"destination":"   "}""")) {
            assertFalse(executor.execute(AiOperation("navigate", JSONObject(slots), "成功", "失败")).succeeded)
        }
        assertEquals(0, calls)
    }

    @Test fun fanPromptExamplesDispatchAbsoluteAndRelativeOperations() = runTest {
        val levels = mutableListOf<Int>()
        val adjustments = mutableListOf<Pair<String, Int>>()
        val executor = createExecutor(
            setFanSpeed = { levels += it; true },
            adjustFanSpeed = { direction, amount -> adjustments += direction to amount; true },
        )
        for (command in listOf("风速调到三档", "风量大一点", "风速降低两档")) {
            val example = examples.getValue(command)
            assertTrue(executor.execute(checkNotNull(AiOperation.parse(example))).succeeded)
        }
        assertEquals(listOf(3), levels)
        assertEquals(listOf("increase" to 1, "decrease" to 2), adjustments)
        val failure = createExecutor(setFanSpeed = { false }, adjustFanSpeed = { _, _ -> false })
        assertFalse(failure.execute(AiOperation("set_fan_speed", JSONObject("""{"level":3}"""), "成功", "失败")).succeeded)
        assertFalse(failure.execute(AiOperation("adjust_fan_speed", JSONObject("""{"direction":"increase"}"""), "成功", "失败")).succeeded)
    }

    @Test fun malformedFanParametersNeverReachCar() = runTest {
        var calls = 0
        val executor = createExecutor(setFanSpeed = { calls++; true }, adjustFanSpeed = { _, _ -> calls++; true })
        for (slots in listOf("{}", """{"level":"3"}""", """{"level":null}""", """{"level":true}""",
            """{"level":0}""", """{"level":10}""", """{"level":2.5}""")) {
            assertFalse(executor.execute(AiOperation("set_fan_speed", JSONObject(slots), "成功", "失败")).succeeded)
        }
        for (slots in listOf("{}", """{"direction":"up"}""", """{"direction":null}""",
            """{"direction":"increase","amount":0}""", """{"direction":"increase","amount":9}""",
            """{"direction":"decrease","amount":1.5}""", """{"direction":"increase","amount":"2"}""",
            """{"direction":"increase","amount":null}""")) {
            assertFalse(executor.execute(AiOperation("adjust_fan_speed", JSONObject(slots), "成功", "失败")).succeeded)
        }
        assertEquals(0, calls)
    }

    @Test fun seatVentilationExamplesDispatchDefaultOrSpecifiedSeatAndLevel() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val executor = createExecutor(setSeatVentilation = { seat, level -> calls += seat to level; true })
        for (command in listOf("主驾座椅通风三档", "开启座椅通风", "关闭座椅通风", "副驾通风调到二档", "关闭副驾座椅通风")) {
            val example = examples.getValue(command)
            assertTrue(executor.execute(checkNotNull(AiOperation.parse(example))).succeeded)
        }
        assertEquals(listOf("driver" to 3, "driver" to 2, "driver" to 0, "passenger" to 2, "passenger" to 0), calls)
        val failure = createExecutor(setSeatVentilation = { _, _ -> false })
        assertFalse(failure.execute(AiOperation("set_seat_ventilation", JSONObject("""{"enabled":true,"level":1}"""), "成功", "失败")).succeeded)
    }

    @Test fun seatHeatingExamplesDispatchToHeatingWithDefaultOrSpecifiedSeatAndLevel() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val executor = createExecutor(
            setSeatVentilation = { _, _ -> error("Heating must not dispatch to ventilation") },
            setSeatHeating = { seat, level -> calls += seat to level; true },
        )
        for (command in listOf("开启座椅加热", "关闭座椅加热", "主驾加热调到一档", "副驾座椅加热三档", "关闭副驾座椅加热")) {
            val example = examples.getValue(command)
            assertTrue(executor.execute(checkNotNull(AiOperation.parse(example))).succeeded)
        }
        assertEquals(listOf("driver" to 2, "driver" to 0, "driver" to 1, "passenger" to 3, "passenger" to 0), calls)
        val failure = createExecutor(setSeatHeating = { _, _ -> false })
        assertFalse(failure.execute(AiOperation("set_seat_heating", JSONObject("""{"enabled":true,"level":2}"""), "成功", "失败")).succeeded)
    }

    @Test fun malformedOrConflictingSeatParametersNeverReachCar() = runTest {
        var calls = 0
        val executor = createExecutor(setSeatVentilation = { _, _ -> calls++; true }, setSeatHeating = { _, _ -> calls++; true })
        for (intent in listOf("set_seat_ventilation", "set_seat_heating")) {
            for (slots in listOf("{}", """{"level":2}""", """{"enabled":"true"}""",
                """{"enabled":true,"seat":null}""", """{"enabled":true,"seat":"rear"}""",
                """{"enabled":true,"level":"2"}""", """{"enabled":true,"level":null}""",
                """{"enabled":true,"level":-1}""", """{"enabled":true,"level":4}""",
                """{"enabled":true,"level":1.5}""", """{"enabled":true,"level":0}""", """{"enabled":false,"level":3}""")) {
                assertFalse(executor.execute(AiOperation(intent, JSONObject(slots), "成功", "失败")).succeeded)
            }
        }
        assertEquals(0, calls)
    }

    @Test fun coolingAndAutoPromptExamplesDispatchToIndependentSwitches() = runTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val executor = createExecutor(
            setCooling = { calls += "AC" to it; true },
            setAutoMode = { calls += "AUTO" to it; false },
        )
        val commands = listOf("开启制冷", "关闭制冷", "开启自动空调模式", "关闭自动空调模式")
        for ((index, command) in commands.withIndex()) {
            val example = examples.getValue(command)
            val operation = checkNotNull(AiOperation.parse(example))
            assertEquals(index < 2, executor.execute(operation).succeeded)
        }
        assertEquals(listOf("AC" to true, "AC" to false, "AUTO" to true, "AUTO" to false), calls)
    }

    @Test fun switchIntentsRejectMissingOrNonBooleanEnabled() = runTest {
        var calls = 0
        val executor = createExecutor(setCooling = { calls++; true }, setAutoMode = { calls++; true })
        for (intent in listOf("set_ac_cooling", "set_ac_auto")) {
            for (slots in listOf("{}", """{"enabled":"false"}""", """{"enabled":1}""", """{"enabled":null}""")) {
                assertFalse(executor.execute(AiOperation(intent, JSONObject(slots), "成功", "失败")).succeeded)
            }
        }
        assertEquals(0, calls)
    }

    @Test fun temperatureIntentPassesNumbersAndDefaultOrExplicitSeatToController() = runTest {
        val calls = mutableListOf<Pair<Float, String>>()
        val executor = createExecutor(setTemperature = { temperature, seat -> calls += temperature to seat; true })
        assertTrue(executor.execute(checkNotNull(AiOperation.parse(json))).succeeded)
        assertTrue(executor.execute(checkNotNull(AiOperation.parse(json.replace("\"temperature\":28", "\"temperature\":27.5,\"seat\":\"passenger\"")))).succeeded)
        assertEquals(listOf(28f to "driver", 27.5f to "passenger"), calls)
    }

    @Test fun invalidTemperatureParametersNeverReachController() = runTest {
        var calls = 0
        val executor = createExecutor(setTemperature = { _, _ -> calls++; true })
        for (slots in listOf("{}", """{"temperature":"28"}""", """{"temperature":null}""",
            """{"temperature":28,"seat":"rear"}""", """{"temperature":28,"seat":null}""")) {
            val operation = checkNotNull(AiOperation.parse(json.replace("{\"temperature\":28}", slots)))
            assertFalse(executor.execute(operation).succeeded)
        }
        assertEquals(0, calls)
    }

    private class Tts : StreamingTts {
        val chunks = mutableListOf<String>()
        var starts = 0
        var finishes = 0
        var version = 0L
        override fun start(version: Long, speed: Int) { starts++; this.version = version }
        override fun append(delta: String) { if (delta.isNotEmpty()) chunks += delta }
        override fun finish() { finishes++ }
        override fun cancel() = Unit
    }

    @Test fun streamingJsonAndExecutionStayHiddenUntilSuccessThenSaveOnlySystemText() = runTest {
        val tts = Tts()
        val saved = linkedMapOf<String, ConversationMessage>()
        val operations = mutableListOf<AiOperation>()
        val finishResponse = CompletableDeferred<Unit>()
        val finishOperation = CompletableDeferred<Boolean>()
        var pauses = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            saveMessage = { saved[it.id] = it }, answerMode = { AnswerMode.SINGLE },
            onSingleQuestionRecognized = { pauses++ }, executeOperation = {
                operations += it
                AiOperationExecutor.Result(finishOperation.await())
            }, judgeOperation = { true }) { _, _ -> flow {
                for (size in 1..structuredJson.length) {
                    emit(ResponseProgress(" \n" + structuredJson.take(size)))
                    assertTrue(tts.chunks.isEmpty())
                    assertEquals(0, tts.starts)
                    assertTrue(operations.isEmpty())
                    assertTrue(saved.values.all { it.role == ConversationMessage.USER })
                    assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
                }
                finishResponse.await()
                emit(ResponseProgress(structuredJson, "response_json"))
                emit(ResponseProgress(structuredJson, "response_json")) // 重复完成事件也不能执行两次。
            } }
        coordinator.setEnabled(true)
        coordinator.onAsrResult(AsrSnapshot(confirmedVersion = 1, confirmedText = "把空调调到28度"))
        assertEquals(1, pauses)
        runCurrent()
        finishResponse.complete(Unit)
        runCurrent()
        assertEquals(1, operations.size)
        assertEquals(28, operations.single().slots.getInt("temperature"))
        assertTrue(tts.chunks.isEmpty())
        assertEquals(ConversationPhase.THINKING, ConversationRepository.snapshot.value.content.phase)
        finishOperation.complete(true)
        runCurrent()
        assertEquals(listOf("空调已调到28度"), tts.chunks)
        assertEquals(1, tts.finishes)
        assertEquals(1, operations.size)
        val result = saved.values.last()
        assertEquals(ConversationMessage.SYSTEM, result.role)
        assertEquals(tts.chunks.single(), result.text)
        assertNull(result.previousResponseId)
        assertEquals(result, ConversationRepository.snapshot.value.content.messages.last())
        assertEquals(1, pauses)
        coordinator.onTtsUpdate(tts.version, TtsProgress(completed = true))
        assertEquals(1, pauses)
    }

    @Test fun emptyExecutorAndThrownExecutionBothUseFailureReply() = runTest {
        for (throwError in listOf(false, true)) {
            val tts = Tts()
            val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
                executeOperation = { if (throwError) error("接口失败") else createExecutor().execute(it) }, judgeOperation = { true }) { _, _ -> flow {
                emit(ResponseProgress(structuredJson, "response_json"))
            } }
            coordinator.setEnabled(true)
            coordinator.onTextSubmitted("把空调调到28度")
            runCurrent()
            assertEquals(listOf("空调调节失败"), tts.chunks)
            assertTrue(ConversationRepository.snapshot.value.content.messages.last().isSystem)
            assertNull(ConversationRepository.snapshot.value.content.messages.last().previousResponseId)
            coordinator.stopSession()
        }
    }

    @Test fun malformedJsonHasFriendlySystemReplyAndNeverExecutesOrSpeaksJson() = runTest {
        for (invalid in listOf("{", "{}", "[]", structuredJson + "尾部文字", structuredJson.replace("\"slots\":{\"temperature\":28}", "\"slots\":[]"))) {
            val tts = Tts()
            var executions = 0
            val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
                executeOperation = { executions++; AiOperationExecutor.Result(true) }, judgeOperation = { true }) { _, _ -> flow { emit(ResponseProgress(invalid, "bad_id")) } }
            coordinator.setEnabled(true)
            coordinator.onTextSubmitted("空调")
            runCurrent()
            assertEquals(0, executions)
            assertEquals(listOf(getString(R.string.voice_operation_invalid_reply)), tts.chunks)
            assertTrue(ConversationRepository.snapshot.value.content.messages.last().isSystem)
            assertNull(ConversationRepository.snapshot.value.content.messages.last().previousResponseId)
            coordinator.stopSession()
        }
    }

    @Test fun interruptedJsonOrFailedModelNeverExecutesAndDoesNotEnterHistory() = runTest {
        val tts = Tts()
        var executions = 0
        val complete = CompletableDeferred<Unit>()
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            executeOperation = { executions++; AiOperationExecutor.Result(true) }, judgeOperation = { true }) { _, _ -> flow {
            emit(ResponseProgress(structuredJson.take(20)))
            complete.await()
            emit(ResponseProgress(structuredJson, "id", error = "网络失败"))
        } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("空调")
        runCurrent()
        coordinator.onAsrResult(AsrSnapshot(conversation = ConversationContent(liveText = "换个话题")))
        complete.complete(Unit)
        runCurrent()
        assertEquals(0, executions)
        assertTrue(tts.chunks.isEmpty())
        assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
        coordinator.onTextSubmitted("空调")
        runCurrent()
        assertEquals(0, executions)
        assertTrue(tts.chunks.isEmpty())
        assertEquals("网络失败", ConversationRepository.snapshot.value.error)
    }

    @Test fun stoppingDuringOperationCancelsPendingResultWithoutSpeakingSuccess() = runTest {
        val tts = Tts()
        val completed = CompletableDeferred<Boolean>()
        var executions = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            executeOperation = { executions++; AiOperationExecutor.Result(completed.await()) }, judgeOperation = { true }) { _, _ -> flow { emit(ResponseProgress(structuredJson, "id")) } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("空调")
        runCurrent()
        assertEquals(1, executions)
        coordinator.stopSession()
        completed.complete(true)
        runCurrent()
        assertTrue(tts.chunks.isEmpty())
        assertFalse(coordinator.hasActiveReply)
        assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
    }

    @Test fun fencedJsonPrefixesStayHiddenAndUnknownReturnsFailure() = runTest {
        assertNull(AiOperation.isJsonCandidate(" \n"))
        assertNull(AiOperation.isJsonCandidate("`"))
        assertNull(AiOperation.isJsonCandidate("``"))
        assertNull(AiOperation.isJsonCandidate("```j"))
        assertEquals(true, AiOperation.isJsonCandidate("```json\n{"))
        assertEquals(false, AiOperation.isJsonCandidate("你好"))
        assertEquals("set_temperature", AiOperation.parse("```json\n$json\n```")?.intent)
        assertNull(AiOperation.parse(json.replace("\"success_reply\":\"空调已调到28度\",", "")))
        val unknown = """{"type":"actions","actions":[{"intent":"unknown","slots":{},"success_reply":"","failure_reply":"暂不支持这个操作"}]}"""
        val tts = Tts()
        var executions = 0
        val coordinator = ConversationCoordinator(backgroundScope, getString, tts,
            executeOperation = { executions++; createExecutor().execute(it) }, judgeOperation = { true }) { _, _ -> flow {
            val fenced = "```json\n$unknown\n```"
            for (size in 1..fenced.length) {
                emit(ResponseProgress(fenced.take(size)))
                assertTrue(tts.chunks.isEmpty())
                assertTrue(ConversationRepository.snapshot.value.content.messages.all { it.role == ConversationMessage.USER })
            }
            emit(ResponseProgress(fenced, "unknown_id"))
        } }
        coordinator.setEnabled(true)
        coordinator.onTextSubmitted("空调")
        runCurrent()
        assertEquals(1, executions)
        assertEquals(listOf("暂不支持这个操作"), tts.chunks)
    }
}
