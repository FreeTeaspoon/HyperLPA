package app.hyperlpa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.components.HeaderViewport
import app.hyperlpa.ui.components.Os4IconButton
import app.hyperlpa.ui.components.Os4TopAppBar
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import kotlin.math.roundToInt

class FixedHeaderShadowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fixedHeaderShadowExtendsBelowBarAndAnimatesInBothDirections() = checkShadow(fixed = true)
    @Test fun collapsedHeaderRetainsFadeWhenContentJumpsStraightToTop() = checkShadow(fixed = false)

    private fun checkShadow(fixed: Boolean) {
        val bar = TopAppBarState(-100f, -100f, 0f)
        val overlapping = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                Box(Modifier.size(300.dp, 200.dp).background(Color.White).testTag("shadow-scene")) {
                    Os4TopAppBar(title = if (fixed) "" else "Long page", backdrop = null,
                        scrollBehavior = MiuixScrollBehavior(bar),
                        viewport = HeaderViewport({ true }, { overlapping.value }, { true }),
                        modifier = Modifier.testTag("fixed-bar"), fixed = fixed,
                        navigationIcon = { Os4IconButton({}, Modifier.testTag("fixed-button")) {} })
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val header = compose.onNodeWithTag("fixed-bar").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("fixed-button").fetchSemanticsNode().boundsInRoot
        val scene = compose.onNodeWithTag("shadow-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot
        val x = (button.center.x - origin.left).roundToInt()
        val y = (header.bottom - origin.top).roundToInt() + 2
        fun darkness(): Float = 1f - scene.captureToImage().toPixelMap()[x, y].red
        val clear = darkness()
        compose.runOnIdle { overlapping.value = true; bar.contentOffset = -96f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160)
        val entering = darkness()
        compose.mainClock.advanceTimeBy(500)
        val full = darkness()
        assertTrue("The shadow must extend below the fixed header", full > clear + .002f)
        assertTrue("The shadow must fade in rather than snap", entering > clear && entering < full)
        compose.runOnIdle { overlapping.value = false; bar.contentOffset = 0f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160)
        val leaving = darkness()
        assertTrue("The shadow must fade out rather than snap", leaving > clear && leaving < full)
        compose.mainClock.advanceTimeBy(500)
        assertTrue("Returning to the top clears the shadow", darkness() <= clear + .002f)
    }
}
