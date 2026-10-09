package app.hyperlpa.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
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
    val artwork = rememberProfileArtwork(profile, cloudIcon)
    ResolvedProfileArtwork(
        profile = profile,
        artwork = artwork,
        isEnabled = isEnabled,
        modifier = modifier,
        size = size,
        cornerRadius = cornerRadius,
    )
}

@Composable
internal fun rememberProfileArtwork(
    profile: ProfileInfo?,
    cloudIcon: ByteArray?,
    readerId: String? = null,
    cloudIconLookupEnabled: Boolean = false,
    cloudIconLookupPending: Boolean = false,
): ProfileArtworkState {
    val context = LocalContext.current
    val input = profile?.artworkInput(cloudIcon)
    val cached = input?.let(ProfileArtworkCache::get)
    val useSnapshot = cloudIconLookupEnabled || profile?.customIconUri != null || profile?.iconBase64 != null
    val snapshot = remember(readerId, profile?.iccid, input, useSnapshot) {
        if (cached == null && useSnapshot) profile?.let { ProfileArtworkSnapshots.get(readerId, it) } else null
    }
    val retained = remember(readerId, profile?.iccid) { RetainedProfileArtwork() }
    val artwork = key(readerId, profile?.iccid, input, useSnapshot) {
        val initial = remember {
            ProfileArtworkState(
                bitmap = if (cached != null) cached.bitmap else profile?.let {
                    retained.bitmaps[it.iccid] ?: snapshot?.bitmap
                },
                ready = cached != null || profile == null,
            )
        }
        val resolved by produceState(initialValue = initial) {
            if (cached != null || profile == null || input == null) return@produceState
            // A saved thumbnail can fill the hero immediately while its larger image decodes.
            val thumbnail = snapshot ?: if (useSnapshot) {
                withContext(Dispatchers.IO) {
                    ProfileArtworkSnapshots.load(context, readerId, profile)
                }
            } else null
            if (value.bitmap == null && thumbnail != null) value = ProfileArtworkState(thumbnail.bitmap, ready = false)
            val loaded = withContext(Dispatchers.IO) {
                loadProfileArtwork(context, profile, cloudIcon)
            }
            // A failed refresh must not discard a saved image for this exact profile/source.
            val result = if (loaded.bitmap == null && thumbnail != null) {
                CachedProfileArtwork(thumbnail.bitmap, isSnapshot = true)
            } else loaded
            ProfileArtworkCache.put(input, result)
            value = ProfileArtworkState(result.bitmap, ready = true)
        }
        SideEffect {
            retained.bitmaps = resolved.bitmap?.let { mapOf(requireNotNull(profile).iccid to it) }.orEmpty()
        }
        resolved
    }
    return artwork.copy(
        ready = artwork.ready && (artwork.bitmap != null || !cloudIconLookupPending || cloudIcon != null),
    )
}

internal data class ProfileArtworkState(val bitmap: Bitmap?, val ready: Boolean)

/** Cached thumbnails are available in the same composition as their profile names. */
@Composable
internal fun rememberProfileArtworkBitmaps(
    profiles: List<ProfileInfo>,
    cloudIcons: Map<String, ByteArray>,
    enabled: Boolean,
    readerId: String? = null,
    cloudIconLookupEnabled: Boolean = false,
    cloudIconLookupPending: Boolean = false,
): ProfileArtworkLoadState {
    if (!enabled || profiles.isEmpty()) return ProfileArtworkLoadState.ReadyWithoutArtwork
    val context = LocalContext.current
    val inputs = remember(profiles, cloudIcons) {
        profiles.associate { profile ->
            profile.iccid to profile.artworkInput(cloudIcons[profile.iccid], ProfileThumbnailDimension)
        }
    }
    // Retain the current list independently of LRU eviction. A state change, reorder or search
    // must not decode images that this page already owns, even for a very large card.
    val retained = remember(readerId) { RetainedProfileArtwork() }
    return key(readerId, inputs, cloudIconLookupEnabled) {
        val carried = remember { retained.bitmaps.filterKeys(inputs::containsKey) }
        val initial = remember {
            profiles.mapNotNull { profile ->
                val input = inputs.getValue(profile.iccid)
                val useSnapshot = cloudIconLookupEnabled || profile.customIconUri != null || profile.iconBase64 != null
                (retained.artwork[input] ?: ProfileArtworkCache.get(input)
                    ?: ProfileArtworkSnapshots.get(readerId, profile)
                        ?.takeIf { useSnapshot && (it.isCustom || cloudIcons[profile.iccid] == null) }
                        ?.let { CachedProfileArtwork(it.bitmap, isCustom = it.isCustom, isSnapshot = true) })
                    ?.let { profile.iccid to it }
            }.toMap()
        }
        val resolved by produceState(initialValue = initial) {
            for (profile in profiles) {
                if (profile.iccid in value) continue
                val input = inputs.getValue(profile.iccid)
                val artwork = withContext(Dispatchers.IO) {
                    val useSnapshot = cloudIconLookupEnabled || profile.customIconUri != null || profile.iconBase64 != null
                    ProfileArtworkCache.get(input) ?: run {
                        val snapshot = if (useSnapshot) ProfileArtworkSnapshots.load(context, readerId, profile) else null
                        val loaded = snapshot?.takeIf { it.isCustom || cloudIcons[profile.iccid] == null }
                            ?.let { CachedProfileArtwork(it.bitmap, isCustom = it.isCustom, isSnapshot = true) } ?: loadProfileArtwork(
                            context, profile, cloudIcons[profile.iccid], ProfileThumbnailDimension,
                        ).also { artwork ->
                            artwork.bitmap?.let {
                                ProfileArtworkSnapshots.put(context, readerId, profile, it, isCustom = artwork.isCustom)
                            }
                        }
                        loaded.also { ProfileArtworkCache.put(input, it) }
                    }
                }
                // Publish each result instead of waiting for every icon on the card.
                value = value + (profile.iccid to artwork)
            }
        }
        val bitmaps = inputs.keys.mapNotNull { iccid ->
            val bitmap = if (iccid in resolved) resolved[iccid]?.bitmap else carried[iccid]
            bitmap?.let { iccid to it }
        }.toMap()
        SideEffect {
            retained.artwork = resolved.filterValues { !it.isSnapshot }
                .map { (iccid, artwork) -> inputs.getValue(iccid) to artwork }.toMap()
            retained.bitmaps = bitmaps
        }
        val readyIccids = resolved.keys.filterTo(hashSetOf()) { iccid ->
            resolved[iccid]?.bitmap != null || !cloudIconLookupPending || cloudIcons[iccid] != null
        }
        ProfileArtworkLoadState(bitmaps = bitmaps, ready = readyIccids.size == inputs.size, readyIccids = readyIccids)
    }
}

private class RetainedProfileArtwork {
    var bitmaps: Map<String, Bitmap> = emptyMap()
    var artwork: Map<ProfileArtworkInput, CachedProfileArtwork> = emptyMap()
}

internal data class ProfileArtworkLoadState(
    val bitmaps: Map<String, Bitmap>,
    val ready: Boolean,
    val readyIccids: Set<String> = emptySet(),
) {
    fun artwork(iccid: String) = ProfileArtworkState(bitmaps[iccid], iccid in readyIccids)

    companion object {
        val ReadyWithoutArtwork = ProfileArtworkLoadState(bitmaps = emptyMap(), ready = true)
    }
}

@Composable
internal fun ResolvedProfileArtwork(
    profile: ProfileInfo,
    artwork: ProfileArtworkState,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    cornerRadius: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    key(profile.iccid) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            val bitmap = artwork.bitmap
            if (bitmap != null) {
                // A cache/source replacement must stay opaque. Crossfading two copies of the
                // same image exposes the card background and looks like a reload flash.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    color = MiuixTheme.colorScheme.secondaryContainer,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.profile_artwork_description,
                            profile.providerName.ifBlank { stringResource(R.string.profile_default_name) },
                        ),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                }
            } else if (artwork.ready) {
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

private const val MaxEmbeddedIconBase64Characters = 1_500_000
private const val MaxRenderedProfileArtworkDimension = 512
private const val ProfileThumbnailDimension = 128

private const val MaxCachedArtworkBytes = 24 * 1024 * 1024

private data class ProfileArtworkInput(
    val dimension: Int,
    val customIconUri: String?,
    val embeddedIcon: String?,
    val cloudIcon: ProfileArtworkBytes?,
)

/** Hash collisions must not make different profiles share the wrong operator image. */
private class ProfileArtworkBytes(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is ProfileArtworkBytes &&
        hash == other.hash && bytes.contentEquals(other.bytes)
}

private fun ProfileInfo.artworkInput(
    cloudIcon: ByteArray?,
    dimension: Int = MaxRenderedProfileArtworkDimension,
) = ProfileArtworkInput(
    dimension = dimension,
    customIconUri = customIconUri,
    embeddedIcon = iconBase64,
    cloudIcon = cloudIcon?.let(::ProfileArtworkBytes),
)

/** A decoded result, where a null [bitmap] records that the profile has no usable artwork. */
private class CachedProfileArtwork(
    val bitmap: Bitmap?,
    val isCustom: Boolean = false,
    val isSnapshot: Boolean = false,
)

private object ProfileArtworkCache {
    private val cached = object : LruCache<ProfileArtworkInput, CachedProfileArtwork>(MaxCachedArtworkBytes) {
        override fun sizeOf(key: ProfileArtworkInput, value: CachedProfileArtwork): Int =
            value.bitmap?.allocationByteCount?.coerceAtLeast(1) ?: 1
    }

    fun get(input: ProfileArtworkInput): CachedProfileArtwork? {
        if (input.customIconUri == null && input.embeddedIcon == null && input.cloudIcon == null) return null
        return input.customIconUri?.let { cached.get(input.copy(embeddedIcon = null, cloudIcon = null)) }
            ?.takeIf { it.isCustom } ?: cached.get(input)
    }

    fun clearMemoryForTesting() = cached.evictAll()

    fun put(input: ProfileArtworkInput, artwork: CachedProfileArtwork) {
        // Snapshots belong to a reader and ICCID, not to a shared unresolved cloud source.
        if (artwork.isSnapshot ||
            (input.customIconUri == null && input.embeddedIcon == null && input.cloudIcon == null)
        ) return
        // A valid custom icon wins over both other sources. Cloud refreshes can reuse it.
        val key = if (artwork.isCustom) input.copy(embeddedIcon = null, cloudIcon = null) else input
        cached.put(key, artwork)
    }
}

internal fun clearProfileArtworkMemoryForTesting() = ProfileArtworkCache.clearMemoryForTesting()

private fun loadProfileArtwork(
    context: android.content.Context,
    profile: ProfileInfo?,
    cloudIcon: ByteArray?,
    dimension: Int = MaxRenderedProfileArtworkDimension,
): CachedProfileArtwork {
    val custom = profile?.customIconUri
        ?.let { uri -> runCatching { uri.toUri() }.getOrNull() }
        ?.let { uri ->
            runCatching {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri),
                ) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longestEdge = maxOf(info.size.width, info.size.height)
                    if (longestEdge > dimension) {
                        val scale = dimension.toFloat() / longestEdge
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        }
    if (custom != null) return CachedProfileArtwork(custom, isCustom = true)

    val embedded = profile?.iconBase64
        ?.takeIf { encoded -> encoded.length <= MaxEmbeddedIconBase64Characters }
        ?.let { encoded -> runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() }
    val embeddedBitmap = embedded?.let { decodeProfileBitmap(it, dimension) }
    return CachedProfileArtwork(
        when {
            embedded != null && embedded.size >= 2_048 && embeddedBitmap != null -> embeddedBitmap
            cloudIcon != null -> decodeProfileBitmap(cloudIcon, dimension) ?: embeddedBitmap
            else -> embeddedBitmap
        },
    )
}

private fun decodeProfileBitmap(bytes: ByteArray, dimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > dimension ||
            bounds.outHeight / sampleSize > dimension
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
