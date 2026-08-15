package app.hyperlpa.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import android.util.Base64
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
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
    val artworkKey = listOf(
        profile?.customIconUri.orEmpty(),
        profile?.iconBase64.orEmpty(),
        cloudIcon?.contentHashCode()?.toString().orEmpty(),
    )
    return key(artworkKey) {
        val bitmap by produceState<Bitmap?>(
            initialValue = null,
            profile?.customIconUri,
            profile?.iconBase64,
            cloudIcon?.contentHashCode(),
        ) {
            value = withContext(Dispatchers.IO) {
                loadProfileArtworkBitmap(context, profile, cloudIcon)
            }
        }
        bitmap
    }
}

@Composable
internal fun rememberProfileArtworkBitmaps(
    profiles: List<ProfileInfo>,
    cloudIcons: Map<String, ByteArray>,
    sourceKey: String?,
    enabled: Boolean,
): ProfileArtworkLoadState {
    if (!enabled || profiles.isEmpty()) return ProfileArtworkLoadState.ReadyWithoutArtwork
    val context = LocalContext.current
    val artworkInputs = remember(profiles, cloudIcons) {
        profiles.map { profile ->
            ProfileArtworkInput(
                iccid = profile.iccid,
                customIconUri = profile.customIconUri,
                embeddedIcon = profile.iconBase64,
                cloudIconHash = cloudIcons[profile.iccid]?.contentHashCode(),
            )
        }
    }
    val batchKey = ProfileArtworkBatchKey(sourceKey = sourceKey, inputs = artworkInputs)
    val cachedState = ProfileArtworkBatchCache.get(batchKey)
    val previousState = remember { mutableStateOf<ProfileArtworkLoadState?>(null) }
    return key(batchKey) {
        val carriedBitmaps = previousState.value?.bitmaps
            ?.filterKeys { iccid -> profiles.any { profile -> profile.iccid == iccid } }
            .orEmpty()
        val initialState = cachedState
            ?: ProfileArtworkLoadState(bitmaps = carriedBitmaps, ready = false)
        val loadState by produceState(
            initialValue = initialState,
            batchKey,
        ) {
            if (cachedState != null) {
                previousState.value = cachedState
                return@produceState
            }
            val bitmaps = withContext(Dispatchers.IO) {
                buildMap<String, Bitmap> {
                    profiles.forEach { profile ->
                        loadProfileArtworkBitmap(
                            context = context,
                            profile = profile,
                            cloudIcon = cloudIcons[profile.iccid],
                        )?.let { bitmap -> put(profile.iccid, bitmap) }
                    }
                }
            }
            val readyState = ProfileArtworkLoadState(bitmaps = bitmaps, ready = true)
            ProfileArtworkBatchCache.put(batchKey, readyState)
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

private const val MaxEmbeddedIconBase64Characters = 1_500_000
private const val ProfileArtworkCrossfadeMillis = 140

private data class ProfileArtworkInput(
    val iccid: String,
    val customIconUri: String?,
    val embeddedIcon: String?,
    val cloudIconHash: Int?,
)

private data class ProfileArtworkBatchKey(
    val sourceKey: String?,
    val inputs: List<ProfileArtworkInput>,
)

private object ProfileArtworkBatchCache {
    private const val MaxCachedBatches = 3
    private val cached = LinkedHashMap<ProfileArtworkBatchKey, ProfileArtworkLoadState>(
        4,
        0.75f,
        true,
    )

    @Synchronized
    fun get(key: ProfileArtworkBatchKey): ProfileArtworkLoadState? =
        cached[key]

    @Synchronized
    fun put(key: ProfileArtworkBatchKey, state: ProfileArtworkLoadState) {
        cached[key] = state
        while (cached.size > MaxCachedBatches) {
            cached.entries.iterator().apply { next(); remove() }
        }
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
                    if (longestEdge > 192) {
                        val scale = 192f / longestEdge
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
    while (bounds.outWidth / sampleSize > 384 || bounds.outHeight / sampleSize > 384) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}
