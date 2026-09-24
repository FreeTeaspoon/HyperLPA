package app.hyperlpa.ui.components

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** First-item spacing after Scaffold padding, matching Mishka. */
enum class PageStart {
    Heading, // SmallTitle already supplies its native 8 dp top inset.
    Inset, // The first component already owns its 12 dp outer top inset.
    Viewport, // Centered state, full-bleed content or documented hero layout.
}

/**
 * Records the list DSL once so the first emitted item determines its top inset.
 * Conditional headings/errors are handled without adding a fake spacer item or
 * changing keys, indices, item scopes, animations or viewport-sized states.
 * Cards/inputs need 12 dp; explicitly marked headings/inset content need none.
 */
class MishkaPageContent(build: LazyListScope.() -> Unit) : LazyListScope {
    private val entries = mutableListOf<LazyListScope.() -> Unit>()
    private var hasFirstItem = false
    var topPadding: Dp = 0.dp
        private set

    private fun first(contentType: Any?) {
        if (!hasFirstItem) {
            topPadding = if (contentType is PageStart) 0.dp else 12.dp
            hasFirstItem = true
        }
    }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        first(contentType)
        entries += { item(key = key, contentType = contentType, content = content) }
    }

    override fun items(
        count: Int,
        key: ((Int) -> Any)?,
        contentType: (Int) -> Any?,
        itemContent: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        if (count > 0 && !hasFirstItem) first(contentType(0))
        entries += { items(count = count, key = key, contentType = contentType, itemContent = itemContent) }
    }

    init { build() }

    val content: LazyListScope.() -> Unit = {
        this@MishkaPageContent.entries.forEach { entry -> entry(this) }
    }
}
