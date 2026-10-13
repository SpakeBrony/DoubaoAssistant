package com.spake.brony.doubaoassistant.car

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeelyHvacControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var car: FakeCar
    private lateinit var controller: GeelyHvacController

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        car = FakeCar()
        Factory.car = car
        controller = GeelyHvacController(context) { name ->
            when (name) {
                "com.ecarx.xui.adaptapi.car.Car" -> Factory::class.java
                "com.ecarx.xui.adaptapi.car.ICar" -> CarApi::class.java
                "com.ecarx.xui.adaptapi.car.base.ICarFunction" -> FunctionApi::class.java
                "com.ecarx.xui.adaptapi.binder.IConnectable" -> Connectable::class.java
                "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher" -> Watcher::class.java
                else -> throw ClassNotFoundException(name)
            }
        }
    }

    @After fun tearDown() {
        Factory.car = null
        Dispatchers.resetMain()
    }

    @Test fun writesDriverTemperatureUsingRealPropertyIdsAndConfirmsBeforeSuccess() = runTest {
        assertTrue(controller.setTemperature(28f, "driver"))
        assertEquals(listOf(Write(0x10060100, 1, 28f)), car.function.writes)
        assertTrue(car.function.reads.containsAll(listOf(
            0x10060200 to Int.MIN_VALUE, 0x10060300 to Int.MIN_VALUE, 0x10060400 to Int.MIN_VALUE,
            0x10060100 to 1)))
        assertEquals(1, car.unregistered)
        assertEquals(1, car.disconnected)
    }

    @Test fun passengerUsesZoneFourAndSupportsHalfDegrees() = runTest {
        assertTrue(controller.setTemperature(27.5f, "passenger"))
        assertEquals(listOf(Write(0x10060100, 4, 27.5f)), car.function.writes)
    }

    @Test fun coolingAndAutoUseIntegerSwitchesWithTheirOwnZones() = runTest {
        assertTrue(controller.setCooling(true))
        assertTrue(controller.setCooling(false))
        assertTrue(controller.setAutoMode(true))
        assertTrue(controller.setAutoMode(false))
        assertEquals(listOf(
            SwitchWrite(0x10010300, Int.MIN_VALUE, 1), SwitchWrite(0x10010300, Int.MIN_VALUE, 0),
            SwitchWrite(0x10010200, 1, 1), SwitchWrite(0x10010200, 1, 0)), car.function.switchWrites)
        assertTrue(car.function.switchReads.containsAll(listOf(0x10010300 to Int.MIN_VALUE, 0x10010200 to 1)))
        assertTrue(car.function.writes.isEmpty())
        assertEquals(4, car.disconnected)
    }

    @Test fun seatVentilationUsesEncodedLevelsForEachSeatAndZeroToClose() = runTest {
        for (seat in listOf("driver", "passenger")) {
            for (level in 1..3) assertTrue(controller.setSeatVentilation(seat, level))
            assertTrue(controller.setSeatVentilation(seat, 0))
        }
        assertEquals(listOf(
            SwitchWrite(0x10050100, 1, 0x10050101), SwitchWrite(0x10050100, 1, 0x10050102),
            SwitchWrite(0x10050100, 1, 0x10050103), SwitchWrite(0x10050100, 1, 0),
            SwitchWrite(0x10050100, 4, 0x10050101), SwitchWrite(0x10050100, 4, 0x10050102),
            SwitchWrite(0x10050100, 4, 0x10050103), SwitchWrite(0x10050100, 4, 0)), car.function.switchWrites)
        assertTrue(car.function.writes.isEmpty())
        assertEquals(8, car.disconnected)
    }

    @Test fun manualFanUsesFrontZoneAndEncodedLevelsWithoutChangingAutoSwitch() = runTest {
        for (level in 1..9) assertTrue(controller.setFanSpeed(level))
        assertEquals((1..9).map { SwitchWrite(0x10020100, 8, 0x10020100 + it) }, car.function.switchWrites)
        assertTrue(car.function.switchReads.contains(0x10020100 to 8))
        assertEquals(9, car.disconnected)
    }

    @Test fun autoFanUsesOriginalNonSequentialFiveLevelMappingAndRejectsManualOnlyLevels() = runTest {
        car.function.setInitialValue(0x10020200, 8, 0x10020201)
        for (level in 1..5) assertTrue(controller.setFanSpeed(level))
        assertFalse(controller.setFanSpeed(6))
        assertFalse(controller.setFanSpeed(9))
        assertEquals(listOf(4, 1, 2, 3, 5).map { SwitchWrite(0x10020200, 8, 0x10020200 + it) }, car.function.switchWrites)
        assertEquals(7, car.disconnected)
    }

    @Test fun relativeFanReadsCurrentManualLevelAndRejectsBoundsOrUnknownReadback() = runTest {
        car.function.setInitialValue(0x10020100, 8, 0x10020103)
        assertTrue(controller.adjustFanSpeed("increase", 2))
        assertTrue(controller.adjustFanSpeed("decrease", 4))
        assertFalse(controller.adjustFanSpeed("decrease", 1))
        car.function.setInitialValue(0x10020100, 8, 0x10020109)
        assertFalse(controller.adjustFanSpeed("increase", 1))
        car.function.setInitialValue(0x10020100, 8, 0)
        assertFalse(controller.adjustFanSpeed("increase", 1))
        assertEquals(listOf(SwitchWrite(0x10020100, 8, 0x10020105), SwitchWrite(0x10020100, 8, 0x10020101)), car.function.switchWrites)
    }

    @Test fun relativeAutoFanAdjustsLogicalLevelRatherThanRawEncodedValue() = runTest {
        car.function.setInitialValue(0x10020200, 8, 0x10020204) // AUTO 一档。
        assertTrue(controller.adjustFanSpeed("increase", 1))
        assertTrue(controller.adjustFanSpeed("increase", 3))
        assertFalse(controller.adjustFanSpeed("increase", 1))
        assertTrue(controller.adjustFanSpeed("decrease", 4))
        assertFalse(controller.adjustFanSpeed("decrease", 1))
        assertEquals(listOf(1, 5, 4).map { SwitchWrite(0x10020200, 8, 0x10020200 + it) }, car.function.switchWrites)
    }

    @Test fun unsupportedFanValuesNeverWriteAndRejectedWritesNeverReportSuccess() = runTest {
        car.function.fanSupported = intArrayOf(0x10020101, 0x10020102)
        assertFalse(controller.setFanSpeed(3))
        car.function.fanSupported = null
        assertFalse(controller.setFanSpeed(1))
        car.function.setInitialValue(0x10020200, 8, 0x10020204)
        car.function.autoFanSupported = intArrayOf(0x10020204)
        assertFalse(controller.setFanSpeed(2))
        assertTrue(car.function.switchWrites.isEmpty())
        car.function.accepted = false
        assertFalse(controller.setFanSpeed(1))
        assertEquals(listOf(SwitchWrite(0x10020200, 8, 0x10020204)), car.function.switchWrites)
        assertEquals(4, car.disconnected)
    }

    @Test fun invalidFanArgumentsNeverOpenConnection() = runTest {
        for (level in listOf(-1, 0, 10)) assertFalse(controller.setFanSpeed(level))
        for (amount in listOf(-1, 0, 9)) assertFalse(controller.adjustFanSpeed("increase", amount))
        assertFalse(controller.adjustFanSpeed("up", 1))
        assertTrue(car.function.switchWrites.isEmpty())
        assertEquals(0, car.disconnected)
    }

    @Test fun seatHeatingUsesItsOwnEncodedLevelsForEachSeatAndZeroToClose() = runTest {
        for (seat in listOf("driver", "passenger")) {
            for (level in 1..3) assertTrue(controller.setSeatHeating(seat, level))
            assertTrue(controller.setSeatHeating(seat, 0))
        }
        assertEquals(listOf(
            SwitchWrite(0x10050200, 1, 0x10050201), SwitchWrite(0x10050200, 1, 0x10050202),
            SwitchWrite(0x10050200, 1, 0x10050203), SwitchWrite(0x10050200, 1, 0),
            SwitchWrite(0x10050200, 4, 0x10050201), SwitchWrite(0x10050200, 4, 0x10050202),
            SwitchWrite(0x10050200, 4, 0x10050203), SwitchWrite(0x10050200, 4, 0)), car.function.switchWrites)
        assertTrue(car.function.writes.isEmpty())
        assertEquals(8, car.disconnected)
    }

    @Test fun manualHeatingDisablesTargetHeatingAutoUsingHeatingProperty() = runTest {
        car.function.setInitialValue(0x10141300, 4, 1)
        car.function.setInitialValue(0x10141200, 4, 1)
        assertTrue(controller.setSeatHeating("passenger", 2))
        assertEquals(listOf(SwitchWrite(0x10141300, 4, 0), SwitchWrite(0x10050200, 4, 0x10050202)), car.function.switchWrites)
        assertEquals(1, car.function.getFunctionValue(0x10141200, 4))
        assertEquals(1, car.disconnected)
    }

    @Test fun manualVentilationDisablesOnlyTheTargetSeatsAutoModeFirst() = runTest {
        car.function.setInitialValue(0x10141200, 1, 1)
        car.function.setInitialValue(0x10141200, 4, 1)
        assertTrue(controller.setSeatVentilation("driver", 2))
        assertEquals(listOf(SwitchWrite(0x10141200, 1, 0), SwitchWrite(0x10050100, 1, 0x10050102)), car.function.switchWrites)
        assertEquals(1, car.function.getFunctionValue(0x10141200, 4))
        assertTrue(controller.setSeatVentilation("passenger", 0))
        assertEquals(listOf(SwitchWrite(0x10141200, 4, 0), SwitchWrite(0x10050100, 4, 0)), car.function.switchWrites.takeLast(2))
    }

    @Test fun invalidSeatAndLevelNeverWriteSeatControls() = runTest {
        assertFalse(controller.setSeatVentilation("rear", 1))
        assertFalse(controller.setSeatVentilation("driver", -1))
        assertFalse(controller.setSeatVentilation("passenger", 4))
        assertFalse(controller.setSeatHeating("rear", 1))
        assertFalse(controller.setSeatHeating("driver", -1))
        assertFalse(controller.setSeatHeating("passenger", 4))
        assertTrue(car.function.switchWrites.isEmpty())
        assertEquals(0, car.disconnected)
    }

    @Test fun rejectedVentilationAndAutoDisableNeverReportSuccess() = runTest {
        car.function.accepted = false
        assertFalse(controller.setSeatVentilation("driver", 3))
        assertEquals(listOf(SwitchWrite(0x10050100, 1, 0x10050103)), car.function.switchWrites)
        car.function.setInitialValue(0x10141200, 4, 1)
        assertFalse(controller.setSeatVentilation("passenger", 1))
        assertEquals(SwitchWrite(0x10141200, 4, 0), car.function.switchWrites.last())
        assertEquals(2, car.function.switchWrites.size)
        car.function.permissionDenied = true
        assertFalse(controller.setSeatVentilation("driver", 0))
        assertEquals(3, car.disconnected)
    }

    @Test fun acceptedVentilationWithoutReadbackConfirmationFails() = runTest {
        car.function.updateValue = false
        assertFalse(controller.setSeatVentilation("driver", 1))
        assertEquals(listOf(SwitchWrite(0x10050100, 1, 0x10050101)), car.function.switchWrites)
        assertEquals(1, car.disconnected)
    }

    @Test fun rejectedOrDeniedSwitchWriteReturnsFailureAndReleasesConnection() = runTest {
        car.function.accepted = false
        assertFalse(controller.setCooling(true))
        assertFalse(controller.setAutoMode(true))
        assertTrue(car.function.switchReads.isEmpty())
        car.function.permissionDenied = true
        assertFalse(controller.setCooling(false))
        assertFalse(controller.setAutoMode(false))
        assertEquals(4, car.unregistered)
        assertEquals(4, car.disconnected)
    }

    @Test fun acceptedSwitchWithoutMatchingReadbackFails() = runTest {
        car.function.updateValue = false
        assertFalse(controller.setAutoMode(true))
        assertEquals(listOf(SwitchWrite(0x10010200, 1, 1)), car.function.switchWrites)
        assertEquals(1, car.disconnected)
    }

    @Test fun cancellingSwitchConfirmationReleasesConnection() = runTest {
        car.function.updateValue = false
        val result = async { controller.setCooling(true) }
        car.function.switchWritten.await()
        result.cancelAndJoin()
        assertEquals(1, car.function.switchWrites.size)
        assertEquals(1, car.unregistered)
        assertEquals(1, car.disconnected)
    }

    @Test fun rejectsTemperatureOutsideVehicleRangeOrStepAndUnsupportedSeats() = runTest {
        for (temperature in listOf(15f, 33f, 28.25f)) assertFalse(controller.setTemperature(temperature, "driver"))
        assertFalse(controller.setTemperature(Float.NaN, "driver"))
        assertFalse(controller.setTemperature(28f, "rear"))
        assertTrue(car.function.writes.isEmpty())
        assertEquals(3, car.disconnected)
        // 上下限来自车机；边界值本身允许执行。
        assertTrue(controller.setTemperature(16f, "driver"))
        assertTrue(controller.setTemperature(32f, "driver"))
    }

    @Test fun rejectedWriteOrPermissionErrorReturnsFailureAndClosesConnection() = runTest {
        car.function.accepted = false
        assertFalse(controller.setTemperature(28f, "driver"))
        assertFalse(car.function.reads.contains(0x10060100 to 1))
        car.function.permissionDenied = true
        assertFalse(controller.setTemperature(28f, "driver"))
        assertEquals(2, car.unregistered)
        assertEquals(2, car.disconnected)
    }

    @Test fun acceptedWriteWithoutReadbackConfirmationStillFails() = runTest {
        car.function.updateValue = false
        assertFalse(controller.setTemperature(28f, "driver"))
        assertEquals(1, car.function.writes.size)
        assertTrue(car.function.reads.contains(0x10060100 to 1))
        assertEquals(1, car.disconnected)
    }

    @Test fun missingSdkAndNullFactoryAreHandledWithoutCrashing() = runTest {
        val missingSdk = GeelyHvacController(context) { throw ClassNotFoundException(it) }
        assertFalse(missingSdk.setTemperature(28f, "driver"))
        assertFalse(missingSdk.setCooling(true))
        assertFalse(missingSdk.setAutoMode(false))
        assertFalse(missingSdk.setFanSpeed(3))
        assertFalse(missingSdk.adjustFanSpeed("increase", 1))
        assertFalse(missingSdk.setSeatVentilation("driver", 3))
        assertFalse(missingSdk.setSeatHeating("driver", 3))
        Factory.car = null
        assertFalse(controller.setTemperature(28f, "driver"))
    }

    @Test fun cancellingWhileConnectingNeverWritesAndReleasesWatcher() = runTest {
        car.connectImmediately = false
        val result = async { controller.setTemperature(28f, "driver") }
        car.registered.await()
        result.cancelAndJoin()
        assertTrue(car.function.writes.isEmpty())
        assertEquals(1, car.unregistered)
        assertEquals(1, car.disconnected)
    }

    // 只在 JVM 测试中提供替身；生产 APK 不包含这些类，也不包含返回 null 的 SDK 桩。
    class Factory {
        companion object {
            var car: FakeCar? = null
            @JvmStatic fun create(context: Context): CarApi? = car
        }
    }

    interface CarApi { fun getICarFunction(): FunctionApi }
    interface Watcher { fun onConnected(); fun onDisConnected() }
    interface Connectable {
        fun registerConnectWatcher(watcher: Watcher)
        fun unregisterConnectWatcher()
        fun disconnect()
    }
    interface FunctionApi {
        fun getCustomizeFunctionValue(property: Int, zone: Int): Float
        fun setCustomizeFunctionValue(property: Int, zone: Int, value: Float): Boolean
        fun getFunctionValue(property: Int, zone: Int): Int
        fun setFunctionValue(property: Int, zone: Int, value: Int): Boolean
        fun getSupportedFunctionValue(property: Int, zone: Int): IntArray?
    }

    class FakeCar : CarApi, Connectable {
        val function = FakeFunction()
        val registered = CompletableDeferred<Unit>()
        var connectImmediately = true
        var unregistered = 0
        var disconnected = 0
        override fun getICarFunction(): FunctionApi = function
        override fun registerConnectWatcher(watcher: Watcher) {
            registered.complete(Unit)
            if (connectImmediately) watcher.onConnected()
        }
        override fun unregisterConnectWatcher() { unregistered++ }
        override fun disconnect() { disconnected++ }
    }

    data class Write(val property: Int, val zone: Int, val temperature: Float)
    data class SwitchWrite(val property: Int, val zone: Int, val value: Int)
    class FakeFunction : FunctionApi {
        val writes = mutableListOf<Write>()
        val reads = mutableListOf<Pair<Int, Int>>()
        val switchWrites = mutableListOf<SwitchWrite>()
        val switchReads = mutableListOf<Pair<Int, Int>>()
        val switchWritten = CompletableDeferred<Unit>()
        var accepted = true
        var updateValue = true
        var permissionDenied = false
        var fanSupported: IntArray? = IntArray(9) { 0x10020101 + it }
        var autoFanSupported: IntArray? = IntArray(5) { 0x10020201 + it }
        private val temperatures = mutableMapOf<Int, Float>()
        private val switches = mutableMapOf<Pair<Int, Int>, Int>()
        fun setInitialValue(property: Int, zone: Int, value: Int) { switches[property to zone] = value }
        override fun getSupportedFunctionValue(property: Int, zone: Int): IntArray? {
            check(zone == 8) { "Fan must use front zone 8" }
            return when (property) {
                0x10020100 -> fanSupported
                0x10020200 -> autoFanSupported
                else -> error("Unexpected property $property")
            }
        }
        override fun getFunctionValue(property: Int, zone: Int): Int {
            switchReads += property to zone
            return switches[property to zone] ?: 0
        }
        override fun setFunctionValue(property: Int, zone: Int, value: Int): Boolean {
            if (permissionDenied) throw SecurityException("Vehicle permission denied")
            switchWrites += SwitchWrite(property, zone, value)
            if (accepted && updateValue) switches[property to zone] = value
            switchWritten.complete(Unit)
            return accepted
        }
        override fun getCustomizeFunctionValue(property: Int, zone: Int): Float {
            reads += property to zone
            return when (property) {
                0x10060200 -> 32f
                0x10060300 -> 16f
                0x10060400 -> 0.5f
                0x10060100 -> temperatures[zone] ?: 22f
                else -> error("Unexpected property $property")
            }
        }
        override fun setCustomizeFunctionValue(property: Int, zone: Int, value: Float): Boolean {
            if (permissionDenied) throw SecurityException("Vehicle permission denied")
            writes += Write(property, zone, value)
            if (accepted && updateValue) temperatures[zone] = value
            return accepted
        }
    }
}
