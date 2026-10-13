package com.twentygx.tgxcode.data.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.twentygx.tgxcode.data.model.FileRef
import com.twentygx.tgxcode.data.model.ImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

/** The bytes of a `data:<type>;base64,<…>` URI, or null for anything else. */
internal fun dataUriBytes(dataUri: String): ByteArray? {
    val comma = dataUri.indexOf(',')
    if (!dataUri.startsWith("data:") || comma < 0) return null
    if (!dataUri.substring(0, comma).endsWith(";base64")) return null
    return runCatching { Base64.getMimeDecoder().decode(dataUri.substring(comma + 1)) }.getOrNull()
}

/** The largest power-of-two step down that still leaves the longest edge at least [target]. */
internal fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

/**
 * The file cards worth drawing beside [images]. The bridge lists an attached photo
 * twice — once as the image the model was sent, once as the file on disk — so as many
 * image-named files as there are images are left out rather than shown a second time.
 */
internal fun filesBesideImages(files: List<FileRef>, images: List<ImageRef>): List<FileRef> {
    var skip = images.size
    return files.filter { file ->
        val ext = file.name.orEmpty().substringAfterLast('.', "").lowercase()
        if (skip > 0 && ext in IMAGE_EXTENSIONS) {
            skip--
            false
        } else {
            true
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

/**
 * Decoded transcript images, by the URI's identity and the size asked for. A
 * conversation scrolls the same few back into view over and over, and decoding a
 * megabyte of base64 each time it does is the jank this saves.
 */
private val decoded by lazy {
    object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
}

/** A data URI as a bitmap no larger than about [maxPx] on its longest edge. */
suspend fun decodeDataUri(dataUri: String, maxPx: Int): Bitmap? = withContext(Dispatchers.Default) {
    val key = "${dataUri.length}:${dataUri.hashCode()}:$maxPx"
    decoded.get(key)?.let { return@withContext it }
    val bytes = dataUriBytes(dataUri) ?: return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return@withContext null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx) }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { decoded.put(key, it) }
}
