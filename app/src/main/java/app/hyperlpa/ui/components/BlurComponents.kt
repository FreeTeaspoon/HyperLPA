package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.hyperlpa.ui.theme.LocalBlurEnabled
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberAppBackdrop(): LayerBackdrop? {
    if (!LocalBlurEnabled.current || !isRuntimeShaderSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

@Composable
fun rememberContentBackdrop(): LayerBackdrop? {
    if (!LocalBlurEnabled.current || !isRuntimeShaderSupported()) return null
    return rememberLayerBackdrop { drawContent() }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    alpha: Float = 0.82f,
    highlight: Highlight? = null,
    progressive: Boolean = false,
    scrollBehavior: ScrollBehavior? = null,
    content: @Composable () -> Unit,
) {
    val blurActive = backdrop != null
    Box(
        modifier = modifier.then(
            if (!blurActive || progressive) {
                Modifier
            } else {
                Modifier.textureBlur(
                    backdrop = checkNotNull(backdrop),
                    shape = shape,
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(
                            BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = alpha)),
                        ),
                    ),
                    highlight = highlight,
                )
            },
        ),
    ) {
        if (blurActive && progressive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        this.alpha = scrollBehavior?.state
                            ?.let { (-it.contentOffset / 48.dp.toPx()).coerceIn(0f, 1f) }
                            ?: 1f
                    }
                    .progressiveTextureBlur(
                        backdrop = checkNotNull(backdrop),
                        shape = shape,
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        blurRadius = 10f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    MiuixTheme.colorScheme.surface.copy(alpha = 0.3f),
                                ),
                            ),
                        ),
                        highlight = highlight,
                    ),
            )
        }
        content()
    }
}

@Composable
fun Modifier.appBackdropBlur(
    backdrop: LayerBackdrop?,
    shape: Shape,
    color: Color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
    highlight: Highlight? = null,
): Modifier = then(
    if (backdrop == null) {
        Modifier
    } else {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = 25f,
            colors = BlurDefaults.blurColors(
                blendColors = listOf(BlendColorEntry(color)),
            ),
            highlight = highlight,
        )
    },
)
