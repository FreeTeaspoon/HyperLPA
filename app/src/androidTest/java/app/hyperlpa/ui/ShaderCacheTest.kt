package app.hyperlpa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.material.LocalRuntimeShaderCache
import top.yukonga.miuix.kmp.material.RuntimeShaderCache

class ShaderCacheTest {
    @get:Rule val compose = createComposeRule()

    @Test fun independentConsumersAndReopenedRoutesKeepTheRootShaderCache() {
        val childVisible = mutableStateOf(true)
        val dark = mutableStateOf(false)
        lateinit var rootCache: RuntimeShaderCache
        lateinit var childCache: RuntimeShaderCache
        lateinit var independentRoot: RuntimeShaderCache
        compose.setContent {
            HyperLpaTheme(AppSettings(pureBlack = dark.value)) {
                ReadCache { rootCache = it }
                if (childVisible.value) ReadCache { childCache = it }
            }
            HyperLpaTheme(AppSettings()) { ReadCache { independentRoot = it } }
        }
        compose.runOnIdle {
            assertSame(rootCache, childCache)
            assertNotSame(rootCache, independentRoot)
        }
        val original = rootCache
        compose.runOnIdle { childVisible.value = false; dark.value = true }
        compose.runOnIdle { childVisible.value = true }
        compose.runOnIdle {
            assertSame(original, rootCache)
            assertSame(original, childCache)
        }
    }

    @Composable private fun ReadCache(onCache: (RuntimeShaderCache) -> Unit) {
        val cache = LocalRuntimeShaderCache.current
        SideEffect { onCache(cache) }
    }
}
