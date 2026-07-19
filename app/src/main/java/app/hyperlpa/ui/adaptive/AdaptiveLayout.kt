package app.hyperlpa.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hyperlpa.ui.theme.LocalPlatformDensity
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val WideWindowThreshold = 600.dp
private val ExpandedWindowThreshold = 1200.dp
val MaximumContentWidth = 880.dp

enum class WindowMode {
    COMPACT,
    WIDE,
    EXPANDED,
}

@Composable
fun rememberWindowMode(): WindowMode {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    val width = with(density) { size.width.toDp() }
    return when {
        width >= ExpandedWindowThreshold -> WindowMode.EXPANDED
        width >= WideWindowThreshold -> WindowMode.WIDE
        else -> WindowMode.COMPACT
    }
}

@Composable
fun rememberIsWideWindow(): Boolean = rememberWindowMode() != WindowMode.COMPACT

@Composable
fun CenteredContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = MaximumContentWidth,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val wide = rememberIsWideWindow()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding = if (wide) ((this.maxWidth - maxWidth) / 2).coerceAtLeast(0.dp) else 0.dp
        content(sidePadding)
    }
}

@Composable
fun CenteredBox(
    modifier: Modifier = Modifier,
    maxWidth: Dp = MaximumContentWidth,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        content(
            Modifier
                .fillMaxSize()
                .then(
                    Modifier,
                ),
        )
    }
}

@Composable
fun AdaptiveTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    scrollBehavior: ScrollBehavior? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    if (rememberIsWideWindow()) {
        SmallTopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    } else {
        TopAppBar(
            title = title,
            modifier = modifier,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    }
}

@Composable
fun Modifier.horizontalCutoutPadding(): Modifier = windowInsetsPadding(
    WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal),
)
