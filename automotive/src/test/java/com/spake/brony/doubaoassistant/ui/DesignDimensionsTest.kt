package com.spake.brony.doubaoassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignDimensionsTest {
    @Test fun standardLandscapeMatchesReferenceCss() {
        val dimensions = DesignDimensions(1280f, 720f)
        assertEquals(64f, dimensions.safeX, .001f)
        assertEquals(844.8f, dimensions.conversationWidth, .001f)
        assertEquals(64f, dimensions.control, .001f)
        assertEquals(122f, dimensions.bottom, .001f)
        assertEquals(26f * 2 / 3, dimensions.aiText, .001f)
        assertEquals(20f * 2 / 3, dimensions.userText, .001f)
        assertEquals(448f, dimensions.panelWidth, .001f)
    }
    @Test fun ultraWideRetainsReadableConversationAndCompactSettings() {
        val dimensions = DesignDimensions(1920f, 720f)
        assertTrue(dimensions.ultraWide)
        assertEquals(1240f, dimensions.conversationWidth, .001f)
        assertEquals(120f, dimensions.bottom, .001f)
        assertEquals(24f, dimensions.aiText, .001f)
        assertEquals(28f * 2 / 3, dimensions.userText, .001f)
        assertEquals(48f, dimensions.conversationTop, .001f)
        assertEquals(74f, dimensions.appearanceHeight, .001f)
    }
    @Test fun phoneLandscapeControlsFitWithoutShrinkingTouchTargetsBelow48Dp() {
        val dimensions = DesignDimensions(800f, 360f)
        assertTrue(dimensions.compact)
        assertTrue(dimensions.control >= 48)
        assertTrue(dimensions.control * 4 + dimensions.controlGap * 3 < dimensions.height)
        assertTrue(dimensions.toolbar + dimensions.bottom < dimensions.height)
        assertTrue(dimensions.panelWidth <= dimensions.width)
    }
}
