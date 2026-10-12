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
        assertFalse(GeelyHvacController(context) { throw ClassNotFoundException(it) }.setTemperature(28f, "driver"))
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
    class FakeFunction : FunctionApi {
        val writes = mutableListOf<Write>()
        val reads = mutableListOf<Pair<Int, Int>>()
        var accepted = true
        var updateValue = true
        var permissionDenied = false
        private val temperatures = mutableMapOf<Int, Float>()
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
