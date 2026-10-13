package com.twentygx.tgxcode.data.attach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Per message, as on the bridge (`MAX_PER_MESSAGE`). */
const val MAX_ATTACHMENTS = 5

/**
 * The bridge's cap for a caller off its machine, which a phone always is. Checked here
 * so a file that cannot go never starts going: the bridge answers an oversized upload
 * by hanging up, and a reset mid-write can arrive as a bare connection error instead of
 * the 413 that says why.
 */
const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

/**
 * The longest edge a photo is sent at. Past this the model sees no more than it would
 * at this size, and a phone camera's 4000px original is most of the 10MB on its own.
 */
const val MAX_IMAGE_EDGE = 2048

/** What the picker handed back, before a byte of it has been read. */
data class Picked(
    val uri: Uri,
    val name: String,
    /** Null when the provider would not say. */
    val size: Long?,
    val mediaType: String?,
) {
    val isImage: Boolean get() = mediaType?.startsWith("image/") == true
}

/** What goes up the wire. [name] may differ from the picked one — a HEIC sent as `.jpg`. */
class Payload(val name: String, val bytes: ByteArray, val contentType: String)

/** A file that cannot be sent, with the sentence that says why. */
class AttachmentProblem(message: String) : Exception(message)

/**
 * Reads picked files off the phone.
 *
 * Everything comes through a `content://` URI from the system photo picker or the
 * document picker, so there is no storage permission anywhere: the grant is the pick.
 */
class AttachmentSource(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    fun describe(uri: Uri): Picked {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
        }
        return Picked(
            uri = uri,
            name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment",
            size = size,
            mediaType = resolver.getType(uri),
        )
    }

    /**
     * The bytes to upload.
     *
     * A photo the bridge can already hand the model as-is (PNG, JPEG, WebP, at most
     * [MAX_IMAGE_EDGE] on a side) goes untouched, so a screenshot keeps its crisp text.
     * Anything else that decodes as an image — a 4000px camera JPEG, a HEIC the bridge
     * would only ever treat as an opaque file — is scaled and re-encoded as JPEG, which
     * is what turns it into something the model can actually see. GIFs pass through so
     * an animation survives. Other files go as they are.
     */
    suspend fun read(picked: Picked): Payload = withContext(Dispatchers.IO) {
        if (picked.isImage && picked.mediaType != "image/gif") {
            val bounds = bounds(picked.uri)
            if (!needsReencode(picked.mediaType, picked.size, bounds?.first, bounds?.second)) {
                return@withContext Payload(picked.name, readAll(picked), picked.mediaType ?: "application/octet-stream")
            }
            reencode(picked)?.let { return@withContext it }
            // Not decodable after all: send what was picked and let the bridge decide.
        }
        Payload(picked.name, readAll(picked), picked.mediaType ?: "application/octet-stream")
    }

    private fun readAll(picked: Picked): ByteArray {
        val input = resolver.openInputStream(picked.uri)
            ?: throw AttachmentProblem("Could not open ${picked.name}")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                // The provider's size can be missing or wrong; the cap is about what is read.
                if (total > MAX_ATTACHMENT_BYTES) throw AttachmentProblem(tooBig(picked.name))
                out.write(buf, 0, n)
            }
            if (total == 0L) throw AttachmentProblem("${picked.name} is empty")
            out.toByteArray()
        }
    }

    private fun bounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    private fun reencode(picked: Picked): Payload? {
        val bitmap = runCatching {
            // ImageDecoder rather than BitmapFactory: it reads HEIC and applies the EXIF
            // rotation, so a portrait photo does not arrive on its side.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, picked.uri)) { decoder, info, _ ->
                val (w, h) = scaledSize(info.size.width, info.size.height, MAX_IMAGE_EDGE)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > MAX_ATTACHMENT_BYTES) throw AttachmentProblem(tooBig(picked.name))
        return Payload(jpegName(picked.name), bytes, "image/jpeg")
    }

    /** A small preview for a chip, or null when it is not an image or will not decode. */
    suspend fun thumbnail(uri: Uri, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (w, h) = scaledSize(info.size.width, info.size.height, px)
                decoder.setTargetSize(w, h)
            }
        }.getOrNull()
    }
}

// ------------------------------------------------------------------ pure rules

/**
 * Whether a picked image should be scaled and re-encoded rather than sent as picked.
 * Unknown dimensions on a type the bridge takes are given the benefit of the doubt
 * only while the file is small.
 */
internal fun needsReencode(mediaType: String?, size: Long?, width: Int?, height: Int?): Boolean {
    if (mediaType !in PASSTHROUGH_TYPES) return true
    if (width == null || height == null) return (size ?: Long.MAX_VALUE) > PASSTHROUGH_UNKNOWN_BYTES
    return maxOf(width, height) > MAX_IMAGE_EDGE || (size ?: 0) > MAX_ATTACHMENT_BYTES
}

private val PASSTHROUGH_TYPES = setOf("image/png", "image/jpeg", "image/webp")
private const val PASSTHROUGH_UNKNOWN_BYTES = 2L * 1024 * 1024

/** Fit within [max] on the longest edge, keeping the aspect; never scales up. */
internal fun scaledSize(width: Int, height: Int, max: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= max || longest <= 0) return width to height
    val scale = max.toDouble() / longest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/** `IMG_0042.HEIC` → `IMG_0042.jpg`. */
internal fun jpegName(name: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return "$stem.jpg"
}

/**
 * A name the bridge will take. It refuses rather than cleans — no separator, no
 * control character, nothing that is only dots, at most 200 bytes
 * (`attachmentNameProblem`, `bridge/attachments.js`) — so the cleaning happens here,
 * where a provider's display name can be anything at all.
 */
internal fun safeName(name: String): String {
    val cleaned = buildString {
        for (ch in name) append(if (ch == '/' || ch == '\\' || ch.isISOControl()) '_' else ch)
    }.trim()
    if (cleaned.isEmpty() || cleaned.all { it == '.' }) return "attachment"
    if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return cleaned

    // Too long: keep the extension, since it is what the bridge and the model read
    // the file's kind from, and trim the stem by whole code points.
    val dot = cleaned.lastIndexOf('.')
    val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
    var stem = cleaned.removeSuffix(ext)
    while (stem.isNotEmpty() && (stem + ext).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
        stem = stem.dropLast(if (stem.length >= 2 && stem[stem.length - 1].isLowSurrogate()) 2 else 1)
    }
    return (stem + ext).ifEmpty { "attachment" }
}

private const val MAX_NAME_BYTES = 200

internal fun tooBig(name: String): String =
    "$name is over the 10MB a phone can attach. Attach it from the desktop instead."

/** `412 KB`, the way the bridge writes sizes in the transcript. */
fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${(n + 512) / 1024} KB"
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}
