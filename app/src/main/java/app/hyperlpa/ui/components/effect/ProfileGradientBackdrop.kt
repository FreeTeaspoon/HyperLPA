package app.hyperlpa.ui.components.effect

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.hyperlpa.ui.theme.LocalDarkTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class ProfileArtworkPalette(
    val primary: Color,
    val secondary: Color,
)

/** A soft, artwork-derived wash that fades completely into the page surface. */
@Composable
internal fun ProfileGradientBackdrop(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val surface = MiuixTheme.colorScheme.surface
    val isDark = LocalDarkTheme.current
    val palette = remember(bitmap, surface) {
        bitmap?.extractArtworkPalette() ?: ProfileArtworkPalette(surface, surface)
    }
    val primary = palette.primary
    val secondary = palette.secondary
    val fadeHeight = with(LocalDensity.current) { 480.dp.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(surface)
        val radius = max(size.width * 0.8f, fadeHeight * 0.72f)
        val primaryAlpha = if (isDark) 0.34f else 0.23f
        val secondaryAlpha = if (isDark) 0.24f else 0.17f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = primaryAlpha), Color.Transparent),
                center = Offset(size.width * 0.42f, fadeHeight * 0.12f),
                radius = radius,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = secondaryAlpha), Color.Transparent),
                center = Offset(size.width * 0.82f, fadeHeight * 0.28f),
                radius = radius * 0.75f,
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.38f to surface.copy(alpha = 0.08f),
                    0.72f to surface.copy(alpha = 0.76f),
                    1f to surface,
                ),
                startY = 0f,
                endY = fadeHeight,
            ),
        )
    }
}

private fun Bitmap.extractArtworkPalette(): ProfileArtworkPalette? {
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    if (runCatching { getPixels(pixels, 0, width, 0, 0, width, height) }.isFailure) return null

    val red = FloatArray(BucketCount)
    val green = FloatArray(BucketCount)
    val blue = FloatArray(BucketCount)
    val weight = FloatArray(BucketCount)
    val sampleStep = sqrt((pixels.size / MaxSamples.toFloat()).coerceAtLeast(1f)).toInt().coerceAtLeast(1)

    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        val alpha = pixel ushr 24 and 0xff
        if (alpha >= 64) {
            val r = pixel ushr 16 and 0xff
            val g = pixel ushr 8 and 0xff
            val b = pixel and 0xff
            val high = max(r, max(g, b)) / 255f
            val low = min(r, min(g, b)) / 255f
            val saturation = if (high == 0f) 0f else (high - low) / high
            val lightness = (high + low) / 2f
            val toneWeight = when {
                lightness > 0.94f -> 0.08f
                lightness < 0.035f -> 0.08f
                lightness > 0.88f || lightness < 0.07f -> 0.35f
                else -> 1f
            }
            val colorWeight = (0.16f + saturation * 1.35f) * toneWeight * (alpha / 255f)
            val bucket = (r shr BucketShift shl 8) or (g shr BucketShift shl 4) or (b shr BucketShift)
            red[bucket] += r * colorWeight
            green[bucket] += g * colorWeight
            blue[bucket] += b * colorWeight
            weight[bucket] += colorWeight
        }
        index += sampleStep
    }

    val primaryBucket = weight.indices.maxByOrNull(weight::get)?.takeIf { weight[it] > 0f } ?: return null
    val primary = bucketColor(primaryBucket, red, green, blue, weight)
    val secondaryBucket = weight.indices
        .asSequence()
        .filter { weight[it] > 0f && colorDistanceSquared(primary, bucketColor(it, red, green, blue, weight)) > 0.045f }
        .maxByOrNull(weight::get)
    val secondary = secondaryBucket?.let { bucketColor(it, red, green, blue, weight) }
        ?: lerp(primary, Color.White, 0.18f)
    return ProfileArtworkPalette(primary, secondary)
}

private fun bucketColor(
    bucket: Int,
    red: FloatArray,
    green: FloatArray,
    blue: FloatArray,
    weight: FloatArray,
): Color {
    val divisor = weight[bucket].coerceAtLeast(0.0001f) * 255f
    return Color(
        red = red[bucket] / divisor,
        green = green[bucket] / divisor,
        blue = blue[bucket] / divisor,
    )
}

private fun colorDistanceSquared(first: Color, second: Color): Float {
    val red = first.red - second.red
    val green = first.green - second.green
    val blue = first.blue - second.blue
    return red * red + green * green + blue * blue
}

private const val BucketShift = 4
private const val BucketCount = 16 * 16 * 16
private const val MaxSamples = 6_000
