package app.hyperlpa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

class NavigationCornerRenderingTest {
    @get:Rule val compose = createComposeRule()
    private data class Route(val id: Int) : NavKey

    @Test fun leadingCornersClipDuringLtrOpening() = checkCorners(LayoutDirection.Ltr, true)
    @Test fun leadingCornersClipDuringRtlOpening() = checkCorners(LayoutDirection.Rtl, true)
    @Test fun roundedFallbackStillClips() = checkCorners(LayoutDirection.Ltr, false)

    private fun checkCorners(direction: LayoutDirection, squircle: Boolean) {
        val stack = navBackStackOf(Route(0))
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction, LocalSquircleEnabled provides squircle) {
                    NavDisplay(backStack = stack, onBack = {},
                        modifier = Modifier.size(300.dp, 400.dp).testTag("corner-host"),
                        effects = NavDisplayEffects(cornerClipRadius = 40.dp)) {
                        entry<Route>(swipeDismiss = if (direction == LayoutDirection.Ltr)
                            NavSwipeDirection.LeftToRight else NavSwipeDirection.RightToLeft) { route ->
                            Box(Modifier.fillMaxSize().background(if (route.id == 0) Color.White else Color.Red))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stack.add(Route(1)) }
        compose.mainClock.advanceTimeBy(240)
        val node = compose.onNodeWithTag("corner-host")
        val pixels = node.captureToImage().toPixelMap()
        fun red(c: Color) = c.red > .8f && c.green < .2f
        val mid = pixels.height / 2
        val edge = if (direction == LayoutDirection.Ltr)
            (0 until pixels.width).first { red(pixels[it, mid]) }
        else (pixels.width - 1 downTo 0).first { red(pixels[it, mid]) }
        val innerX = edge + if (direction == LayoutDirection.Ltr) 3 else -3
        assertTrue("Moving page has a clipped top leading corner", !red(pixels[innerX, 2]))
        assertTrue("Moving page has a clipped bottom leading corner", !red(pixels[innerX, pixels.height - 3]))
        assertTrue("The middle stays opaque", red(pixels[innerX, mid]))
        compose.mainClock.advanceTimeBy(1600)
        val settled = node.captureToImage().toPixelMap()
        assertTrue("The settled page fills its bounds", red(settled[2, 2]))
        assertTrue("The settled page fills the opposite corner", red(settled[settled.width - 3, settled.height - 3]))
    }
}
