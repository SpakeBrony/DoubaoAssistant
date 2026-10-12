package com.spake.brony.doubaoassistant.ui

// 保留设计文件的横屏断点；对话字号按用户确认的目标图调整，布局单位为 dp。
data class DesignDimensions(val width: Float, val height: Float) {
    val compact = height < 540f
    val ultraWide = width / height > 2.3f
    val safeX = (width * .05f).coerceIn(32f, 96f)
    val toolbar = if (compact) 64f else (height * .111f).coerceIn(90f, 120f)
    val control = when {
        compact -> 48f
        width >= 1000 && height <= 760 -> 64f
        width <= 850 -> 56f
        else -> (width * .045f).coerceIn(56f, 80f)
    }
    val controlGap = if (compact) 12f else (width * .016f).coerceIn(16f, 28f)
    val bottom = if (compact) 94f else if (ultraWide) 120f else 122f
    val conversationWidth = if (width <= 850) width * .8f else (width * .66f).coerceAtMost(1240f)
    val conversationTop = when {
        compact -> 12f
        ultraWide -> width * .025f
        width >= 1000 && height <= 760 -> 30f
        else -> (height * .08f).coerceIn(45f, 95f)
    }
    val aiText = (width * .0203125f).coerceIn(26f, 36f) * 2f / 3f
    val userText = (width * .015625f).coerceIn(20f, 28f) * 2f / 3f
    val statusText = (width * .015f).coerceIn(17f, 29f)
    val panelWidth = if (width <= 850) (width * .65f).coerceAtLeast(360f.coerceAtMost(width)) else (width * .35f).coerceIn(380f, 680f)
    val panelPadding = if (width <= 850) 25f else (width * .028f).coerceIn(28f, 54f)
    val settingLabel = (width * .014f).coerceIn(18f, 27f)
    val settingDetail = (width * .0095f).coerceIn(13f, 18f)
    val settingSpacing = if (compact || ultraWide) 18f else 28f
    val appearanceHeight = if (compact || ultraWide) 74f else 102f
}
