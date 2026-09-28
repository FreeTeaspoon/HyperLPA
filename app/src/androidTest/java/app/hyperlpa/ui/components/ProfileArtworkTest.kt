package app.hyperlpa.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileArtworkTest {
    @get:Rule val compose = createComposeRule()
    private val directory = File(
        ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "artwork-test-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    @After fun cleanUp() { directory.deleteRecursively() }

    @Test fun manyIconsArePresentInFirstCompositionWhenReturningToReader() {
        val first = profiles(80, "first")
        val second = profiles(80, "second")
        val shown = mutableStateOf(first)
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        compose.setContent {
            val artwork = rememberProfileArtworkBitmaps(shown.value, emptyMap(), enabled = true)
            SideEffect { emissions.add(artwork) }
        }
        awaitReady(emissions)
        val original = emissions.last().bitmaps
        assertTrue(original.values.all { it.width <= 128 && it.height <= 128 })
        compose.runOnIdle { emissions.clear(); shown.value = second }
        awaitReady(emissions)
        compose.runOnIdle { emissions.clear(); shown.value = first }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Returning to a cached reader must not show placeholders", emissions.first().ready)
            first.forEach { assertSame(original[it.iccid], emissions.first().bitmaps[it.iccid]) }
        }
    }

    @Test fun profileSwitchRetainsEvenAVeryLargeList() {
        val profiles = profiles(180, "large")
        val shown = mutableStateOf(profiles)
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        compose.setContent {
            val artwork = rememberProfileArtworkBitmaps(shown.value, emptyMap(), enabled = true)
            SideEffect { emissions.add(artwork) }
        }
        awaitReady(emissions)
        val original = emissions.last().bitmaps
        compose.runOnIdle {
            emissions.clear()
            shown.value = profiles.reversed().map { it.copy(state = ProfileState.ENABLED) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(emissions.first().ready)
            profiles.forEach { assertSame(original[it.iccid], emissions.first().bitmaps[it.iccid]) }
        }
    }

    @Test fun diskThumbnailsAppearWithNamesAfterProcessMemoryIsGone() {
        val profiles = profiles(80, "restart")
        val visible = mutableStateOf(true)
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            if (visible.value) {
                val artwork = rememberProfileArtworkBitmaps(
                    profiles, emptyMap(), enabled = true, readerId = "reader",
                )
                SideEffect { emissions.add(artwork) }
            }
        }
        awaitReady(emissions)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        clearProfileArtworkMemoryForTesting()
        ProfileArtworkSnapshots.clearMemoryForTesting()
        runBlocking { ProfileArtworkSnapshots.prewarm(context) }
        compose.runOnIdle { emissions.clear(); visible.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Reopening must have every thumbnail in the first composition", emissions.first().ready)
            assertTrue(emissions.first().bitmaps.keys.containsAll(profiles.map(ProfileInfo::iccid)))
        }
    }

    @Test fun profilesWithSameArtworkShareBitmapAndChangedSourceReplacesIt() {
        val profiles = profiles(2, "shared")
        val shared = listOf(profiles[0], profiles[1].copy(customIconUri = profiles[0].customIconUri))
        val shown = mutableStateOf(shared)
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        compose.setContent {
            val artwork = rememberProfileArtworkBitmaps(shown.value, emptyMap(), enabled = true)
            SideEffect { emissions.add(artwork) }
        }
        awaitReady(emissions)
        val bitmaps = emissions.last().bitmaps
        assertSame(bitmaps[shared[0].iccid], bitmaps[shared[1].iccid])
        compose.runOnIdle { emissions.clear(); shown.value = profiles }
        awaitReady(emissions)
        assertTrue(emissions.last().bitmaps[profiles[1].iccid] !== bitmaps[shared[1].iccid])
    }

    private fun awaitReady(emissions: List<ProfileArtworkLoadState>) {
        compose.waitUntil(20_000) { emissions.lastOrNull()?.ready == true }
    }

    private fun profiles(count: Int, prefix: String): List<ProfileInfo> = List(count) { index ->
        val file = File(directory, "$prefix-$index.png")
        val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(index % 256, 100, 180))
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        ProfileInfo(
            iccid = "$prefix-$index", state = ProfileState.DISABLED, name = "Profile $index",
            nickname = "", providerName = "Provider", isdPAid = "",
            profileClass = ProfileClass.OPERATIONAL, customIconUri = file.toURI().toString(),
        )
    }
}
