package app.hyperlpa.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import app.hyperlpa.ui.components.HeaderBlurState
import app.hyperlpa.ui.components.HeaderViewport
import org.junit.Assert.*
import org.junit.Test
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState

class HeaderBlurStateTest {
    private val bar = TopAppBarState(-100f, -50f, -12f)
    private val scroll = object : ScrollBehavior {
        override val state = bar
        override val isPinned = false
        override val snapAnimationSpec: AnimationSpec<Float>? = null
        override val flingAnimationSpec: DecayAnimationSpec<Float>? = null
        override val nestedScrollConnection = object : NestedScrollConnection {}
    }
    private var measured = false
    private var backward = false
    private var forward = false
    private val viewport = HeaderViewport({ measured }, { backward }, { forward })

    @Test fun shortPageFallbackRequiresMeasurementAndTracksCollapse() {
        val blur = HeaderBlurState(viewport, scroll, 48f, 32f, true)
        assertFalse(blur.usesCollapseFallback)
        assertEquals(.25f, blur.alpha, .001f)
        measured = true
        assertTrue(blur.usesCollapseFallback)
        assertEquals(.5f, blur.alpha, .001f)
        assertEquals(.5f, blur.shadowAlpha, .001f)
        assertTrue(blur.isContentScrolled)
        bar.heightOffset = 0f
        assertEquals(0f, blur.alpha, .001f)
        assertFalse(blur.isContentScrolled)
    }

    @Test fun rangeChangesAndLongListBottomKeepIndependentRamps() {
        measured = true
        val blur = HeaderBlurState(viewport, scroll, 48f, 32f, true)
        assertTrue(blur.usesCollapseFallback)
        // Larger text or a smaller window gives the same page a real scroll range.
        forward = true
        assertFalse(blur.usesCollapseFallback)
        assertEquals(.25f, blur.alpha, .001f)
        assertEquals(.375f, blur.shadowAlpha, .001f)
        forward = false
        backward = true
        assertFalse(blur.usesCollapseFallback)
        bar.contentOffset = -96f
        assertEquals(1f, blur.alpha, .001f)
        backward = false
        assertTrue(blur.usesCollapseFallback)
    }

    @Test fun almostFittingPageUsesCollapseUntilItHasEnoughActualScrollForTheRamp() {
        measured = true
        forward = true
        var range = 12f
        val actual = HeaderViewport({ measured }, { backward }, { forward }, { range })
        val blur = HeaderBlurState(actual, scroll, 48f, 32f, true)
        assertTrue(blur.usesCollapseFallback)
        bar.contentOffset = 0f
        bar.heightOffset = -100f
        assertEquals(1f, blur.alpha, .001f)
        range = 48f
        assertFalse(blur.usesCollapseFallback)
        range = 12f
        forward = false
        backward = true
        assertTrue(blur.usesCollapseFallback)
        bar.heightOffset = 0f
        backward = false
        assertEquals(0f, blur.alpha, .001f)
    }

    @Test fun fixedCompactHeaderUsesScrollShadowAndWideHeaderKeepsStandaloneShadow() {
        measured = true
        val blur = HeaderBlurState(viewport, scroll, 48f, 32f, false)
        assertFalse(blur.usesCollapseFallback)
        assertEquals(.25f, blur.alpha, .001f)
        assertEquals(.375f, blur.shadowAlpha, .001f)
        val wide = HeaderBlurState(viewport, scroll, 48f, 32f, false, fullShadow = true)
        assertEquals(1f, wide.shadowAlpha, .001f)
        assertTrue(wide.isFloating)
        bar.contentOffset = 0f
        assertEquals(0f, blur.shadowAlpha, .001f)
        assertFalse(blur.isFloating)
        assertEquals(1f, wide.shadowAlpha, .001f)
        assertTrue(wide.isFloating)
    }
}
