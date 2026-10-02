package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * A session set up but not started.
 *
 * **A draft is the body of `POST /api/sessions`**, plus an id, two timestamps and a
 * title — so pressing Start runs the create call that was written down, and nothing
 * about the session is decided at start time that was not decided when it was saved.
 *
 * The feature exists for this client in particular. `docs/api.md:121`: *"setting work
 * up at the desk and releasing it from a phone when quota frees up is the case the
 * feature exists for."* The draft routes are open to a remote caller for that reason;
 * the only thing a phone may not do is *widen* a permission mode.
 */
@Serializable
data class Draft(
    val id: String,
    /**
     * **Stored expanded.** Send `~/thing` and it comes back as the real path, because
     * that is what will be handed to `spawn()` — so this need not match what was typed.
     */
    val cwd: String = "",
    /** Derived on the bridge, so three clients cannot disagree about which project a directory is. */
    val projectName: String? = null,
    val prompt: String = "",
    /**
     * **`null` means *derive it*** — take the first line of [prompt]. It is not an empty
     * heading and it is not the string `"null"`; rendering it raw shows nothing where
     * the name should be. Use [displayTitle].
     */
    val title: String? = null,
    /** `null` is `inherit` — the session picks for itself. Not `""`. */
    val model: String? = null,
    /** `null` is `inherit`: no `--effort`, so Claude's own `effortLevel` decides. */
    val effort: String? = null,
    val permissionMode: String = "auto",
    /** The flag the started session will get, not a property of the draft. */
    val test: Boolean = false,
    /** Epoch millis, unlike a task's ISO-8601 `ts`. */
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Untitled draft"
}

@Serializable
data class DraftCounts(val total: Int = 0)

@Serializable
data class DraftsResponse(
    val at: Long? = null,
    /** **Newest `updatedAt` first**, which is the order to draw them in. Do not re-sort. */
    val drafts: List<Draft> = emptyList(),
    val counts: DraftCounts? = null,
)

/** `POST /api/drafts` and `PATCH /api/drafts/:id` both answer `{draft}`. */
@Serializable
data class DraftResponse(val draft: Draft)

/**
 * The create body, and the patch body.
 *
 * Carries **no `title`**, and that is the interesting part. `PATCH` is a genuine
 * partial — a field left out is left alone — so omitting it *preserves* whatever was
 * set at the desk. This screen has no title field, so it must not be able to clear
 * one by saving.
 *
 * [model] and [effort] *are* sent, now that the form has a picker for each, and
 * `inherit` goes as `""`: the bridge stores a whitespace-only value as `null`, which
 * is how a phone clears a model chosen at the desk. Sending Kotlin `null` would be
 * dropped from the body by `explicitNulls = false` and change nothing.
 *
 * [permissionMode] is always sent, never conditionally: an absent one does not mean
 * "decide later", it means the draft is saved as `auto`.
 */
@Serializable
data class DraftBody(
    val cwd: String,
    val prompt: String,
    val permissionMode: String,
    val test: Boolean,
    /** No default, on purpose: `AppJson` does not encode defaults, so a default `""` would never be sent. */
    val model: String,
    val effort: String,
)
