package app.hyperlpa.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import android.util.Base64
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProfileArtwork(
    profile: ProfileInfo,
    cloudIcon: ByteArray?,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    cornerRadius: Dp = 12.dp,
) {
    val bitmap = rememberProfileArtworkBitmap(profile, cloudIcon)
    ResolvedProfileArtwork(
        profile = profile,
        bitmap = bitmap,
        isEnabled = isEnabled,
        modifier = modifier,
        size = size,
        cornerRadius = cornerRadius,
    )
}

@Composable
internal fun rememberProfileArtworkBitmap(
    profile: ProfileInfo?,
    cloudIcon: ByteArray?,
): Bitmap? {
    val context = LocalContext.current
    val input = profile?.artworkInput(cloudIcon)
    val cached = input?.let(ProfileArtworkCache::get)
    return key(input, cloudIcon?.contentHashCode()) {
        val bitmap by produceState(initialValue = cached?.bitmap) {
            if (cached != null) return@produceState
            value = withContext(Dispatchers.IO) {
                loadProfileArtworkBitmap(context, profile, cloudIcon)
                    .also { bitmap -> input?.let { ProfileArtworkCache.put(it, bitmap) } }
            }
        }
        bitmap
    }
}

/**
 * Resolves the artwork of a whole profile list. Artwork decoded before, for any list, is returned
 * in the first composition, so a card shown again never passes through placeholders.
 */
@Composable
internal fun rememberProfileArtworkBitmaps(
    profiles: List<ProfileInfo>,
    cloudIcons: Map<String, ByteArray>,
    enabled: Boolean,
): ProfileArtworkLoadState {
    if (!enabled || profiles.isEmpty()) return ProfileArtworkLoadState.ReadyWithoutArtwork
    val context = LocalContext.current
    val artworkInputs = remember(profiles, cloudIcons) {
        profiles.map { profile -> profile.artworkInput(cloudIcons[profile.iccid]) }
    }
    val cachedArtwork = remember(artworkInputs) {
        artworkInputs.mapNotNull { input -> ProfileArtworkCache.get(input)?.let { input.iccid to it } }.toMap()
    }
    val previousState = remember { mutableStateOf<ProfileArtworkLoadState?>(null) }
    return key(artworkInputs) {
        val cachedBitmaps = cachedArtwork.mapNotNull { (iccid, artwork) -> artwork.bitmap?.let { iccid to it } }.toMap()
        val initialState = if (cachedArtwork.size == artworkInputs.size) {
            ProfileArtworkLoadState(bitmaps = cachedBitmaps, ready = true)
        } else {
            // Keep the artwork a profile already shows until its replacement is decoded.
            val carriedBitmaps = previousState.value?.bitmaps
                ?.filterKeys { iccid -> iccid !in cachedArtwork && artworkInputs.any { it.iccid == iccid } }
                .orEmpty()
            ProfileArtworkLoadState(bitmaps = carriedBitmaps + cachedBitmaps, ready = false)
        }
        val loadState by produceState(initialValue = initialState) {
            if (initialState.ready) {
                previousState.value = initialState
                return@produceState
            }
            val loaded = withContext(Dispatchers.IO) {
                profiles.zip(artworkInputs)
                    .filter { (_, input) -> input.iccid !in cachedArtwork }
                    .mapNotNull { (profile, input) ->
                        loadProfileArtworkBitmap(context, profile, cloudIcons[profile.iccid])
                            .also { bitmap -> ProfileArtworkCache.put(input, bitmap) }
                            ?.let { bitmap -> input.iccid to bitmap }
                    }
                    .toMap()
            }
            val readyState = ProfileArtworkLoadState(bitmaps = cachedBitmaps + loaded, ready = true)
            previousState.value = readyState
            value = readyState
        }
        loadState
    }
}

internal data class ProfileArtworkLoadState(
    val bitmaps: Map<String, Bitmap>,
    val ready: Boolean,
) {
    companion object {
        val ReadyWithoutArtwork = ProfileArtworkLoadState(bitmaps = emptyMap(), ready = true)
    }
}

@Composable
internal fun ResolvedProfileArtwork(
    profile: ProfileInfo,
    bitmap: Bitmap?,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    cornerRadius: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    key(profile.iccid) {
        Crossfade(
            targetState = bitmap,
            modifier = modifier.size(size),
            animationSpec = tween(durationMillis = ProfileArtworkCrossfadeMillis),
            label = "Profile artwork",
        ) { resolvedBitmap ->
            if (resolvedBitmap != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    color = MiuixTheme.colorScheme.secondaryContainer,
                ) {
                    Image(
                        bitmap = resolvedBitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.profile_artwork_description,
                            profile.providerName.ifBlank { stringResource(R.string.profile_default_name) },
                        ),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.BankCards,
                        contentDescription = null,
                        modifier = Modifier.size(size * 0.52f),
                        tint = if (isEnabled) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                }
            }
        }
    }
}

private const val MaxEmbeddedIconBase64Characters = 1_500_000
private const val MaxRenderedProfileArtworkDimension = 512
private const val ProfileArtworkCrossfadeMillis = 140

private const val MaxCachedArtworkBytes = 24 * 1024 * 1024

private data class ProfileArtworkInput(
    val iccid: String,
    val customIconUri: String?,
    val embeddedIcon: String?,
    val cloudIconHash: Int?,
)

private fun ProfileInfo.artworkInput(cloudIcon: ByteArray?) = ProfileArtworkInput(
    iccid = iccid,
    customIconUri = customIconUri,
    embeddedIcon = iconBase64,
    cloudIconHash = cloudIcon?.contentHashCode(),
)

/** A decoded result, where a null [bitmap] records that the profile has no usable artwork. */
private class CachedProfileArtwork(val bitmap: Bitmap?)

private object ProfileArtworkCache {
    private val cached = object : LruCache<ProfileArtworkInput, CachedProfileArtwork>(MaxCachedArtworkBytes) {
        override fun sizeOf(key: ProfileArtworkInput, value: CachedProfileArtwork): Int =
            value.bitmap?.allocationByteCount?.coerceAtLeast(1) ?: 1
    }

    fun get(input: ProfileArtworkInput): CachedProfileArtwork? = cached.get(input)

    fun put(input: ProfileArtworkInput, bitmap: Bitmap?) {
        cached.put(input, CachedProfileArtwork(bitmap))
    }
}

private fun loadProfileArtworkBitmap(
    context: android.content.Context,
    profile: ProfileInfo?,
    cloudIcon: ByteArray?,
): Bitmap? {
    val custom = profile?.customIconUri
        ?.let { uri -> runCatching { uri.toUri() }.getOrNull() }
        ?.let { uri ->
            runCatching {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri),
                ) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longestEdge = maxOf(info.size.width, info.size.height)
                    if (longestEdge > MaxRenderedProfileArtworkDimension) {
                        val scale = MaxRenderedProfileArtworkDimension.toFloat() / longestEdge
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        }
    if (custom != null) return custom

    val embedded = profile?.iconBase64
        ?.takeIf { encoded -> encoded.length <= MaxEmbeddedIconBase64Characters }
        ?.let { encoded -> runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() }
    val embeddedBitmap = embedded?.let(::decodeProfileBitmap)
    return when {
        embedded != null && embedded.size >= 2_048 && embeddedBitmap != null -> embeddedBitmap
        cloudIcon != null -> decodeProfileBitmap(cloudIcon) ?: embeddedBitmap
        else -> embeddedBitmap
    }
}

private fun decodeProfileBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MaxRenderedProfileArtworkDimension ||
            bounds.outHeight / sampleSize > MaxRenderedProfileArtworkDimension
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}
