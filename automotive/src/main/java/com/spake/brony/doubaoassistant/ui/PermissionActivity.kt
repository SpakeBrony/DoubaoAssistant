package com.spake.brony.doubaoassistant.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
        if (canEnterHome()) {
            enterHome()
            return
        }
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
        binding.speechAppId.addTextChangedListener {
            viewModel.submitted = false
            viewModel.draft = viewModel.draft.copy(appId = it.toString())
        }
        binding.speechAccessToken.addTextChangedListener {
            viewModel.submitted = false
            viewModel.draft = viewModel.draft.copy(accessToken = it.toString())
        }
        binding.arkApiKey.addTextChangedListener {
            viewModel.submitted = false
            viewModel.draft = viewModel.draft.copy(arkApiKey = it.toString())
        }
        refreshPermission()
        binding.applyDesignDimensions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
        maybeEnterHome()
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun canEnterHome() = hasMicrophone() && credentials.isConfigured &&
        ((!editing && !viewModel.formLoaded) || viewModel.submitted)

    private fun maybeEnterHome() {
        if (canEnterHome()) enterHome()
    }

    private fun refreshPermission() {
        viewModel.onPermissionChanged(
            hasMicrophone(),
            preferences.microphoneRequested,
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
        )
    }

    private fun enterHome() {
        if (enteringHome || isFinishing) return
        enteringHome = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_AUTO_START, true),
        )
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
        if (hasMicrophone()) {
            enterHome()
            return
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_EDIT_CREDENTIALS = "com.spake.brony.doubaoassistant.EDIT_CREDENTIALS"
    }
}
