package com.spake.brony.doubaoassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 电话和天气共用授权入口；实际操作由原协程执行，取消回答后不重放。 */
class PhonePermissionActivity : AppCompatActivity() {
    private val preferences by lazy { AssistantPreferences(this) }
    private val requestId: String? get() = intent.getStringExtra(EXTRA_REQUEST_ID)
    private val required: List<String> get() = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        .orEmpty().filter { it == Manifest.permission.CALL_PHONE || it == Manifest.permission.READ_CONTACTS ||
            it == Manifest.permission.ACCESS_COARSE_LOCATION }.distinct()
    private val locationRequest: Boolean get() = Manifest.permission.ACCESS_COARSE_LOCATION in required
    private var settingsPromptVisible = false
    private var completionHandle: DisposableHandle? = null
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishWithResult() }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when {
            missing().isEmpty() -> finishWithResult()
            missing().any(::permanentlyDenied) -> showSettingsPrompt()
            else -> {
                Toast.makeText(this, if (locationRequest) R.string.weather_location_permission_denied
                    else R.string.phone_permission_denied, Toast.LENGTH_LONG).show()
                finishWithResult()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = pending[requestId]
        if (result == null || !result.isActive || required.isEmpty()) { finish(); return }
        completionHandle = result.invokeOnCompletion { runOnUiThread { if (!isFinishing) finish() } }
        if (savedInstanceState?.getBoolean("settings_prompt") == true) showSettingsPrompt()
        else if (savedInstanceState == null) {
            val missing = missing()
            when {
                missing.isEmpty() -> finishWithResult()
                missing.any(::permanentlyDenied) -> showSettingsPrompt()
                else -> {
                    if (Manifest.permission.CALL_PHONE in missing) preferences.callPhoneRequested = true
                    if (Manifest.permission.READ_CONTACTS in missing) preferences.contactsRequested = true
                    if (locationRequest) preferences.locationRequested = true
                    // Android 12+ 必须与近似位置一起申请精确位置；只授予近似位置也算成功。
                    permissions.launch((missing + if (locationRequest) listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        else emptyList()).toTypedArray())
                }
            }
        }
    }

    private fun missing() = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun permanentlyDenied(permission: String): Boolean {
        val requested = when (permission) {
            Manifest.permission.CALL_PHONE -> preferences.callPhoneRequested
            Manifest.permission.ACCESS_COARSE_LOCATION -> preferences.locationRequested
            else -> preferences.contactsRequested
        }
        return requested && !shouldShowRequestPermissionRationale(permission)
    }

    private fun showSettingsPrompt() {
        settingsPromptVisible = true
        AlertDialog.Builder(this)
            .setTitle(if (locationRequest) R.string.weather_location_title else R.string.phone_contacts_title)
            .setMessage(if (locationRequest) R.string.weather_location_permission_settings else R.string.phone_permission_settings)
            .setPositiveButton(R.string.voice_settings_action) { _, _ ->
                settingsPromptVisible = false
                if (pending[requestId]?.isActive != true) { finish(); return@setPositiveButton }
                try {
                    settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                } catch (_: RuntimeException) {
                    Toast.makeText(this, R.string.voice_settings_unavailable, Toast.LENGTH_LONG).show()
                    finishWithResult()
                }
            }
            .setNegativeButton(R.string.voice_cancel) { _, _ -> finishWithResult() }
            .setOnCancelListener { finishWithResult() }
            .show()
    }

    private fun finishWithResult() {
        pending[requestId]?.complete(required.isNotEmpty() && missing().isEmpty())
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settings_prompt", settingsPromptVisible)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        completionHandle?.dispose()
        if (isFinishing) pending[requestId]?.complete(false)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "phone_permission_request_id"
        private const val EXTRA_PERMISSIONS = "phone_permissions"
        // 仅主线程访问；进程重启后丢弃待授权请求，避免旧操作自动重放。
        private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()
        internal val hasPendingRequest: Boolean get() = pending.values.any { it.isActive }

        internal suspend fun request(context: Context, permissions: List<String>): Boolean = withContext(Dispatchers.Main.immediate) {
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<Boolean>()
            pending[id] = result
            try {
                try {
                    context.startActivity(Intent(context, PhonePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_REQUEST_ID, id)
                        .putExtra(EXTRA_PERMISSIONS, permissions.toTypedArray()))
                } catch (error: RuntimeException) {
                    Log.w("PhonePermission", "Cannot open permission request", error)
                    return@withContext false
                }
                // 系统若拦截后台弹窗，也不能让当前模型请求无限等待。
                withTimeoutOrNull(120_000) { result.await() } ?: false
            } finally {
                pending.remove(id)
                result.cancel()
            }
        }
    }
}
