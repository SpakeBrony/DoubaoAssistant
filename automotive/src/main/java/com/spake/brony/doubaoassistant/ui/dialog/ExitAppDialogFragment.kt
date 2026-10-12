package com.spake.brony.doubaoassistant.ui.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.ui.MainActivity

class ExitAppDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_exit_title)
        .setMessage(R.string.voice_exit_message)
        .setNegativeButton(R.string.voice_cancel, null)
        .setPositiveButton(R.string.voice_confirm) { _, _ ->
            (activity as? MainActivity)?.confirmExitApp()
        }
        .create()
}
