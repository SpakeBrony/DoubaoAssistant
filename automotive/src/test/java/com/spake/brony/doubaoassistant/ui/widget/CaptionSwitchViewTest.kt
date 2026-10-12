package com.spake.brony.doubaoassistant.ui.widget

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.databinding.PanelSettingsBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class CaptionSwitchViewTest {
    @Test fun bothSettingsSwitchesRequestRedrawOnClickAndBoundStateChanges() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DoubaoAssistant)
        val binding = PanelSettingsBinding.inflate(LayoutInflater.from(context), FrameLayout(context), false)
        try {
            binding.executePendingBindings()
            for (view in listOf(binding.captionsSwitch, binding.backgroundChatSwitch)) {
                view.isChecked = false
                shadowOf(view).clearWasInvalidated()
                view.performClick()
                assertTrue(view.isChecked)
                assertTrue("Click must redraw the custom switch", shadowOf(view).wasInvalidated())

                shadowOf(view).clearWasInvalidated()
                view.isChecked = false
                assertFalse(view.isChecked)
                assertTrue("Binding updates must redraw the custom switch", shadowOf(view).wasInvalidated())
            }
        } finally { binding.unbind() }
    }
}
