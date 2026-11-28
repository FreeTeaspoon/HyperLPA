package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.material.isRenderEffectSupported
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

class Os4NavigationBarStartupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun solidBarDrawsSelectionOnFirstFrame() = checkFirstDraw(blur = false)

    @Test fun glassBarDrawsSelectionOnFirstFrame() = checkFirstDraw(blur = true)

    private fun checkFirstDraw(blur: Boolean) {
        assumeTrue(!blur || (isRenderEffectSupported() && isRuntimeShaderSupported()))
        lateinit var firstFrame: GraphicsLayer
        var recorded = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = blur)) {
                val backdrop = rememberAppBackdrop(captureContent = false)
                firstFrame = rememberGraphicsLayer()
                Box {
                    Box(Modifier.fillMaxSize().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier))
                    GlassNavigationBar(
                        items = List(4) { GlassNavigationItem(MiuixIcons.BankCards, "Tab $it") },
                        selectedIndex = 2,
                        onSelect = {},
                        backdrop = backdrop,
                        indicatorColor = Color.Magenta,
                        modifier = Modifier.drawWithContent {
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
        compose.waitForIdle()
        assertTrue("The bar must have drawn", recorded)
        val pixels = runBlocking { firstFrame.toImageBitmap() }.toPixelMap()
        val selectedSlot = (pixels.width * 5 / 8)
        assertTrue("The first draw must show the selected tab", (0 until pixels.height).any { y ->
            val color = pixels[selectedSlot, y]
            color.alpha > 0.5f && color.red > color.green + 0.4f && color.blue > color.green + 0.4f
        })
    }
}
