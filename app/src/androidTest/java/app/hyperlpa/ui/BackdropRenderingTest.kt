package app.hyperlpa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

/** A transparent failed GPU program would expose the red source instead of the green capture. */
class BackdropRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun standardAndExtendedBlendsRenderCapturedPixelsWithAndWithoutMask() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                val backdrop = rememberLayerBackdrop { drawRect(Color.Green) }
                val blends = listOf(
                    BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
                    BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
                    BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
                )
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.Red))
                    Column {
                        repeat(4) { i ->
                            Box(Modifier.size(80.dp).testTag("capture$i").textureBlur(
                                backdrop, RoundedCornerShape(0.dp), blurRadius = if (i == 0) 0f else 150f,
                                colors = BlurColors(blendColors = if (i >= 2) blends else emptyList()),
                                contentBlendMode = if (i == 1 || i == 2) BlendMode.DstIn else BlendMode.SrcOver,
                            ).background(if (i == 1 || i == 2) Color.Black else Color.Transparent))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        repeat(4) { i ->
            val pixels = compose.onNodeWithTag("capture$i").captureToImage().toPixelMap()
            val color = pixels[pixels.width / 2, pixels.height / 2]
            assertEquals(1f, color.alpha, .01f)
            assertTrue("Capture $i must render green instead of transparent red: $color", color.green > .5f && color.red < .15f)
        }
    }
}
