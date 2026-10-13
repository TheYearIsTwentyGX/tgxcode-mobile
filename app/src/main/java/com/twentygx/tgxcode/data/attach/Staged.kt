package com.twentygx.tgxcode.data.attach

import android.net.Uri
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.AttachmentRef
import com.twentygx.tgxcode.data.net.BridgeApi

/** Where an upload lands: a session's checkout, or a folder a session will start in. */
sealed interface UploadTarget {
    data class Session(val id: String) : UploadTarget
    data class Folder(val cwd: String) : UploadTarget
}

sealed interface StageState {
    /** Picked, not sent yet — the new-session form holds files until Start. */
    data object Held : StageState
    data object Uploading : StageState
    data class Ready(val ref: AttachmentRef) : StageState
    data class Failed(val reason: String) : StageState
}

/** One chip in a strip. [name] becomes the bridge's once it answers, which may be `-2`'d. */
data class StagedFile(
    val id: Long,
    /** The picked `content://` URI, as a string so the rules above stay plain JVM. */
    val uri: String,
    val name: String,
    val size: Long?,
    val mediaType: String?,
    val state: StageState,
) {
    val isImage: Boolean get() = mediaType?.startsWith("image/") == true
    fun picked(): Picked = Picked(Uri.parse(uri), name, size, mediaType)
}

fun List<StagedFile>.readyRefs(): List<AttachmentRef> =
    mapNotNull { (it.state as? StageState.Ready)?.ref }

/**
 * Whether a composer holding [text] and [files] can send.
 *
 * Anything uploading or failed holds the send back: the alternative is a message that
 * quietly goes without a file its author thinks is on it. A failed chip has a Retry and
 * an ×, so the way out is always one tap.
 */
fun canSendWith(text: String, files: List<StagedFile>, sending: Boolean): Boolean {
    if (sending) return false
    if (files.any { it.state is StageState.Uploading || it.state is StageState.Failed }) return false
    return text.isNotBlank() || files.any { it.state is StageState.Ready }
}

/** What the composer says when [canSendWith] is false for a file's sake, or null. */
fun stagingNote(files: List<StagedFile>): String? = when {
    files.any { it.state is StageState.Uploading } -> "Uploading…"
    files.any { it.state is StageState.Failed } -> "A file did not upload. Retry it or remove it."
    else -> null
}

/**
 * The read-then-upload step, shared by the composer and the new-session form.
 *
 * Returns the state the chip should move to; never throws for an ordinary failure.
 */
class Uploader(private val source: AttachmentSource, private val api: BridgeApi) {

    suspend fun upload(target: UploadTarget, picked: Picked): StageState {
        val payload = try {
            source.read(picked)
        } catch (problem: AttachmentProblem) {
            return StageState.Failed(problem.message.orEmpty())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return StageState.Failed("Could not read ${picked.name}")
        }
        val name = safeName(payload.name)
        val result = when (target) {
            is UploadTarget.Session -> api.uploadAttachment(target.id, name, payload.bytes, payload.contentType)
            is UploadTarget.Folder -> api.uploadAttachmentTo(target.cwd, name, payload.bytes, payload.contentType)
        }
        return when (result) {
            is ApiResult.Ok -> StageState.Ready(result.value.ref())
            is ApiResult.Http -> StageState.Failed(uploadRefusal(result))
            is ApiResult.Offline -> StageState.Failed(result.message())
        }
    }
}

/** The refusals worth their own sentence. */
internal fun uploadRefusal(result: ApiResult.Http): String = when {
    result.status == 413 -> "Over the 10MB a phone can attach"
    result.status == 403 && result.error?.contains("only be attached") == true ->
        "This bridge does not take files from a phone yet — it needs updating"
    else -> result.message()
}
