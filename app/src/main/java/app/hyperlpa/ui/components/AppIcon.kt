package app.hyperlpa.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private val appIconCache = LruCache<String, ImageBitmap>(64)

@Composable
fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(appIconCache.get("$packageName@$pixels"), packageName, pixels) {
        if (value == null) value = withContext(Dispatchers.IO) { loadAppIcon(context, packageName, pixels) }
    }
    val shape = RoundedCornerShape(size * 0.25f)
    val frame = modifier
        .size(size)
        .clip(shape)
        .border(0.5.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
    val icon = bitmap
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = frame, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
    } else {
        Box(frame.background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(
                text = label.trim().take(1).uppercase(),
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun loadAppIcon(context: Context, packageName: String, size: Int): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    drawable.toFilledIcon(size).asImageBitmap().also { appIconCache.put("$packageName@$size", it) }
}.getOrNull()

/** Adaptive layers overhang the tile so the 72-in-108 safe zone fills it without a launcher mask. */
private fun Drawable.toFilledIcon(size: Int): Bitmap {
    val source = unwrap().mutate()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawFilter = PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val adaptive = source as? AdaptiveIconDrawable
    if (adaptive?.background != null) {
        val inset = (size * AdaptiveIconDrawable.getExtraInsetFraction()).roundToInt().coerceAtLeast(0)
        listOfNotNull(adaptive.background, adaptive.foreground).forEach { layer ->
            val copy = layer.mutate()
            if (copy is BitmapDrawable) copy.isFilterBitmap = true
            copy.setBounds(-inset, -inset, size + inset, size + inset)
            copy.draw(canvas)
        }
    } else {
        if (source is BitmapDrawable) source.isFilterBitmap = true
        source.setBounds(0, 0, size, size)
        source.draw(canvas)
    }
    return bitmap
}

private fun Drawable.unwrap(): Drawable {
    var current = this
    while (current is DrawableWrapper) {
        val inner = current.drawable ?: break
        if (inner === current) break
        current = inner
    }
    val hardware = (current as? BitmapDrawable)?.bitmap?.takeIf { it.config == Bitmap.Config.HARDWARE }
    return if (hardware != null) BitmapDrawable(null, hardware.copy(Bitmap.Config.ARGB_8888, false)) else current
}
