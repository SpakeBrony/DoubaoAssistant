package com.spake.brony.doubaoassistant.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.data.repository.CredentialStore
import com.spake.brony.doubaoassistant.data.repository.ServiceCredentials
import com.spake.brony.doubaoassistant.databinding.ActivityPermissionBinding
import com.spake.brony.doubaoassistant.ui.base.BaseActivity
import com.spake.brony.doubaoassistant.viewmodel.PermissionViewModel

class PermissionActivity : BaseActivity() {
    private val viewModel: PermissionViewModel by viewModels()
    private val preferences by lazy { AssistantPreferences(this) }
    private val credentials by lazy { CredentialStore(this) }
    private lateinit var binding: ActivityPermissionBinding
    private val editing: Boolean get() = intent.getBooleanExtra(EXTRA_EDIT_CREDENTIALS, false)
    private var enteringHome = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermission()
        maybeEnterHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (canEnterHome()) { enterHome(); return }
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.lifecycleOwner = this
        binding.viewModel = viewModel
        binding.actions = this
        if (!viewModel.formLoaded) {
            viewModel.draft = credentials.load()
            viewModel.formLoaded = true
        }
        binding.speechAppId.setText(viewModel.draft.appId)
        binding.speechAccessToken.setText(viewModel.draft.accessToken)
        binding.arkApiKey.setText(viewModel.draft.arkApiKey)
        binding.speechAppId.addTextChangedListener { viewModel.submitted = false; viewModel.draft = viewModel.draft.copy(appId = it.toString()) }
        binding.speechAccessToken.addTextChangedListener { viewModel.submitted = false; viewModel.draft = viewModel.draft.copy(accessToken = it.toString()) }
        binding.arkApiKey.addTextChangedListener { viewModel.submitted = false; viewModel.draft = viewModel.draft.copy(arkApiKey = it.toString()) }
        refreshPermission()
        binding.root.doOnLayout {
            val density = resources.displayMetrics.density
            val width = it.width / density
            val height = it.height / density
            val compact = height < 540
            val ultraWide = width / height > 2.3f
            fun dp(value: Float) = (value * density + .5f).toInt()
            fun TextView.size(value: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
            fun android.view.View.bottom(value: Float) = updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = dp(value) }
            binding.onboardTitle.size(if (compact) 28f else 34f)
            binding.onboardTitle.typeface = Typeface.create(binding.onboardTitle.typeface, 450, false)
            binding.onboardTitle.bottom(if (compact || ultraWide) 12f else 18f)
            binding.onboardBody.size(16f)
            binding.onboardBody.setFallbackLineSpacing(false)
            binding.onboardBody.setLineHeight((binding.onboardBody.textSize * 1.5f + .5f).toInt())
            binding.onboardBody.bottom(if (compact || ultraWide) 18f else 28f)
            binding.permissionActionLabel.size((width * .0125f).coerceIn(18f, 24f))
            val actionPadding = if (compact) 14f else if (ultraWide) 17f else 23f
            binding.permissionAction.setPadding(dp(if (ultraWide) 32f else 36f), dp(actionPadding), dp(if (ultraWide) 32f else 36f), dp(actionPadding))
            binding.permissionAction.minimumHeight = dp(if (compact) 52f else if (ultraWide) 58f else 70f)
            binding.permissionAction.updateLayoutParams { this.width = dp(270f.coerceAtMost(width - 64f)) }
            binding.onboardNote.size((width * .009f).coerceIn(12f, 17f))
            binding.onboardNote.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = dp(if (compact || ultraWide) 10f else 24f) }
            binding.onboarding.setPadding(dp(32f), dp(if (compact) 16f else 32f), dp(32f), dp(if (compact) 16f else 32f))
            listOf(binding.onboardTitle, binding.onboardBody, binding.permissionHelp).forEach { text -> text.updateLayoutParams { this.width = dp((width - 64f).coerceAtMost(850f)) } }
            binding.credentialsForm.updateLayoutParams { this.width = dp((width - 64f).coerceAtMost(640f)) }
            binding.invalidateAll()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
        maybeEnterHome()
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun canEnterHome() = hasMicrophone() && credentials.isConfigured &&
        ((!editing && !viewModel.formLoaded) || viewModel.submitted)

    private fun maybeEnterHome() { if (canEnterHome()) enterHome() }

    private fun refreshPermission() {
        viewModel.onPermissionChanged(hasMicrophone(), preferences.microphoneRequested, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
    }

    private fun enterHome() {
        if (enteringHome || isFinishing) return
        enteringHome = true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_START, true))
        finish()
    }

    fun requestMicrophone() {
        val value = ServiceCredentials(
            appId = binding.speechAppId.text.toString().trim(),
            accessToken = binding.speechAccessToken.text.toString().trim(),
            arkApiKey = binding.arkApiKey.text.toString().trim(),
        )
        if (!value.ready) {
            viewModel.onSettingsFailed(getString(R.string.voice_credentials_invalid))
            return
        }
        if (!credentials.save(value)) {
            viewModel.onSettingsFailed(getString(R.string.voice_credentials_save_failed))
            return
        }
        viewModel.submitted = true
        viewModel.clearError()
        if (hasMicrophone()) { enterHome(); return }
        if (viewModel.uiState.value.openSettings) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            } catch (_: ActivityNotFoundException) {
                viewModel.onSettingsFailed(getString(R.string.voice_settings_unavailable))
            }
            return
        }
        preferences.microphoneRequested = true
        val missing = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_EDIT_CREDENTIALS = "com.spake.brony.doubaoassistant.EDIT_CREDENTIALS"
    }
}
