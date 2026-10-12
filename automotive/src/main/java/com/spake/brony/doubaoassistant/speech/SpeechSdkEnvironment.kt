package com.spake.brony.doubaoassistant.speech

import android.app.Application
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import com.bytedance.speech.speechengine.SpeechEngineDefines as Sdk

internal object SpeechSdkEnvironment {
    private var prepared = false

    fun checkCode(code: Int, operation: String) {
        check(code == Sdk.ERR_NO_ERROR) { "SDK $operation 返回码：$code" }
    }

    fun checkCreatedEngine(handle: Long) {
        // SDK 的无效句柄是 -1；0 是可用句柄，不是 initEngine 的错误码。
        check(handle != -1L) { "SDK createEngine() 返回句柄：$handle" }
    }

    @Synchronized fun prepare(application: Application) {
        if (!prepared) {
            check(SpeechEngineGenerator.PrepareEnvironment(application.applicationContext, application)) {
                "SDK PrepareEnvironment() 返回值：false"
            }
            prepared = true
        }
    }
}
