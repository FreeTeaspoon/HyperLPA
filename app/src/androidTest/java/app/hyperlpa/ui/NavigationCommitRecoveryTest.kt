package app.hyperlpa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.navigation.RouteTransitionGuard
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

/** A guarded/locked Back callback may keep the stack. Neither driver may remain half closed. */
class NavigationCommitRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private data class Route(val id: Int) : NavKey
    private class BackInput : NavigationEventInput() {
        fun start(edge: Int) = dispatchOnBackStarted(NavigationEvent(swipeEdge = edge, progress = 0f))
        fun progress(edge: Int) = dispatchOnBackProgressed(NavigationEvent(swipeEdge = edge, progress = .65f))
        fun commit() = dispatchOnBackCompleted()
        fun cancel() = dispatchOnBackCancelled()
    }

    @Test fun rejectedSwipeReturnsToRestInLtr() = checkRecovery(false, LayoutDirection.Ltr)
    @Test fun rejectedSwipeReturnsToRestInRtl() = checkRecovery(false, LayoutDirection.Rtl)
    @Test fun rejectedPredictiveBackReturnsToRestInLtr() = checkRecovery(true, LayoutDirection.Ltr)
    @Test fun rejectedPredictiveBackReturnsToRestInRtl() = checkRecovery(true, LayoutDirection.Rtl)

    @Test fun successivePredictiveGesturesPopTwoPagesWithoutWaitingForTheClose() {
        val root = Route(0)
        val parent = Route(1)
        val child = Route(2)
        val stack = navBackStackOf(root, parent, child)
        val guard = RouteTransitionGuard(stack)
        val dispatcher = NavigationEventDispatcher()
        val input = BackInput()
        val dispatcherOwner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides dispatcherOwner) {
                    DisposableEffect(Unit) {
                        dispatcher.addInput(input)
                        onDispose { dispatcher.removeInput(input); dispatcher.dispose() }
                    }
                    val owner = stack.last()
                    NavDisplay(backStack = stack,
                        onBack = { if (guard.back(owner)) stack.removeAt(stack.lastIndex) },
                        effects = NavDisplayEffects(blockInputDuringTransition = false)) {
                        entry<Route> { route ->
                            Box(Modifier.fillMaxSize().background(if (route == root) Color.White else Color.Red))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        fun gesture() {
            compose.runOnIdle { input.start(NavigationEvent.EDGE_LEFT) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { input.progress(NavigationEvent.EDGE_LEFT) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { input.commit() }
            compose.mainClock.advanceTimeByFrame()
        }
        gesture()
        compose.runOnIdle { assertEquals(listOf(root, parent), stack.toList()) }
        gesture()
        compose.runOnIdle { assertEquals(listOf(root), stack.toList()) }
        compose.mainClock.advanceTimeBy(900)
        compose.runOnIdle { assertEquals(listOf(root), stack.toList()) }
    }

    private fun checkRecovery(predictive: Boolean, direction: LayoutDirection) {
        val stack = navBackStackOf(Route(0), Route(1))
        val dispatcher = NavigationEventDispatcher()
        val input = BackInput()
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        var allowBack = false
        var commits = 0
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner,
                    LocalLayoutDirection provides direction) {
                    DisposableEffect(Unit) {
                        dispatcher.addInput(input)
                        onDispose { dispatcher.removeInput(input); dispatcher.dispose() }
                    }
                    NavDisplay(backStack = stack, modifier = Modifier.size(300.dp, 400.dp).testTag("recovery-nav"),
                        onBack = { commits++; if (allowBack) stack.removeAt(stack.lastIndex) },
                        effects = NavDisplayEffects(blockInputDuringTransition = true)) {
                        entry<Route>(swipeDismiss = if (direction == LayoutDirection.Ltr)
                            NavSwipeDirection.LeftToRight else NavSwipeDirection.RightToLeft) { route ->
                            Box(Modifier.fillMaxSize().background(if (route.id == 1) Color.Red else Color.White))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val node = compose.onNodeWithTag("recovery-nav")
        val edge = if (direction == LayoutDirection.Ltr) NavigationEvent.EDGE_LEFT else NavigationEvent.EDGE_RIGHT
        fun gesture(commit: Boolean) {
            if (predictive) {
                compose.runOnIdle { input.start(edge) }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle { input.progress(edge) }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle { if (commit) input.commit() else input.cancel() }
            } else {
                fun x(f: Float, w: Float) = w * if (direction == LayoutDirection.Ltr) f else 1f - f
                node.performTouchInput { down(Offset(x(.03f, width.toFloat()), height * .5f))
                    moveTo(Offset(x(.2f, width.toFloat()), height * .5f), 120) }
                compose.mainClock.advanceTimeByFrame()
                node.performTouchInput { moveTo(Offset(x(.7f, width.toFloat()), height * .5f), 80) }
                compose.mainClock.advanceTimeByFrame()
                node.performTouchInput { if (commit) up() else cancel() }
            }
            compose.mainClock.advanceTimeBy(1600)
        }
        gesture(commit = true)
        compose.runOnIdle { assertEquals("The guarded callback was reached", 1, commits); assertEquals(2, stack.size) }
        val pixels = node.captureToImage().toPixelMap()
        for (x in listOf(pixels.width / 10, pixels.width / 2, pixels.width * 9 / 10)) {
            val pixel = pixels[x, pixels.height / 2]
            assertTrue("Rejected Back must restore the red page at x=$x, actual=$pixel", pixel.red > .95f && pixel.green < .05f)
        }
        gesture(commit = false)
        compose.runOnIdle { allowBack = true }
        gesture(commit = true)
        compose.runOnIdle { assertEquals("The next gesture can still pop", 1, stack.size) }
    }
}
