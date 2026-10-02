package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * The slice of the desktop's settings this app reads: `GET /api/prefs`, and the `prefs`
 * event, which carries the user-level settings in the same shape whenever they change.
 *
 * Read-only from here. `PUT /api/prefs` is refused to a remote caller, so the colours are
 * given at the desk and worn on the phone.
 */
@Serializable
data class Prefs(val projects: ProjectPrefs? = null)

@Serializable
data class ProjectPrefs(
    /** Keyed by project **path**, not the name the rail draws — two checkouts can share a name. */
    val colors: Map<String, String> = emptyMap(),
)
