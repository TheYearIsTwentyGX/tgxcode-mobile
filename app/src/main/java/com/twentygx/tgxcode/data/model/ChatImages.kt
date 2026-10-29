package com.twentygx.tgxcode.data.model

import com.twentygx.tgxcode.core.toolDisplayText
import kotlinx.serialization.Serializable

/**
 * `GET /api/sessions/:id/images`: every image in a conversation, once each.
 *
 * Never the bytes. Each entry is named by the sha256 of its decoded bytes, and those
 * are fetched one key at a time from `…/images/:key` — immutable, so cached for good.
 * [images] is newest first by first occurrence, which is the order the grid draws.
 */
@Serializable
data class SessionImages(
    val sessionId: String? = null,
    val images: List<ChatImage> = emptyList(),
)

@Serializable
data class ChatImage(
    /** sha256 of the decoded bytes, 64 lower-case hex characters. */
    val key: String,
    /** `user` or `tool`, of the first occurrence. */
    val source: String? = null,
    val toolUseId: String? = null,
    val n: Int? = null,
    val uuid: String? = null,
    /** ISO timestamp of the first occurrence. */
    val at: String? = null,
    val mediaType: String = "image/png",
    /** Decoded length. */
    val bytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    /** "Your message", a Read's file name, or the tool's display name. */
    val label: String? = null,
    val url: String? = null,
    /** Every time these bytes appeared — keeps counting past the 200 [occurrences] listed. */
    val count: Int = 1,
    /** Oldest first, so the first is the one the top-level fields repeat. */
    val occurrences: List<ImageOccurrence> = emptyList(),
)

@Serializable
data class ImageOccurrence(
    val uuid: String? = null,
    val at: String? = null,
    val source: String? = null,
    val toolUseId: String? = null,
    val n: Int? = null,
    val label: String? = null,
    /** The Agent call's tool_use id, when the image was in a subagent. */
    val agent: String? = null,
    /** Where to scroll for it. Null when a subagent cannot be traced back to the session. */
    val anchor: ImageAnchor? = null,
) {
    /** The viewer's title for this one: what produced it, and where. */
    val title: String
        get() {
            val where = when {
                agent != null -> "in a subagent"
                source == "user" -> null
                else -> "from a tool"
            }
            return listOfNotNull(label?.takeIf { it.isNotBlank() }?.let(::toolDisplayText), where).joinToString(" · ").ifBlank { "Image" }
        }
}

/**
 * The row an occurrence lives on: a `user` event's id or a `tool` event's id — for a
 * subagent's image, the Agent call that spawned it. [offset] is a byte offset in the
 * session's own transcript, *not* a window boundary: `/range` takes only turn
 * offsets, so a jump loads from the turn at or before it.
 */
@Serializable
data class ImageAnchor(
    val kind: String? = null,
    val id: String,
    val offset: Long? = null,
)

/**
 * The turn offset to load from so that [anchor] is in hand: the last turn starting at
 * or before it, or 0 when it is above every turn. Null when nothing needs loading —
 * it is already inside the window, or there is no turn index to range over.
 */
fun earlierStartFor(anchor: ImageAnchor, turns: List<TurnMark>, windowStart: Long): Long? {
    val at = anchor.offset ?: return null
    if (turns.isEmpty() || at >= windowStart) return null
    return turns.filter { it.isTurn && it.offset <= at }.maxOfOrNull { it.offset } ?: 0L
}

private val KEY = Regex("^[0-9a-f]{64}$")

/** Whether [key] can be a file name and a path segment as it is. */
fun isImageKey(key: String): Boolean = KEY.matches(key)

/** "340 KB", "1.2 MB": the desktop's `kb()`. */
fun imageSizeText(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    } else {
        "${maxOf(1L, Math.round(bytes / 1024.0))} KB"
    }

/** What a tile says about itself: what it came from, its size, and how often it appeared. */
fun ChatImage.describe(withLabel: Boolean = true): String = listOfNotNull(
    label?.takeIf { withLabel && it.isNotBlank() }?.let(::toolDisplayText),
    if (width != null && height != null) "$width×$height" else null,
    imageSizeText(bytes),
    if (count > 1) "appears $count times" else null,
).joinToString(" · ")
