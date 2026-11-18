package app.hyperlpa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.hyperlpa.ui.theme.LocalBlurEnabled
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun rememberAppBackdrop(): LayerBackdrop? {
    if (
        !LocalBlurEnabled.current ||
        !isRenderEffectSupported() ||
        !isRuntimeShaderSupported()
    ) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

@Composable
fun rememberContentBackdrop(): LayerBackdrop? {
    if (
        !LocalBlurEnabled.current ||
        !isRenderEffectSupported() ||
        !isRuntimeShaderSupported()
    ) return null
    return rememberLayerBackdrop { drawContent() }
}

@Composable
fun Modifier.appBackdropBlur(
    backdrop: LayerBackdrop?,
    shape: Shape,
    color: Color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f),
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
        )
    },
)
