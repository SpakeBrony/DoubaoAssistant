package com.spake.brony.doubaoassistant.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import com.spake.brony.doubaoassistant.databinding.ActivityPermissionBinding

/** 配置页的横屏尺寸适配，与凭证保存和授权流程分开维护。 */
internal fun ActivityPermissionBinding.applyDesignDimensions() {
    root.doOnLayout {
        val density = root.resources.displayMetrics.density
        val width = it.width / density
        val height = it.height / density
        val compact = height < 540
        val ultraWide = width / height > 2.3f

        fun dp(value: Float) = (value * density + 0.5f).toInt()
        fun TextView.size(value: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
        fun View.bottom(value: Float) = updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = dp(value)
        }

        onboardTitle.size(if (compact) 28f else 34f)
        onboardTitle.typeface = Typeface.create(onboardTitle.typeface, 450, false)
        onboardTitle.bottom(if (compact || ultraWide) 12f else 18f)
        onboardBody.size(16f)
        onboardBody.setFallbackLineSpacing(false)
        onboardBody.setLineHeight((onboardBody.textSize * 1.5f + 0.5f).toInt())
        onboardBody.bottom(if (compact || ultraWide) 18f else 28f)
        permissionActionLabel.size((width * 0.0125f).coerceIn(18f, 24f))
        val actionPadding = if (compact) 14f else if (ultraWide) 17f else 23f
        permissionAction.setPadding(
            dp(if (ultraWide) 32f else 36f),
            dp(actionPadding),
            dp(if (ultraWide) 32f else 36f),
            dp(actionPadding),
        )
        permissionAction.minimumHeight = dp(if (compact) 52f else if (ultraWide) 58f else 70f)
        permissionAction.updateLayoutParams { this.width = dp(270f.coerceAtMost(width - 64f)) }
        onboardNote.size((width * 0.009f).coerceIn(12f, 17f))
        onboardNote.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = dp(if (compact || ultraWide) 10f else 24f)
        }
        onboarding.setPadding(
            dp(32f),
            dp(if (compact) 16f else 32f),
            dp(32f),
            dp(if (compact) 16f else 32f),
        )
        listOf(onboardTitle, onboardBody, permissionHelp).forEach { text ->
            text.updateLayoutParams { this.width = dp((width - 64f).coerceAtMost(850f)) }
        }
        credentialsForm.updateLayoutParams { this.width = dp((width - 64f).coerceAtMost(640f)) }
        invalidateAll()
    }
}
