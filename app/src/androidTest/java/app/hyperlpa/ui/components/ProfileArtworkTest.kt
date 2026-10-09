package app.hyperlpa.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.ui.theme.HyperLpaTheme
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun pendingArtworkIsBlankAndDefaultNeverOverlaysTheFirstImageFrame() {
        val profile = profiles(1, "render").single()
        val shown = mutableStateOf(ProfileArtworkState(null, ready = false))
        val background = Color.MAGENTA
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                ResolvedProfileArtwork(
                    profile = profile,
                    artwork = shown.value,
                    isEnabled = true,
                    modifier = Modifier.testTag("artwork").background(androidx.compose.ui.graphics.Color(background)),
                )
            }
        }
        val pending = compose.onNodeWithTag("artwork").captureToImage().asAndroidBitmap()
        assertCenterColor(pending, background)
        compose.runOnIdle { shown.value = ProfileArtworkState(null, ready = true) }
        val fallback = compose.onNodeWithTag("artwork").captureToImage().asAndroidBitmap()
        assertFalse("The default is shown only after loading finishes", pending.sameAs(fallback))

        compose.mainClock.autoAdvance = false
        val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        compose.runOnIdle { shown.value = ProfileArtworkState(image, ready = true) }
        compose.mainClock.advanceTimeByFrame()
        assertCenterColor(compose.onNodeWithTag("artwork").captureToImage().asAndroidBitmap(), Color.GREEN)
    }

    @Test fun detailRefreshKeepsThePreviousImageUntilItsReplacementIsReady() {
        val profiles = profiles(2, "detail-refresh")
        val shown = mutableStateOf(profiles.first())
        val emissions = mutableListOf<ProfileArtworkState>()
        compose.setContent {
            val artwork = rememberProfileArtwork(shown.value, null)
            SideEffect { emissions.add(artwork) }
        }
        compose.waitUntil { emissions.lastOrNull()?.ready == true }
        assertFalse("A cache miss is loading, not a missing icon", emissions.first().ready)
        val original = emissions.last().bitmap
        compose.runOnIdle {
            emissions.clear()
            shown.value = shown.value.copy(customIconUri = profiles.last().customIconUri)
        }
        compose.waitUntil { emissions.lastOrNull()?.ready == true }
        compose.runOnIdle {
            assertFalse(emissions.first().ready)
            assertSame(original, emissions.first().bitmap)
            assertTrue(original !== emissions.last().bitmap)
            emissions.clear()
            shown.value = shown.value.copy(customIconUri = null)
        }
        compose.waitUntil { emissions.lastOrNull()?.ready == true }
        compose.runOnIdle {
            assertEquals("Removing artwork must restore the default", null, emissions.last().bitmap)
        }
    }

    @Test fun replacingACachedThumbnailNeverExposesTheCardBackground() {
        val profile = profiles(1, "thumbnail-replacement").single()
        fun image() = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val shown = mutableStateOf(ProfileArtworkState(image(), ready = true))
        compose.setContent {
            HyperLpaTheme(settings = AppSettings()) {
                ResolvedProfileArtwork(
                    profile = profile,
                    artwork = shown.value,
                    isEnabled = true,
                    modifier = Modifier.testTag("artwork").background(androidx.compose.ui.graphics.Color.White),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // Disk and source decoders return different Bitmap instances for the same artwork.
        compose.runOnIdle { shown.value = ProfileArtworkState(image(), ready = true) }
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            assertCenterColor(compose.onNodeWithTag("artwork").captureToImage().asAndroidBitmap(), Color.GREEN)
        }
    }

    @Test fun cloudUpdatesReuseCustomIconsOnHomeAndDetailsWithoutDecodingAgain() {
        val profile = profiles(1, "cloud-refresh").single()
        val cloudIcon = mutableStateOf<ByteArray?>(null)
        val home = mutableListOf<ProfileArtworkLoadState>()
        val details = mutableListOf<ProfileArtworkState>()
        compose.setContent {
            val icons = cloudIcon.value?.let { mapOf(profile.iccid to it) }.orEmpty()
            val listArtwork = rememberProfileArtworkBitmaps(listOf(profile), icons, enabled = true)
            val detailArtwork = rememberProfileArtwork(profile, cloudIcon.value)
            SideEffect { home.add(listArtwork); details.add(detailArtwork) }
        }
        compose.waitUntil { home.lastOrNull()?.ready == true && details.lastOrNull()?.ready == true }
        val homeImage = home.last().bitmaps[profile.iccid]
        val detailImage = details.last().bitmap
        // If either path tries to decode the source again, it cannot recover this image.
        File(requireNotNull(profile.customIconUri).toUri().path!!).delete()
        compose.runOnIdle {
            home.clear()
            details.clear()
            cloudIcon.value = byteArrayOf(1, 2, 3)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(home.first().ready)
            assertTrue(details.first().ready)
            assertSame(homeImage, home.first().bitmaps[profile.iccid])
            assertSame(detailImage, details.first().bitmap)
        }
    }

    @Test fun coldReopenReadsSavedThumbnailsWithoutNeedingTheOriginalImage() {
        val profile = profiles(1, "cold-disk").single()
        val visible = mutableStateOf(true)
        val cloudIcon = mutableStateOf<ByteArray?>(null)
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        compose.setContent {
            if (visible.value) {
                val artwork = rememberProfileArtworkBitmaps(
                    listOf(profile), cloudIcon.value?.let { mapOf(profile.iccid to it) }.orEmpty(),
                    enabled = true, readerId = "cold-reader",
                )
                SideEffect { emissions.add(artwork) }
            }
        }
        awaitReady(emissions)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        File(requireNotNull(profile.customIconUri).toUri().path!!).delete()
        clearProfileArtworkMemoryForTesting()
        ProfileArtworkSnapshots.clearMemoryForTesting()
        compose.runOnIdle { emissions.clear(); visible.value = true }
        awaitReady(emissions)
        compose.runOnIdle {
            assertFalse(emissions.first().artwork(profile.iccid).ready)
            assertTrue("The saved thumbnail survives losing process memory", emissions.last().bitmaps[profile.iccid] != null)
        }
        val reopened = emissions.last().bitmaps[profile.iccid]
        compose.runOnIdle { emissions.clear(); cloudIcon.value = iconBytes(Color.GREEN) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(emissions.first().ready)
            assertSame("Cloud arrival must reuse the saved custom thumbnail", reopened, emissions.first().bitmaps[profile.iccid])
            assertSame(reopened, emissions.last().bitmaps[profile.iccid])
        }
    }

    @Test fun unreadableCustomIconStillAllowsCloudArtworkToRefresh() {
        val profile = profiles(1, "cloud-fallback").single().copy(customIconUri = "file:///${UUID.randomUUID()}.png")
        val cloudIcon = mutableStateOf(iconBytes(Color.RED))
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        compose.setContent {
            val artwork = rememberProfileArtworkBitmaps(
                listOf(profile), mapOf(profile.iccid to cloudIcon.value), enabled = true, readerId = "fallback-reader",
            )
            SideEffect { emissions.add(artwork) }
        }
        awaitReady(emissions)
        compose.runOnIdle {
            assertEquals(Color.RED, emissions.last().bitmaps.getValue(profile.iccid).getPixel(64, 64))
            emissions.clear()
            cloudIcon.value = iconBytes(Color.BLUE)
        }
        awaitReady(emissions)
        compose.runOnIdle {
            assertEquals(Color.BLUE, emissions.last().bitmaps.getValue(profile.iccid).getPixel(64, 64))
        }
    }

    @Test fun unresolvedCloudSnapshotsStayWithTheirProfileAndReader() {
        val profiles = profiles(2, "cloud-snapshots").map { it.copy(customIconUri = null) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reader = mutableStateOf("snapshot-reader-one")
        val selected = mutableStateOf(profiles.first())
        val colors = listOf(Color.RED, Color.BLUE)
        profiles.forEachIndexed { index, profile ->
            val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[index]) }
            ProfileArtworkSnapshots.put(context, reader.value, profile, image)
        }
        val emissions = mutableListOf<ProfileArtworkLoadState>()
        val details = mutableListOf<ProfileArtworkState>()
        compose.setContent {
            val artwork = rememberProfileArtworkBitmaps(
                profiles, emptyMap(), enabled = true, readerId = reader.value, cloudIconLookupEnabled = true,
            )
            val detailArtwork = rememberProfileArtwork(
                selected.value, null, readerId = reader.value, cloudIconLookupEnabled = true,
            )
            SideEffect { emissions.add(artwork); details.add(detailArtwork) }
        }
        awaitReady(emissions)
        compose.waitUntil { details.lastOrNull()?.ready == true }
        compose.runOnIdle {
            assertEquals(Color.RED, details.last().bitmap!!.getPixel(64, 64))
            details.clear()
            selected.value = profiles.last()
        }
        compose.waitUntil { details.lastOrNull()?.ready == true }
        compose.runOnIdle {
            assertEquals(Color.BLUE, details.first().bitmap!!.getPixel(64, 64))
            assertEquals(Color.BLUE, details.last().bitmap!!.getPixel(64, 64))
        }
        // Recomposition used to put snapshots for absent cloud sources under the same cache key.
        compose.runOnIdle { emissions.clear(); details.clear(); reader.value = "snapshot-reader-two" }
        awaitReady(emissions)
        compose.waitUntil { details.lastOrNull()?.ready == true }
        compose.runOnIdle {
            assertTrue("Another reader must not inherit these snapshots", emissions.all { it.bitmaps.isEmpty() })
            assertTrue(details.all { it.bitmap == null })
            emissions.clear()
            reader.value = "snapshot-reader-one"
        }
        awaitReady(emissions)
        compose.runOnIdle {
            profiles.forEachIndexed { index, profile ->
                assertEquals(colors[index], emissions.first().bitmaps.getValue(profile.iccid).getPixel(64, 64))
            }
        }
    }

    @Test fun cloudLookupKeepsDefaultHiddenUntilNoArtworkIsConfirmed() {
        val profile = profiles(1, "pending-cloud").single().copy(customIconUri = null)
        val pending = mutableStateOf(true)
        val home = mutableListOf<ProfileArtworkLoadState>()
        val details = mutableListOf<ProfileArtworkState>()
        compose.setContent {
            val listArtwork = rememberProfileArtworkBitmaps(
                listOf(profile), emptyMap(), enabled = true, cloudIconLookupPending = pending.value,
            )
            val detailArtwork = rememberProfileArtwork(profile, null, cloudIconLookupPending = pending.value)
            SideEffect { home.add(listArtwork); details.add(detailArtwork) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(home.last().artwork(profile.iccid).ready)
            assertFalse(details.last().ready)
            pending.value = false
        }
        compose.waitUntil { home.lastOrNull()?.ready == true && details.lastOrNull()?.ready == true }
        compose.runOnIdle {
            assertTrue(home.last().artwork(profile.iccid).ready)
            assertEquals(null, details.last().bitmap)
        }
    }

    private fun assertCenterColor(bitmap: Bitmap, color: Int) {
        for (x in bitmap.width / 3 until bitmap.width * 2 / 3) {
            for (y in bitmap.height / 3 until bitmap.height * 2 / 3) {
                assertEquals("Unexpected placeholder or overlay at $x,$y", color, bitmap.getPixel(x, y))
            }
        }
    }

    private fun iconBytes(color: Int): ByteArray {
        val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            image.recycle()
            output.toByteArray()
        }
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
