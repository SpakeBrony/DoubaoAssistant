package com.spake.brony.doubaoassistant.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.AssistantName
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.databinding.DialogAssistantNameBinding
import com.spake.brony.doubaoassistant.speech.kws.KwsKeywords
import com.spake.brony.doubaoassistant.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantNameDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAssistantNameBinding.inflate(LayoutInflater.from(requireContext()))
        binding.nameInput.setText(AssistantPreferences(requireContext()).assistantName)
        binding.nameInput.selectAll()
        val assets = requireContext().applicationContext.assets
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_name_dialog_title)
            .setView(binding.root)
            .setNegativeButton(R.string.voice_cancel, null)
            .setPositiveButton(R.string.voice_confirm, null)
            .create().apply {
                setOnShowListener {
                    binding.nameInput.requestFocus()
                    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    val confirm = getButton(AlertDialog.BUTTON_POSITIVE)
                    confirm.setOnClickListener {
                        val name = binding.nameInput.text.toString().trim()
                        if (!AssistantName.isValid(name)) {
                            binding.nameInput.error = getString(R.string.voice_name_invalid)
                            return@setOnClickListener
                        }
                        confirm.isEnabled = false
                        lifecycleScope.launch {
                            try {
                                val supported = withContext(Dispatchers.IO) { KwsKeywords.create(assets, name) != null }
                                if (!isShowing) return@launch
                                if (supported) {
                                    (activity as? MainActivity)?.confirmAssistantName(name)
                                    dismiss()
                                } else binding.nameInput.error = getString(R.string.voice_name_unsupported)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                Log.e("AssistantName", "Failed to prepare nickname keywords", e)
                                binding.nameInput.error = getString(R.string.voice_name_failed)
                            } finally { confirm.isEnabled = true }
                        }
                    }
                    binding.nameInput.setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_DONE) {
                            if (confirm.isEnabled) confirm.performClick()
                            true
                        } else false
                    }
                }
            }
    }
}
