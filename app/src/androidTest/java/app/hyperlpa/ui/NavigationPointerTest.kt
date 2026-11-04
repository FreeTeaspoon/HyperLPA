package app.hyperlpa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

class NavigationPointerTest {
    @get:Rule val compose = createComposeRule()
    private data class Route(val id: Int) : NavKey

    @Test fun initiatingPointerOwnsHeldSwipeAndReleaseRemainsInteractive() = checkPointers(LayoutDirection.Ltr)
    @Test fun initiatingPointerOwnsHeldSwipeInRtl() = checkPointers(LayoutDirection.Rtl)

    private fun checkPointers(direction: LayoutDirection) {
        val stack = navBackStackOf(Route(0), Route(1))
        var underlyingClicks = 0
        var outgoingVisible = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    NavDisplay(backStack = stack, modifier = Modifier.size(300.dp, 400.dp).testTag("navigation"),
                        effects = NavDisplayEffects(blockInputDuringTransition = false)) {
                        entry<Route>(swipeDismiss = if (direction == LayoutDirection.Ltr)
                            NavSwipeDirection.LeftToRight else NavSwipeDirection.RightToLeft) { route ->
                            if (route.id == 1) DisposableEffect(Unit) {
                                outgoingVisible = true
                                onDispose { outgoingVisible = false }
                            }
                            Box(Modifier.fillMaxSize().background(Color.White).clickable {
                                if (route.id == 0) underlyingClicks++
                            })
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("navigation")
        fun x(fraction: Float, width: Float) = width * if (direction == LayoutDirection.Ltr) fraction else 1f - fraction
        node.performTouchInput { down(0, Offset(x(.03f, width.toFloat()), height * .5f)); moveTo(0, Offset(x(.3f, width.toFloat()), height * .5f), 160) }
        compose.mainClock.advanceTimeByFrame()
        node.performTouchInput { moveTo(0, Offset(x(.8f, width.toFloat()), height * .5f), 160) }
        compose.mainClock.advanceTimeByFrame()
        node.performTouchInput {
            down(1, Offset(x(.1f, width.toFloat()), height * .6f))
            advanceEventTime(40)
            up(1)
        }
        compose.runOnIdle { assertEquals("A second pointer cannot activate the revealed page", 0, underlyingClicks) }
        node.performTouchInput { up(0) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals("The initiating pointer commits the pop", 1, stack.size)
            assertTrue("The outgoing page must still be settling", outgoingVisible)
        }
        node.performTouchInput {
            down(0, Offset(x(.1f, width.toFloat()), height * .6f))
            advanceEventTime(40)
            up(0)
        }
        compose.runOnIdle { assertEquals("The exposed page accepts input during release settling", 1, underlyingClicks) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }
}
