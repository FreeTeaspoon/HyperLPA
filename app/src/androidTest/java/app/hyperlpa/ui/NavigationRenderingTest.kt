package app.hyperlpa.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
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
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/** Frozen-clock rendered regression for outgoing/incoming entries sharing a stack index. */
class NavigationRenderingTest {
    @get:Rule val compose = createComposeRule()
    private data class PaintRoute(val id: Int) : NavKey

    @Test fun interruptedPushHasOneScrimInLtr() = checkInterruptedPush(LayoutDirection.Ltr)
    @Test fun interruptedPushHasOneScrimInRtlAndNestedStack() = checkInterruptedPush(LayoutDirection.Rtl, nested = true)

    @Test fun revisitedPageReplacesClosingPageBeforeSettling() = checkRevisitedPage(LayoutDirection.Ltr)
    @Test fun revisitedPageReplacesClosingPageInRtlAndNestedStack() = checkRevisitedPage(LayoutDirection.Rtl, nested = true)
    @Test fun swipeThenRevisitReplacesClosingPageBeforeSettling() = checkRevisitedPage(LayoutDirection.Ltr, swipe = true)
    @Test fun swipeThenRevisitReplacesClosingPageInRtlAndNestedStack() = checkRevisitedPage(LayoutDirection.Rtl, nested = true, swipe = true)

    private fun checkRevisitedPage(direction: LayoutDirection, nested: Boolean = false, swipe: Boolean = false) {
        val stack = navBackStackOf(PaintRoute(0))
        val visible = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        fun color(id: Int) = when (id) { 1 -> Color.Red; 2 -> Color.Green; else -> Color.White }
        val transition = navGraphicsTransition(
            motion = NavMotion(programmatic = NavSettleSpec.Tween(1000, LinearEasing)),
            dismissDirection = if (direction == LayoutDirection.Ltr) NavSwipeDirection.LeftToRight else NavSwipeDirection.RightToLeft,
            scrim = { 1f },
        ) { scope ->
            if (scope.relativeDepth <= 0f) translationX = -scope.relativeDepth * scope.layoutSize.width *
                if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
        }
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    NavDisplay(backStack = stack, modifier = Modifier.size(300.dp, 400.dp).testTag("revisit-nav"),
                        transition = transition,
                        effects = NavDisplayEffects(enableCornerClip = false, dimAmount = .5f,
                            blockInputDuringTransition = false, backdropColor = Color.White)) {
                        entry<PaintRoute> { route ->
                            DisposableEffect(route.id) { visible.add(route.id); onDispose { visible.remove(route.id) } }
                            Box(Modifier.fillMaxSize().background(color(route.id)))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        if (nested) {
            compose.runOnIdle { stack.add(PaintRoute(10)) }
            compose.mainClock.advanceTimeBy(1200)
        }
        val rootSize = stack.size
        compose.runOnIdle { stack.add(PaintRoute(1)) }
        compose.mainClock.advanceTimeBy(1200)
        val node = compose.onNodeWithTag("revisit-nav")
        fun pop() {
            if (swipe) {
                fun x(fraction: Float, width: Float) = width * if (direction == LayoutDirection.Ltr) fraction else 1f - fraction
                node.performTouchInput {
                    down(Offset(x(.03f, width.toFloat()), height * .5f))
                    moveTo(Offset(x(.3f, width.toFloat()), height * .5f), 160)
                }
                compose.mainClock.advanceTimeByFrame()
                node.performTouchInput { moveTo(Offset(x(.7f, width.toFloat()), height * .5f), 160) }
                compose.mainClock.advanceTimeByFrame()
                node.performTouchInput { up() }
                compose.mainClock.advanceTimeByFrame()
            } else {
                compose.runOnIdle { stack.removeAt(stack.lastIndex) }
                compose.mainClock.advanceTimeBy(160)
            }
            compose.runOnIdle { assertEquals("Back commits before its animation completes", rootSize, stack.size) }
        }
        // Reuse the same two identities without letting their previous removals finish.
        // Append-only presentation order must not allow the newer outgoing entry to cover a revived entry.
        repeat(4) { turn ->
            val outgoing = if (turn % 2 == 0) 1 else 2
            val incoming = if (turn % 2 == 0) 2 else 1
            pop()
            compose.runOnIdle {
                assertTrue("Outgoing page is still presented before the push", outgoing in visible)
                stack.add(PaintRoute(incoming))
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertTrue("The new page must be composed while the old one is closing", incoming in visible && outgoing in visible)
            val pixels = node.captureToImage().toPixelMap()
            val x = if (direction == LayoutDirection.Ltr) pixels.width * 19 / 20 else pixels.width / 20
            val actual = pixels[x, pixels.height / 2]
            val expected = color(incoming)
            assertEquals("Visible page changes before settling, turn=$turn", expected.red, actual.red, .025f)
            assertEquals("Visible page changes before settling, turn=$turn", expected.green, actual.green, .025f)
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertEquals(PaintRoute(1), stack.last()) }
    }

    private fun checkInterruptedPush(direction: LayoutDirection, nested: Boolean = false) {
        val stack = navBackStackOf(PaintRoute(0))
        val visible = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val transition = navGraphicsTransition(
            motion = NavMotion(programmatic = NavSettleSpec.Tween(1000, LinearEasing)),
            scrim = { 1f },
        ) { scope ->
            if (scope.relativeDepth <= 0f) {
                translationX = -scope.relativeDepth * scope.layoutSize.width *
                    if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
            }
        }
        compose.setContent {
            HyperLpaTheme(settings = AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    NavDisplay(backStack = stack, modifier = Modifier.size(300.dp, 400.dp).testTag("paint-nav"),
                        transition = transition,
                        effects = NavDisplayEffects(enableCornerClip = false, dimAmount = 0.5f,
                            blockInputDuringTransition = false, backdropColor = Color.White)) {
                        entry<PaintRoute> { route ->
                            DisposableEffect(route.id) { visible.add(route.id); onDispose { visible.remove(route.id) } }
                            Box(Modifier.fillMaxSize().background(Color.White))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        if (nested) {
            compose.runOnIdle { stack.add(PaintRoute(10)) }
            compose.mainClock.advanceTimeBy(1200)
        }
        repeat(3) { turn ->
            val outgoing = PaintRoute(turn * 2 + 1)
            val incoming = PaintRoute(turn * 2 + 2)
            compose.runOnIdle { stack.add(outgoing) }
            compose.mainClock.advanceTimeBy(1200)
            compose.runOnIdle { stack.removeAt(stack.lastIndex) }
            compose.mainClock.advanceTimeBy(320)
            compose.waitForIdle()
            assertTrue("Pop must still be in progress, visible=$visible", outgoing.id in visible)
            compose.runOnIdle { stack.add(incoming) }
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("paint-nav").captureToImage().toPixelMap()
            assertTrue("Both entries must still be presented before sampling, turn=$turn visible=$visible", outgoing.id in visible && incoming.id in visible)
            val x = if (direction == LayoutDirection.Ltr) pixels.width / 20 else pixels.width * 19 / 20
            assertEquals("One fullscreen dim layer during interruption $turn", 0.5f, pixels[x, pixels.height / 2].red, 0.025f)
            compose.mainClock.advanceTimeBy(1200)
            compose.runOnIdle { stack.removeAt(stack.lastIndex) }
            compose.mainClock.advanceTimeBy(1200)
        }
        if (nested) {
            compose.runOnIdle { stack.removeAt(stack.lastIndex) }
            compose.mainClock.advanceTimeBy(1200)
        }
        val pixels = compose.onNodeWithTag("paint-nav").captureToImage().toPixelMap()
        assertEquals("Returning to root removes dim", 1f, pixels[pixels.width / 2, pixels.height / 2].red, 0.01f)
    }
}
