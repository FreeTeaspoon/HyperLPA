package app.hyperlpa.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

internal object RemoteArtwork {
    fun read(context: Context, uri: String, size: Int = 384): ByteArray {
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "The image is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The image could not be opened")
        return normalize(bytes, size)
    }

    fun normalize(bytes: ByteArray, size: Int = 384): ByteArray {
        require(bytes.size <= 1024 * 1024 * 8)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) { "Invalid image" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > size * 2 || bounds.outHeight / inSampleSize > size * 2) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid image")
        try {
            val scale = minOf(1f, size.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
            try {
                val output = ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, output))
                return output.toByteArray().also { require(it.size <= 256_000) }
            } finally { if (bitmap !== decoded) bitmap.recycle() }
        } finally { decoded.recycle() }
    }
}
