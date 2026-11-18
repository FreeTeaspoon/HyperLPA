package app.hyperlpa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.ThemeMode
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.material.DefaultRuntimeShaderCache
import top.yukonga.miuix.kmp.material.LocalRuntimeShaderCache
import top.yukonga.miuix.kmp.material.RuntimeShader
import top.yukonga.miuix.kmp.material.RuntimeShaderCache

class HeaderBlurActivationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparentHeaderBuildsNoBlurUntilLongPageScrolls() = checkActivation(short = false)

    @Test fun transparentHeaderBuildsNoBlurUntilShortPageCollapses() = checkActivation(short = true)

    @Test fun scrollBlurFollowsDistanceAndFadesAfterInstantReturn() {
        val scroll = TopAppBarState(-100f, 0f, 0f)
        var pixelsPerDp = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = true, themeMode = ThemeMode.LIGHT)) {
                pixelsPerDp = LocalDensity.current.density
                val backdrop = checkNotNull(rememberAppBackdrop())
                Box(Modifier.size(280.dp, 200.dp).testTag("blur-scene")) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.Red))
                    Os4TopAppBar(title = "", backdrop = backdrop, scrollBehavior = MiuixScrollBehavior(scroll),
                        viewport = HeaderViewport({ true }, { scroll.contentOffset < 0 }, { true }),
                        fixed = true, defaultWindowInsetsPadding = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        fun tint(): Float = compose.onNodeWithTag("blur-scene").captureToImage().toPixelMap().let {
            it[it.width / 2, (20 * pixelsPerDp).toInt()].green
        }
        val clear = tint()
        compose.runOnIdle { scroll.contentOffset = -12 * pixelsPerDp }
        compose.mainClock.advanceTimeBy(500)
        val partial = tint()
        compose.runOnIdle { scroll.contentOffset = -64 * pixelsPerDp }
        compose.mainClock.advanceTimeBy(500)
        val full = tint()
        assertTrue("Tint should increase with scroll: $clear, $partial, $full", clear < partial && partial < full)
        compose.runOnIdle { scroll.contentOffset = 0f }
        compose.mainClock.advanceTimeBy(160)
        val leaving = tint()
        assertTrue("A jump to the top should leave a fading band: $clear, $leaving, $full", clear < leaving && leaving < full)
        compose.mainClock.advanceTimeBy(500)
        assertTrue("The band should clear after fading", tint() <= clear + .01f)
    }

    private fun checkActivation(short: Boolean) {
        val requests = mutableListOf<String>()
        val cache = object : RuntimeShaderCache {
            val delegate = DefaultRuntimeShaderCache()
            override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
                requests += key
                return delegate.obtainRuntimeShader(key, string)
            }
        }
        lateinit var scroll: ScrollBehavior
        compose.setContent {
            HyperLpaTheme(AppSettings()) {
                CompositionLocalProvider(LocalRuntimeShaderCache provides cache) {
                    val backdrop = rememberAppBackdrop()
                    scroll = MiuixScrollBehavior()
                    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {}
                    Os4TopAppBar(
                        title = "Settings",
                        backdrop = backdrop,
                        scrollBehavior = scroll,
                        viewport = HeaderViewport({ true }, { false }, { !short }, { if (short) 0f else 1000f }),
                    )
                }
            }
        }
        compose.runOnIdle {
            assertTrue("Fully transparent header allocated shaders: $requests", requests.isEmpty())
            if (short) scroll.state.heightOffset = scroll.state.heightOffsetLimit
            else scroll.state.contentOffset = -100f
        }
        compose.runOnIdle {
            assertTrue("Visible header must retain the OS4 blur pipeline", requests.isNotEmpty())
            scroll.state.heightOffset = 0f
            scroll.state.contentOffset = 0f
        }
        compose.waitForIdle()
    }
}
