package app.hyperlpa.ui

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.ui.screens.AboutScreen
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassHeroRemainsVisible() = checkHero(blur = true, dark = false)
    @Test fun opaqueFallbackHeroRemainsVisible() = checkHero(blur = false, dark = false)

    @Test fun darkGlassHeroRemainsVisible() = checkHero(blur = true, dark = true)
    @Test fun darkFallbackHeroRemainsVisible() = checkHero(blur = false, dark = true)

    private fun checkHero(blur: Boolean, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = blur, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                AboutScreen(onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        val mark = compose.onNodeWithContentDescription("HyperLPA").captureToImage().toPixelMap()
        val title = compose.onAllNodesWithText("HyperLPA")[0].captureToImage().toPixelMap()
        // Glass follows the background hue. Test sharp silhouette/glyph edges rather than
        // requiring a fixed lightness that would reject the reference dark blend colors.
        fun hasVisibleDetail(pixels: androidx.compose.ui.graphics.PixelMap): Boolean {
            val step = (pixels.width / 50).coerceAtLeast(1)
            var edges = 0
            for (y in 0 until pixels.height - step) for (x in 0 until pixels.width - step) {
                val p = pixels[x, y]
                val right = pixels[x + step, y]
                val below = pixels[x, y + step]
                fun delta(other: androidx.compose.ui.graphics.Color) = maxOf(
                    kotlin.math.abs(p.red - other.red), kotlin.math.abs(p.green - other.green),
                    kotlin.math.abs(p.blue - other.blue),
                )
                if (maxOf(delta(right), delta(below)) > .08f) edges++
            }
            return edges > pixels.width * pixels.height / 100
        }
        assertTrue("The real app mark must be visible with blur=$blur", hasVisibleDetail(mark))
        // The SIM contacts keep their gold colour over either theme's animated background.
        var goldPixels = 0
        for (y in 0 until mark.height) for (x in 0 until mark.width) {
            val pixel = mark[x, y]
            if (pixel.red > .65f && pixel.green > .4f && pixel.blue < .65f && pixel.red > pixel.blue * 1.2f) goldPixels++
        }
        assertTrue("The coloured app mark must not be tinted or covered by the gradient", goldPixels > mark.width * mark.height / 20)
        assertTrue("The app name must be visible with blur=$blur", hasVisibleDetail(title))
    }
}
