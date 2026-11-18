package app.hyperlpa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
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
import app.hyperlpa.ui.navigation.RouteTransitionGuard
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/** Real pointer input through the route host while opening and closing are unfinished. */
class NavigationInterruptionTest {
    @get:Rule val compose = createComposeRule()
    private data class Route(val id: Int) : NavKey

    @Test fun swipeOpeningThenReopenDuringCloseInLtr() = checkInterruption(LayoutDirection.Ltr)
    @Test fun swipeOpeningThenReopenDuringCloseInRtl() = checkInterruption(LayoutDirection.Rtl)

    @Test fun tapOpeningPageThenSwipeBackTwiceInLtr() = checkChainedNavigation(LayoutDirection.Ltr)
    @Test fun tapOpeningPageThenSwipeBackTwiceInRtl() = checkChainedNavigation(LayoutDirection.Rtl)

    private fun checkChainedNavigation(direction: LayoutDirection) {
        val root = Route(0)
        val parent = Route(1)
        val child = Route(2)
        val stack = navBackStackOf(root)
        val guard = RouteTransitionGuard(stack)
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val owner = stack.last()
                    NavDisplay(backStack = stack,
                        onBack = { if (guard.back(owner)) stack.removeAt(stack.lastIndex) },
                        modifier = Modifier.size(300.dp, 400.dp).testTag("chain-host"),
                        effects = NavDisplayEffects(blockInputDuringTransition = false)) {
                        entry<Route>(swipeDismiss = if (direction == LayoutDirection.Ltr)
                            NavSwipeDirection.LeftToRight else NavSwipeDirection.RightToLeft) { route ->
                            val destination = when (route) {
                                root -> parent
                                parent -> child
                                else -> null
                            }
                            Box(Modifier.fillMaxSize().background(when (route) {
                                root -> Color.White
                                parent -> Color.Red
                                else -> Color.Blue
                            }).clickable {
                                if (destination != null && guard.open(route, destination)) stack.add(destination)
                            })
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("chain-host")
        fun x(f: Float, w: Int) = w * if (direction == LayoutDirection.Ltr) f else 1f - f
        fun tap(f: Float) {
            node.performTouchInput { down(Offset(x(f, width), height * .5f)); advanceEventTime(40); up() }
        }
        fun swipe() {
            node.performTouchInput {
                down(Offset(x(.02f, width), height * .5f))
                moveTo(Offset(x(.2f, width), height * .5f), 40)
            }
            compose.mainClock.advanceTimeByFrame()
            node.performTouchInput { moveTo(Offset(x(.86f, width), height * .5f), 40) }
            compose.mainClock.advanceTimeByFrame()
            node.performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
        }
        tap(.1f)
        compose.mainClock.advanceTimeBy(80)
        // A second tap on the exposed root is stale, even if it is still visible.
        tap(.02f)
        compose.runOnIdle { assertEquals(listOf(root, parent), stack.toList()) }
        tap(.96f)
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertEquals("The entering page can push immediately", listOf(root, parent, child), stack.toList()) }
        compose.mainClock.advanceTimeBy(600)
        swipe()
        compose.runOnIdle { assertEquals(listOf(root, parent), stack.toList()) }
        swipe()
        compose.runOnIdle { assertEquals("The next swipe pops the newly revealed page", listOf(root), stack.toList()) }
        compose.mainClock.advanceTimeBy(900)
        compose.runOnIdle { assertEquals(listOf(root), stack.toList()) }
    }

    private fun checkInterruption(direction: LayoutDirection) {
        val root = Route(0)
        val child = Route(1)
        val stack = navBackStackOf(root)
        val guard = RouteTransitionGuard(stack)
        fun back(expected: Route) {
            if (guard.back(expected)) {
                stack.removeAt(stack.lastIndex)
            }
        }
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    val owner = stack.last() as Route
                    NavDisplay(backStack = stack, onBack = { back(owner) },
                        modifier = Modifier.size(300.dp, 400.dp).testTag("interrupt-host"),
                        effects = NavDisplayEffects(blockInputDuringTransition = false)) {
                        entry<Route>(swipeDismiss = if (direction == LayoutDirection.Ltr)
                            NavSwipeDirection.LeftToRight else NavSwipeDirection.RightToLeft) { route ->
                            Box(Modifier.fillMaxSize().background(if (route == root) Color.White else Color.Red)
                                .clickable {
                                    if (route == root) {
                                        if (guard.open(route, child)) stack.add(child)
                                    } else back(route)
                                })
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("interrupt-host")
        fun x(f: Float, w: Float) = w * if (direction == LayoutDirection.Ltr) f else 1f - f
        node.performTouchInput { down(Offset(x(.1f, width.toFloat()), height * .5f)); advanceEventTime(40); up() }
        compose.mainClock.advanceTimeBy(64)
        node.performTouchInput {
            down(Offset(x(.02f, width.toFloat()), height * .5f))
            moveTo(Offset(x(.2f, width.toFloat()), height * .5f), 40)
        }
        compose.mainClock.advanceTimeByFrame()
        node.performTouchInput { moveTo(Offset(x(.8f, width.toFloat()), height * .5f), 40) }
        compose.mainClock.advanceTimeByFrame()
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("Swipe interrupts the opening page", listOf(root), stack.toList()) }
        repeat(3) {
            // The exposed parent can reopen the same destination before the close completes.
            node.performTouchInput { down(Offset(x(.05f, width.toFloat()), height * .5f)); advanceEventTime(40); up() }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { assertEquals(listOf(root, child), stack.toList()) }
            // A toolbar-style click is accepted on the visible opening child too.
            node.performTouchInput { down(Offset(x(.98f, width.toFloat()), height * .5f)); advanceEventTime(40); up() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Rapid close commits exactly once", listOf(root), stack.toList()) }
        }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { assertEquals(listOf(root), stack.toList()) }
    }
}
