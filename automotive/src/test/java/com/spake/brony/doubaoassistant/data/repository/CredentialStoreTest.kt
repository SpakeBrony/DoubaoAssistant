package com.spake.brony.doubaoassistant.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.spake.brony.doubaoassistant.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CredentialStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        TestCredentialKeys.install()
        context.getSharedPreferences("credentials", 0).edit().clear().commit()
    }

    @Test fun defaultsRequireConfirmationAndGeneralBuildHasNoEmbeddedCredentials() {
        val store = CredentialStore(context)
        assertFalse(store.isConfigured)
        val defaults = store.load()
        assertEquals(BuildConfig.DOUBAO_ASR_API_KEY, defaults.apiKey)
        if (BuildConfig.FLAVOR == "general") assertEquals(ServiceCredentials(), defaults)
    }

    @Test fun encryptedPreferencesSurviveNewStoreAndCorruptionRequiresReconfiguration() {
        val value = ServiceCredentials(apiKey = "speech-test-key", arkApiKey = "ark-test-key")
        assertTrue(CredentialStore(context).save(value))
        val prefs = context.getSharedPreferences("credentials", 0)
        val payload = prefs.getString("payload", "").orEmpty()
        assertFalse(payload.contains(value.apiKey))
        assertFalse(payload.contains(value.arkApiKey))
        assertEquals(value, CredentialStore(context).load())
        assertTrue(CredentialStore(context).isConfigured)
        assertTrue(CredentialStore(context).save(value))
        assertNotEquals(payload, prefs.getString("payload", ""))
        prefs.edit().putString("payload", "corrupted").commit()
        assertFalse(CredentialStore(context).isConfigured)
        assertEquals(ServiceCredentials(), CredentialStore(context).load())
    }

    @Test fun bothAuthModesNeedArkKeyAndRejectWhitespaceAndInvalidPreferredApiKey() {
        assertTrue(ServiceCredentials(apiKey = "speech", arkApiKey = "ark").ready)
        assertTrue(ServiceCredentials(appId = "123", accessToken = "token", arkApiKey = "ark").ready)
        assertFalse(ServiceCredentials(apiKey = "speech").ready)
        assertFalse(ServiceCredentials(appId = "123", arkApiKey = "ark").ready)
        assertFalse(ServiceCredentials(apiKey = "bad\nkey", appId = "123", accessToken = "token", arkApiKey = "ark").ready)
        assertFalse(ServiceCredentials(apiKey = "speech", arkApiKey = "bad key").ready)
        val value = ServiceCredentials(apiKey = "secret-speech", arkApiKey = "secret-ark")
        assertFalse(value.toString().contains("secret"))
        assertEquals("[已隐藏] [已隐藏]", value.redact("secret-speech secret-ark"))
    }

    @Test fun failedCommitDoesNotReplacePreviouslySavedCredentialsInMemory() {
        val original = ServiceCredentials(apiKey = "saved-speech", arkApiKey = "saved-ark")
        assertTrue(CredentialStore(context).save(original))
        val preferences = context.getSharedPreferences("credentials", 0)
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val wrapper = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = failing
        }
        assertFalse(CredentialStore(wrapper).save(ServiceCredentials(apiKey = "new-speech", arkApiKey = "new-ark")))
        assertEquals(original, CredentialStore(context).load())
    }
}
