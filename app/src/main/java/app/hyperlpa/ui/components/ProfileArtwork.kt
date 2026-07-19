package app.hyperlpa.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hyperlpa.domain.model.ProfileInfo
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
    val context = LocalContext.current
    val artworkKey = listOf(
        profile.customIconUri.orEmpty(),
        profile.iconBase64.orEmpty(),
        cloudIcon?.contentHashCode()?.toString().orEmpty(),
    )
    key(artworkKey) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        profile.customIconUri,
        profile.iconBase64,
        cloudIcon?.contentHashCode(),
    ) {
        value = withContext(Dispatchers.IO) {
            val custom = profile.customIconUri
                ?.let { uri -> runCatching { Uri.parse(uri) }.getOrNull() }
                ?.let { uri ->
                    runCatching {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(context.contentResolver, uri),
                        ) { decoder, info, _ ->
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
            if (custom != null) return@withContext custom

            val embedded = profile.iconBase64
                ?.let { encoded -> runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() }
            val embeddedBitmap = embedded?.let(::decodeProfileBitmap)
            when {
                embedded != null && embedded.size >= 2_048 && embeddedBitmap != null -> embeddedBitmap
                cloudIcon != null -> decodeProfileBitmap(cloudIcon) ?: embeddedBitmap
                else -> embeddedBitmap
            }
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    if (bitmap != null) {
        Surface(
            modifier = modifier.size(size),
            shape = shape,
            color = MiuixTheme.colorScheme.secondaryContainer,
        ) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "${profile.providerName.ifBlank { "Profile" }} icon",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = shape,
            color = if (isEnabled) {
                MiuixTheme.colorScheme.primaryContainer
            } else {
                MiuixTheme.colorScheme.secondaryContainer
            },
            contentColor = if (isEnabled) {
                MiuixTheme.colorScheme.onPrimaryContainer
            } else {
                MiuixTheme.colorScheme.onSecondaryContainer
            },
        ) {
            Icon(
                imageVector = MiuixIcons.BankCards,
                contentDescription = null,
                modifier = Modifier
                    .padding(size * 0.24f)
                    .size(size * 0.52f),
            )
        }
    }
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
