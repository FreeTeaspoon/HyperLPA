package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.RedactionMode
import app.hyperlpa.ui.adaptive.AdaptiveTopAppBar
import app.hyperlpa.ui.adaptive.CenteredContent
import app.hyperlpa.ui.adaptive.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
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
    content: LazyListScope.(sidePadding: androidx.compose.ui.unit.Dp) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberAppBackdrop()
    val topBarColor = if (backdrop == null) MiuixTheme.colorScheme.surface else Color.Transparent
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop = backdrop) {
                AdaptiveTopAppBar(
                    title = title,
                    color = topBarColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        CenteredContent(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
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

fun redactIdentifier(
    value: String,
    mode: RedactionMode,
    reveal: Boolean,
): String {
    if (reveal || mode == RedactionMode.NONE || value.length < 8) return value
    return when (mode) {
        RedactionMode.NONE -> value
        RedactionMode.FULL -> "•".repeat(value.length.coerceAtMost(20))
        RedactionMode.MIDDLE -> {
            val visible = (value.length / 4).coerceIn(2, 6)
            value.take(visible) + "•".repeat((value.length - visible * 2).coerceAtLeast(4)) + value.takeLast(visible)
        }
    }
}
