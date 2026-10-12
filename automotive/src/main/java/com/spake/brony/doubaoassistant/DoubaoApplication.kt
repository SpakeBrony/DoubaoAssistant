package com.spake.brony.doubaoassistant

import android.app.Application
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.ui.AppearanceTheme

class DoubaoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppearanceTheme.apply(AssistantPreferences(this).appearance)
    }
}
