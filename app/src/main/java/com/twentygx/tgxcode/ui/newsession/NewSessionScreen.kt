package com.twentygx.tgxcode.ui.newsession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.data.model.Draft
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.model.SessionSummary
import com.twentygx.tgxcode.ui.conversation.modeLabel
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Pill
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** A project you already have sessions in. */
data class RecentDir(val path: String, val label: String)

/**
 * The projects you could start a session in.
 *
 * Two decisions here, and both are about what *not* to offer.
 *
 * **Projects only, never worktrees.** A worktree is somewhere work is already
 * happening — an agent made it for a branch it is partway through. Starting a fresh
 * session inside one is almost never what you meant, and on a phone it is a mistake
 * you cannot easily undo. So this keys on `projectCwd`, which the bridge already
 * resolves to the project root regardless of whether the session itself is running
 * in `.claude/worktrees/<name>`. That also collapses the awkward case where a
 * session entered a worktree partway through and its `cwd` and `projectCwd` disagree.
 *
 * **From sessions that exist, not a filesystem walk.** `/api/fs` would work, but
 * browsing a tree on a phone to find a path you already have twenty sessions in is
 * the wrong shape for the input.
 *
 * Ordering follows the session list, which is newest-activity-first, so the project
 * you were last in is the one at the top.
 */
private val TEMP_ROOTS = listOf("/tmp", "/var/tmp", "/private/tmp", "/var/folders")

/**
 * Scratch directories are not projects either.
 *
 * `gitBranch` looks like it should separate them and does not — the bridge reports a
 * branch for `/tmp/probe-qaJigb` as readily as for a real checkout. So the signal has
 * to be the path itself:
 *
 * - **Temp roots.** Nothing under `/tmp` is somewhere you meant to start work.
 * - **Hidden segments.** `.claude/jobs/…`, `.claude/worktrees/…` and friends are
 *   machinery, not places.
 */
private fun isPlausibleProject(path: String): Boolean {
    if (path.isBlank() || !path.startsWith("/")) return false
    if (TEMP_ROOTS.any { path == it || path.startsWith("$it/") }) return false
    return path.split('/').none { it.startsWith(".") && it.length > 1 }
}

fun recentDirs(sessions: List<SessionSummary>, limit: Int = 40): List<RecentDir> {
    val seen = LinkedHashMap<String, RecentDir>()
    for (session in sessions) {
        val project = session.projectCwd?.takeIf { isPlausibleProject(it) } ?: continue
        if (seen.containsKey(project)) continue
        val label = session.projectName?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: project.substringAfterLast('/').ifBlank { project }
        seen[project] = RecentDir(project, label)
    }

    // A directory that contains two or more of the others is a container, not a
    // project — this is what drops `$HOME`, which otherwise shows up as a perfectly
    // ordinary entry and is the single worst place to start a session by accident.
    //
    // Two or more, not one: a project that happens to also have a session in a
    // subdirectory is still a project, and should not be removed by its own child.
    val paths = seen.keys.toList()
    val containers = paths.filter { candidate ->
        paths.count { it != candidate && it.startsWith("$candidate/") } >= 2
    }.toSet()

    return seen.entries
        .filterNot { it.key in containers }
        .take(limit)
        .map { it.value }
}

/**
 * Start a session — or write one down and start it later.
 *
 * The second half is why the drafts live here rather than on a board of their own. A
 * draft *is* the body of this form (`docs/api.md:695`), so a saved one is this screen
 * with its fields already filled; putting the list anywhere else would mean two places
 * that both claim to be where a session is set up.
 *
 * `docs/api.md:121` names the case it exists for: *"setting work up at the desk and
 * releasing it from a phone when quota frees up."* This is the releasing half.
 */
@Composable
fun NewSessionScreen(
    dirs: List<RecentDir>,
    cwd: String?,
    onCwdChanged: (String) -> Unit,
    mode: String,
    modes: List<String>,
    onModeChanged: (String) -> Unit,
    model: String,
    onModelChanged: (String) -> Unit,
    effort: String,
    onEffortChanged: (String) -> Unit,
    prompt: String,
    onPromptChanged: (String) -> Unit,
    test: Boolean,
    onTestChanged: (Boolean) -> Unit,
    starting: Boolean,
    savingDraft: Boolean,
    error: String?,
    drafts: List<Draft>,
    editingDraftId: String?,
    onStart: () -> Unit,
    onSaveDraft: () -> Unit,
    onEditDraft: (Draft) -> Unit,
    onStartDraft: (Draft) -> Unit,
    onDeleteDraft: (Draft) -> Unit,
    onClearDraftEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dirMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    // Open on arrival when there is something waiting, because that is the whole point
    // of releasing work from a phone — but collapsible, because on the days there are
    // twelve of them the form is what you came for.
    // Shut by default. The list is a place to *go back to*, and the form underneath is
    // what the tab is for — a section that opens itself pushes the first message box
    // off the screen on the days there are several drafts saved.
    var draftsOpen by rememberSaveable { mutableStateOf(false) }

    val promptFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A one-shot signal rather than keying on `editingDraftId`: saving a *new* draft
    // also sets that id, and the keyboard should not reappear because something was
    // filed successfully.
    var focusPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(focusPrompt) {
        if (!focusPrompt) return@LaunchedEffect
        focusPrompt = false
        // The field lives in a `verticalScroll`, so taking focus also scrolls it into
        // view — which is the other half of what tapping a draft should do.
        promptFocus.requestFocus()
        keyboard?.show()
    }

    val busy = starting || savingDraft
    val ready = !cwd.isNullOrBlank() && prompt.isNotBlank()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Metrics.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (drafts.isNotEmpty()) {
            DraftsSection(
                drafts = drafts,
                open = draftsOpen,
                editingDraftId = editingDraftId,
                busy = busy,
                onToggle = { draftsOpen = !draftsOpen },
                onEdit = { draft ->
                    onEditDraft(draft)
                    // Collapse first: the row that was tapped is about to be replaced
                    // by the form it filled, and leaving the list open means the thing
                    // you are now editing is off the bottom of the screen.
                    draftsOpen = false
                    focusPrompt = true
                },
                onStart = onStartDraft,
                onDelete = onDeleteDraft,
            )
            HorizontalDivider(color = tgx.outlineSoft, thickness = 1.dp)
        }

        if (editingDraftId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Editing a saved draft",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearDraftEdit, enabled = !busy) {
                    Text("Start fresh")
                }
            }
        }

        Text("Where", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        Box {
            OutlinedButton(
                onClick = { dirMenu = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
            ) {
                com.twentygx.tgxcode.ui.theme.projectAccent(cwd)?.let { c ->
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(8.dp)
                            .background(c, androidx.compose.foundation.shape.CircleShape),
                    )
                }
                Text(
                    dirs.firstOrNull { it.path == cwd }?.label ?: cwd ?: "Pick a directory",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = dirMenu, onDismissRequest = { dirMenu = false }) {
                dirs.forEach { dir ->
                    DropdownMenuItem(
                        text = { Text(dir.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { dirMenu = false; onCwdChanged(dir.path) },
                    )
                }
            }
        }

        Text("Mode", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        Box {
            OutlinedButton(
                onClick = { modeMenu = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
            ) { Text(modeLabel(mode), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                modes.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(modeLabel(option)) },
                        onClick = { modeMenu = false; onModeChanged(option) },
                    )
                }
            }
        }

        // Side by side: both are short words, and `inherit` is the answer most of the
        // time, so two full-width rows would be a lot of screen for two defaults.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Model", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                ChoiceButton(model, SessionChoices.MODELS, onModelChanged)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Effort", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
                ChoiceButton(effort, SessionChoices.EFFORTS, onEffortChanged)
            }
        }

        Text("First message", style = MaterialTheme.typography.labelMedium, color = tgx.textMuted)
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChanged,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .focusRequester(promptFocus),
            placeholder = { Text("What should it do?") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = test, onCheckedChange = onTestChanged)
            Text(
                "Test session — keep it out of the everyday window",
                style = MaterialTheme.typography.bodySmall,
                color = tgx.textMuted,
            )
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tgx.red)
        }

        Button(
            onClick = onStart,
            enabled = !busy && ready,
            shape = Pill,
            colors = ButtonDefaults.buttonColors(
                containerColor = tgx.blueDeep,
                contentColor = Color.White,
                disabledContainerColor = tgx.s4,
                disabledContentColor = tgx.textFaint,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.PrimaryButtonHeight),
        ) { Text(if (starting) "Starting…" else "Start") }

        // Quieter than Start on purpose: one of these runs a process and the other
        // writes a file, and they should not look like the same weight of decision.
        OutlinedButton(
            onClick = onSaveDraft,
            enabled = !busy && ready,
            shape = Pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.PrimaryButtonHeight),
        ) {
            Text(
                when {
                    savingDraft -> "Saving…"
                    editingDraftId != null -> "Save changes"
                    else -> "Save as draft"
                },
            )
        }
    }
}

/**
 * Drafts, newest first — **the order the bridge sent**, which is by `updatedAt`, so
 * editing one moves it to the top. Not re-sorted here.
 */
@Composable
private fun DraftsSection(
    drafts: List<Draft>,
    open: Boolean,
    editingDraftId: String?,
    busy: Boolean,
    onToggle: () -> Unit,
    onEdit: (Draft) -> Unit,
    onStart: (Draft) -> Unit,
    onDelete: (Draft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = Metrics.TouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Drafts · ${drafts.size}",
                style = MaterialTheme.typography.labelMedium,
                color = tgx.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (open) "Hide" else "Show",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                drafts.forEach { draft ->
                    DraftRow(
                        draft = draft,
                        editing = draft.id == editingDraftId,
                        busy = busy,
                        onEdit = { onEdit(draft) },
                        onStart = { onStart(draft) },
                        onDelete = { onDelete(draft) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftRow(
    draft: Draft,
    editing: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    onDelete: () -> Unit,
) {
    val meta = listOfNotNull(
        draft.projectName?.takeIf { it.isNotBlank() },
        modeLabel(draft.permissionMode),
        draft.model?.takeIf { it.isNotBlank() },
        draft.effort?.takeIf { it.isNotBlank() }?.let { "effort $it" },
        ago(draft.updatedAt).takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (editing) tgx.s3 else tgx.s2,
                RoundedCornerShape(Radius.Md),
            )
            .clickable(enabled = !busy, onClick = onEdit)
            .padding(Metrics.CardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                draft.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // No confirmation, deliberately: a draft is a small file, unlike a session
            // whose transcript cannot be reconstructed.
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete this draft",
                    tint = tgx.textFaint,
                )
            }
        }

        if (meta.isNotBlank()) {
            Text(meta, style = TgxType.mono12, color = tgx.textFaint, maxLines = 1)
        }

        OutlinedButton(
            onClick = onStart,
            enabled = !busy,
            shape = Pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.TouchTarget),
        ) { Text(if (editing) "Save and start" else "Start") }
    }
}

/** A full-width dropdown over [SessionChoices], `""` reading as `inherit`. */
@Composable
private fun ChoiceButton(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.FieldHeight),
        ) { Text(SessionChoices.label(value), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(SessionChoices.label(option)) },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}
