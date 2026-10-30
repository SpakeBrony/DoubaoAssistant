package com.spake.brony.doubaoassistant.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import com.spake.brony.doubaoassistant.databinding.ActivityMainBinding

internal fun ActivityMainBinding.applyDesignDimensions(width: Int, height: Int) {
    val density = root.resources.displayMetrics.density
    val dimensions = DesignDimensions(width / density, height / density)
    fun dp(value: Float) = (value * density + .5f).toInt()
    fun TextView.size(value: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
    topToolbar.updateLayoutParams { this.height = dp(dimensions.toolbar) }
    val topPadding = if (dimensions.compact || dimensions.width <= 850) 12f else 24f
    topToolbar.setPadding(dp(dimensions.safeX), dp(topPadding), dp(dimensions.safeX), 0)
    conversation.setPadding(conversation.paddingLeft, dp(dimensions.conversationTop),
        conversation.paddingRight, dp(28f))
    conversation.setDimensions(dimensions)
    val bottomPadding = if (dimensions.compact) 12f else if (dimensions.ultraWide) 24f else 26f
    val statusHeight = if (dimensions.compact) 82f else 96f
    bottomArea.updateLayoutParams { this.height = dp(dimensions.bottom) }
    bottomArea.setPadding(0, 0, 0, dp(bottomPadding))
    statusGroup.updateLayoutParams { this.height = dp(statusHeight) }
    voiceMark.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = dp(if (dimensions.compact) 8f else 13f) }
    rightControls.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        leftMargin = dp(dimensions.safeX)
        rightMargin = dp(dimensions.safeX)
    }
    listeningStatus.size(dimensions.statusText)
    statusSupport.size((dimensions.width * .01f).coerceIn(14f, 20f))
    inputContainer.updateLayoutParams {
        this.width = dp(dimensions.control)
        this.height = dp(dimensions.control)
    }
    inputButton.size(dimensions.control * .34f)
    listOf(toggleListening, toggleChat, endSession).forEach { button ->
        button.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            this.width = dp(dimensions.control)
            this.height = dp(dimensions.control)
            topMargin = dp(dimensions.controlGap)
        }
        val padding = dp(dimensions.control * .325f)
        button.setPadding(padding, padding, padding, padding)
    }
    with(settings) {
        settingsPanel.updateLayoutParams { this.width = dp(dimensions.panelWidth) }
        settingsColumn.setPadding(dp(dimensions.panelPadding), dp(if (dimensions.compact || dimensions.ultraWide) 20f else 42f), dp(dimensions.panelPadding), dp(24f))
        settingsHeader.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = dp(if (dimensions.compact || dimensions.ultraWide) 0f else 26f) }
        settingsTitle.size((dimensions.width * .02f).coerceIn(25f, 38f))
        settingsTitle.typeface = Typeface.create(settingsTitle.typeface, 500, false)
        listOf(nameLabel, appearanceLabel, layoutLabel, captionsLabel, speedLabel, replyStyleLabel, aiControlLabel, answerModeLabel, credentialsLabel, permissionLabel, backgroundChatLabel, overlayStyleLabel, historyLabel, debugLabel).forEach { it.size(dimensions.settingLabel) }
        nameValue.size(dimensions.settingLabel)
        listOf(nameDetail, captionsDetail, aiControlDetail, answerModeDetail, credentialsDetail, permissionDetail, backgroundChatDetail, historyDetail).forEach { it.size(dimensions.settingDetail) }
        listOf(nameSection, appearanceSection, layoutSection, captionsSection, speedSection, replyStyleSection, aiControlSection, answerModeSection, credentialsSection, permissionSection, backgroundChatSection, historySection, debugSection).forEach { it.setPadding(0, dp(dimensions.settingSpacing), 0, dp(dimensions.settingSpacing)) }
        overlayStyleSection.setPadding(0, 0, 0, dp(dimensions.settingSpacing))
        appearanceOptions.updateLayoutParams<ViewGroup.MarginLayoutParams> { this.height = dp(dimensions.appearanceHeight); topMargin = dp(if (dimensions.compact || dimensions.ultraWide) 14f else 23f) }
        speedOptions.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = dp(if (dimensions.compact || dimensions.ultraWide) 14f else 23f) }
        listOf(themeSystemLabel, themeLightLabel, themeDarkLabel).forEach { it.size((dimensions.width * .01f).coerceIn(13f, 19f)) }
        listOf(speedSlow, speedNormal, speedFast, layoutLeft, layoutRight, replyStyleMinimal, replyStyleSimple, replyStyleDetailed, answerModeSingle, answerModeChat, overlayStyleMinimal, overlayStyleCompact, overlayStyleDetailed).forEach { it.size((dimensions.width * .011f).coerceIn(16f, 21f)) }
    }
}
