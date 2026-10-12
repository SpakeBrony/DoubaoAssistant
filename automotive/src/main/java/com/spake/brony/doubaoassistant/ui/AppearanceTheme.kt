package com.spake.brony.doubaoassistant.ui

import androidx.appcompat.app.AppCompatDelegate
import com.spake.brony.doubaoassistant.data.model.Appearance

object AppearanceTheme {
    fun apply(appearance: Appearance) {
        AppCompatDelegate.setDefaultNightMode(when (appearance) {
            Appearance.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        })
    }
}
