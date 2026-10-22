package com.twentygx.tgxcode.data.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.Tgx
import com.twentygx.tgxcode.data.model.SessionImages
import com.twentygx.tgxcode.data.model.isImageKey
import com.twentygx.tgxcode.data.net.BridgeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Images sheet's pictures: fetched through [BridgeApi.imageBytes], so they carry
 * the token and the Access headers, and kept.
 *
 * No image library. Coil or Glide would want a URL they fetch themselves, and the
 * headers make that a custom fetcher anyway; what is left after that is a disk
 * directory, a bitmap LRU and [sampleSizeFor], which the transcript's own images
 * already use.
 *
 * **Kept on disk by key, without revalidation.** The key is the sha256 of the bytes,
 * so a file that exists is right by construction — the reason the bridge answers
 * these `immutable`. The directory is trimmed oldest-read first past [DISK_BYTES], and
 * lives under the cache dir, where Android may also clear it.
 */
class ChatImageCache(
    private val api: BridgeApi,
    private val dir: File,
) {
    private val decoded = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The list itself: never cached, since it is what changes. */
    suspend fun list(sessionId: String): ApiResult<SessionImages> = api.images(sessionId)

    /** Already decoded at this size — for a first frame with no placeholder flash. */
    fun peek(key: String, maxPx: Int): Bitmap? = decoded.get("$key:$maxPx")

    /**
     * The picture behind [key], no larger than about [maxPx] on its longest edge.
     * Null when it cannot be had: the bridge no longer has it, the network is down, or
     * the bytes do not decode.
     */
    suspend fun bitmap(sessionId: String, key: String, maxPx: Int): Bitmap? {
        if (!isImageKey(key)) return null
        peek(key, maxPx)?.let { return it }
        val file = file(sessionId, key) ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
            }
            BitmapFactory.decodeFile(file.path, options)?.also { decoded.put("$key:$maxPx", it) }
        }
    }

    private suspend fun file(sessionId: String, key: String): File? = withContext(Dispatchers.IO) {
        val file = File(dir, key)
        if (file.isFile) {
            // Read order is what the trim goes by.
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        when (val result = api.imageBytes(sessionId, key)) {
            is ApiResult.Ok -> runCatching {
                dir.mkdirs()
                // Through a temporary name, so a half-written file is never taken for one.
                val tmp = File(dir, "$key.${java.util.UUID.randomUUID()}.tmp")
                tmp.writeBytes(result.value)
                if (!tmp.renameTo(file)) tmp.delete()
                trim()
                file.takeIf { it.isFile }
            }.onFailure { Tgx.w("image cache write failed", it) }.getOrNull()
            else -> null
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> isImageKey(f.name) }?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val MEMORY_BYTES = 32 * 1024 * 1024
        const val DISK_BYTES = 96L * 1024 * 1024
    }
}
