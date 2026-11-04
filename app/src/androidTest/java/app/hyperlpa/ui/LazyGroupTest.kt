package app.hyperlpa.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.components.lazyGroup
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.BasicComponent

class LazyGroupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeGroupComposesViewportAndPreservesLastItemActionAfterFiltering() {
        val entries = mutableStateOf((1..1000).toList())
        val composed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        var selected: Int? = null
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                LazyColumn(Modifier.testTag("entities")) {
                    lazyGroup(entries.value, key = { it }, contentType = "fixture-entity") { entry ->
                        DisposableEffect(entry) { composed.add(entry); onDispose { composed.remove(entry) } }
                        BasicComponent(title = "Entity $entry", onClick = { selected = entry })
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue("Initial composition must be viewport sized: ${composed.size}", composed.size in 1..40) }
        compose.onNodeWithTag("entities").performScrollToIndex(999)
        compose.onNodeWithText("Entity 1000").performClick()
        compose.runOnIdle { assertEquals(1000, selected) }
        compose.runOnIdle { entries.value = entries.value.filter { it % 2 == 0 } }
        compose.onNodeWithTag("entities").performScrollToIndex(499)
        compose.onNodeWithText("Entity 1000").performClick()
        compose.runOnIdle { assertEquals(1000, selected); assertTrue(composed.size < 40) }
    }
}
