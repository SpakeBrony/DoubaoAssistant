package com.spake.brony.doubaoassistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SpeechSdkEnvironmentTest {
    @Test fun reportsActualSynchronousReturnCodeAndOperation() {
        SpeechSdkEnvironment.checkCode(0, "initEngine()")
        try {
            SpeechSdkEnvironment.checkCode(-1101, "initEngine()")
            fail("Non-zero SDK return code must be reported")
        } catch (e: IllegalStateException) {
            assertEquals("SDK initEngine() 返回码：-1101", e.message)
        }
    }

    @Test fun zeroAndPositiveEngineHandlesAreAccepted() {
        SpeechSdkEnvironment.checkCreatedEngine(0L)
        SpeechSdkEnvironment.checkCreatedEngine(1L)
        SpeechSdkEnvironment.checkCreatedEngine(Long.MAX_VALUE)
    }

    @Test fun invalidHandleIsRejectedAndReported() {
        try {
            SpeechSdkEnvironment.checkCreatedEngine(-1L)
            fail("Invalid SDK handle must not be accepted")
        } catch (e: IllegalStateException) {
            assertEquals("SDK createEngine() 返回句柄：-1", e.message)
        }
    }
}
