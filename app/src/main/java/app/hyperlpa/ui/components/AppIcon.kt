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
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private const val MetadataCacheMillis = 5 * 60_000L
private data class CachedIcon(val bitmap: ImageBitmap?, val loadedAt: Long = android.os.SystemClock.elapsedRealtime())
private data class CachedLabel(val label: String, val loadedAt: Long = android.os.SystemClock.elapsedRealtime())
private val appIconCache = LruCache<String, CachedIcon>(128)
private val metadataScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
private val iconLoads = mutableMapOf<String, kotlinx.coroutines.Deferred<ImageBitmap?>>()
private val appLabels = LruCache<String, CachedLabel>(256)
private val labelLoads = mutableMapOf<String, kotlinx.coroutines.Deferred<String>>()

private fun labelKey(packageName: String) = "$packageName@${java.util.Locale.getDefault().toLanguageTag()}"

fun cachedAppLabel(packageName: String): String = appLabels.get(labelKey(packageName))?.label ?: packageName

suspend fun appLabel(context: Context, packageName: String): String {
    val key = labelKey(packageName)
    appLabels.get(key)?.takeIf { android.os.SystemClock.elapsedRealtime() - it.loadedAt < MetadataCacheMillis }?.let { return it.label }
    val load = synchronized(labelLoads) {
        labelLoads.getOrPut(key) { metadataScope.async {
            runCatching { context.applicationContext.packageManager.let { pm ->
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } }.getOrDefault(packageName).also { appLabels.put(key, CachedLabel(it)) }
        } }
    }
    return try { load.await() } finally { synchronized(labelLoads) { if (load.isCompleted && labelLoads[key] === load) labelLoads.remove(key) } }
}

private suspend fun appIcon(context: Context, packageName: String, pixels: Int): ImageBitmap? {
    val key = "$packageName@$pixels"
    appIconCache.get(key)?.takeIf { android.os.SystemClock.elapsedRealtime() - it.loadedAt < MetadataCacheMillis }?.let { return it.bitmap }
    val load = synchronized(iconLoads) {
        iconLoads.getOrPut(key) { metadataScope.async {
            loadAppIcon(context.applicationContext, packageName, pixels).also { appIconCache.put(key, CachedIcon(it)) }
        } }
    }
    return try { load.await() } finally { synchronized(iconLoads) { if (load.isCompleted && iconLoads[key] === load) iconLoads.remove(key) } }
}

@Composable
fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(appIconCache.get("$packageName@$pixels")?.bitmap, packageName, pixels) {
        value = appIcon(context, packageName, pixels)
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
    drawable.toFilledIcon(size).asImageBitmap()
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
