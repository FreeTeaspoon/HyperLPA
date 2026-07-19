package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import app.hyperlpa.ui.theme.LocalBlurEnabled
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
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
fun BlurredBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    alpha: Float = 0.82f,
    highlight: Highlight? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.then(
            if (backdrop == null) {
                Modifier
            } else {
                Modifier.textureBlur(
                    backdrop = backdrop,
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
