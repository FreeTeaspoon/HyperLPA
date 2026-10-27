package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.ui.components.liquid.NzbLiquidGlassNavigationBar
import app.hyperlpa.ui.theme.HyperLpaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported

class LiquidNavigationBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassSelectionIsVisibleOnFirstDraw() = checkFirstDraw(blur = true)

    @Test fun opaqueSelectionIsVisibleOnFirstDraw() = checkFirstDraw(blur = false)

    @Test fun iconOnlyRtlSelectionIsVisibleOnFirstDraw() = checkFirstDraw(
        blur = true,
        showLabels = false,
        layoutDirection = LayoutDirection.Rtl,
    )

    private fun checkFirstDraw(
        blur: Boolean,
        showLabels: Boolean = true,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        assumeTrue(!blur || (isRenderEffectSupported() && isRuntimeShaderSupported()))
        lateinit var firstFrame: GraphicsLayer
        var recorded = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = blur, themeMode = ThemeMode.LIGHT)) {
                val backdrop = rememberAppBackdrop()
                firstFrame = rememberGraphicsLayer()
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Box {
                        Box(Modifier.fillMaxSize().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier))
                        NzbLiquidGlassNavigationBar(
                            items = List(4) { NavigationItem("Tab $it", MiuixIcons.BankCards) },
                            selectedIndex = 0,
                            onItemClick = {},
                            backdrop = backdrop,
                            isBlurActive = backdrop != null,
                            isDark = false,
                            showLabels = showLabels,
                            modifier = Modifier.width(360.dp).drawWithContent {
                                // Keep the initial display list, before a size callback can
                                // schedule a composition that adds the missing selection.
                                if (!recorded) {
                                    firstFrame.record { this@drawWithContent.drawContent() }
                                    recorded = true
                                }
                                drawLayer(firstFrame)
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("The bar must have drawn", recorded)
        val bitmap = runBlocking { firstFrame.toImageBitmap() }
        assertTrue(
            "The initial draw must include the blue selected tab",
            countBluePixels(bitmap) > 20,
        )
    }

    private fun countBluePixels(bitmap: ImageBitmap): Int {
        val pixels = bitmap.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.alpha > 0.5f && color.blue > color.red + 0.2f && color.blue > color.green + 0.1f) count++
            }
        }
        return count
    }
}
