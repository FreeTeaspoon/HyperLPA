package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior

class Os4HeaderViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun measuredRangeChangesWithViewportAndContentAndRemainsLongAtBottom() {
        var viewportHeight by mutableStateOf(300.dp)
        var itemHeight by mutableStateOf(40.dp)
        var count by mutableIntStateOf(2)
        lateinit var blur: HeaderBlurState
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            list = rememberLazyListState()
            val viewport = rememberHeaderViewport(list)
            val scroll = MiuixScrollBehavior()
            blur = remember(viewport, scroll) { HeaderBlurState(viewport, scroll, 48f, 32f, true) }
            LazyColumn(Modifier.height(viewportHeight), state = list) {
                items(count) { Box(Modifier.fillMaxWidth().height(itemHeight)) }
            }
        }
        compose.runOnIdle { assertTrue(blur.usesCollapseFallback); viewportHeight = 20.dp }
        compose.runOnIdle { assertFalse(blur.usesCollapseFallback); viewportHeight = 300.dp }
        compose.runOnIdle { assertTrue(blur.usesCollapseFallback); viewportHeight = 70.dp }
        compose.runOnIdle { assertTrue("A 10dp range cannot drive a 48dp blur ramp", blur.usesCollapseFallback); viewportHeight = 300.dp; itemHeight = 200.dp }
        compose.runOnIdle { assertFalse(blur.usesCollapseFallback); itemHeight = 40.dp; count = 30 }
        compose.runOnIdle { assertFalse(blur.usesCollapseFallback) }
        compose.runOnIdle { runBlocking { list.scrollToItem(29) } }
        compose.runOnIdle {
            assertFalse(list.canScrollForward)
            assertTrue(list.canScrollBackward)
            assertFalse(blur.usesCollapseFallback)
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(0) } }
        compose.runOnIdle { assertFalse(blur.usesCollapseFallback); count = 2 }
        compose.runOnIdle { assertTrue(blur.usesCollapseFallback) }
    }
}
