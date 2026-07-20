package app.hyperlpa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.ui.adaptive.AdaptiveTopAppBar
import app.hyperlpa.ui.adaptive.CenteredContent
import app.hyperlpa.ui.adaptive.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DetailLazyScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    background: (@Composable BoxScope.() -> Unit)? = null,
    collapsedTitle: String? = null,
    collapsedBarRevealStart: Dp = 0.dp,
    content: LazyListScope.(sidePadding: Dp) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hasBackground = background != null
    val backdrop = rememberAppBackdrop()
    val backgroundScrollOffset = remember { mutableFloatStateOf(0f) }
    val barRevealStart = with(LocalDensity.current) { collapsedBarRevealStart.toPx() }
    val barRevealDistance = with(LocalDensity.current) { 56.dp.toPx() }
    val collapsedBarProgress by remember(barRevealStart, barRevealDistance, hasBackground) {
        derivedStateOf {
            if (hasBackground) {
                val linearProgress = (
                    (backgroundScrollOffset.floatValue - barRevealStart) / barRevealDistance
                ).coerceIn(0f, 1f)
                val remaining = 1f - linearProgress
                1f - remaining * remaining
            } else {
                1f
            }
        }
    }
    val backgroundScrollConnection = remember(hasBackground) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (hasBackground) {
                    backgroundScrollOffset.floatValue =
                        (backgroundScrollOffset.floatValue - consumed.y).coerceAtLeast(0f)
                }
                return Offset.Zero
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = if (hasBackground) Color.Transparent else MiuixTheme.colorScheme.surface,
            topBar = {
                val navigationIcon: @Composable () -> Unit = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "Back")
                    }
                }
                if (hasBackground) {
                    Box {
                        if (backdrop != null) {
                            BlurredBar(
                                backdrop = backdrop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = collapsedBarProgress },
                                alpha = 0.8f,
                            ) {}
                        } else {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .graphicsLayer { alpha = collapsedBarProgress }
                                    .background(MiuixTheme.colorScheme.surface),
                            )
                        }
                        SmallTopAppBar(
                            title = collapsedTitle.orEmpty(),
                            color = Color.Transparent,
                            titleColor = MiuixTheme.colorScheme.onSurface.copy(
                                alpha = collapsedBarProgress,
                            ),
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                            actions = actions,
                        )
                    }
                } else {
                    val topBarColor = if (backdrop == null) MiuixTheme.colorScheme.surface else Color.Transparent
                    BlurredBar(backdrop = backdrop) {
                        if (title.isBlank()) {
                            SmallTopAppBar(
                                title = title,
                                color = topBarColor,
                                scrollBehavior = scrollBehavior,
                                navigationIcon = navigationIcon,
                                actions = actions,
                            )
                        } else {
                            AdaptiveTopAppBar(
                                title = title,
                                color = topBarColor,
                                scrollBehavior = scrollBehavior,
                                navigationIcon = navigationIcon,
                                actions = actions,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            ) {
                if (background != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationY = -backgroundScrollOffset.floatValue },
                        content = background,
                    )
                }
                CenteredContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding(),
                ) { sidePadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .nestedScroll(backgroundScrollConnection),
                        state = rememberLazyListState(),
                        overscrollEffect = null,
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                    ) {
                        content(sidePadding)
                    }
                }
            }
        }
    }
}

fun redactIdentifier(
    value: String,
    mode: RedactionMode,
): String {
    if (mode == RedactionMode.NONE || value.length < 8) return value
    return when (mode) {
        RedactionMode.NONE -> value
        RedactionMode.FULL -> "•".repeat(8)
        RedactionMode.MIDDLE -> {
            val visible = (value.length / 4).coerceIn(2, 6)
            value.take(visible) + "•".repeat(4) + value.takeLast(visible)
        }
    }
}
