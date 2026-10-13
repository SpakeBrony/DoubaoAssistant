package com.spake.brony.doubaoassistant.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.databinding.DialogMessageInputBinding
import com.spake.brony.doubaoassistant.ui.MainActivity

class MessageInputDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogMessageInputBinding.inflate(LayoutInflater.from(requireContext()))
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_message_input)
            .setView(binding.root)
            .setNegativeButton(R.string.voice_cancel, null)
            .setPositiveButton(R.string.voice_message_send, null)
            .create().apply {
                setOnShowListener {
                    binding.messageInput.requestFocus()
                    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    val send = getButton(AlertDialog.BUTTON_POSITIVE)
                    send.setOnClickListener {
                        val text = binding.messageInput.text.toString().trim()
                        if (text.isEmpty()) {
                            binding.messageInput.error = getString(R.string.voice_message_required)
                        } else if ((activity as? MainActivity)?.sendTypedMessage(text) == true) {
                            dismiss()
                        } else {
                            binding.messageInput.error = getString(R.string.chat_request_failed)
                        }
                    }
                    binding.messageInput.setOnEditorActionListener { _, action, event ->
                        when {
                            action == EditorInfo.IME_ACTION_SEND -> { send.performClick(); true }
                            event?.keyCode == KeyEvent.KEYCODE_ENTER -> {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send.performClick()
                                true
                            }
                            else -> false
                        }
                    }
                }
            }
    }
}
