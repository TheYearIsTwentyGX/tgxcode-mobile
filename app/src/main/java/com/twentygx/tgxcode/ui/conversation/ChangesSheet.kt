package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.ago
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.data.model.Changes
import com.twentygx.tgxcode.data.model.FileDiff
import com.twentygx.tgxcode.data.model.ScratchFile
import com.twentygx.tgxcode.data.model.ScratchFileContent
import com.twentygx.tgxcode.data.model.Scratchpad
import com.twentygx.tgxcode.data.net.BridgeApi
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/** One loaded answer, or why there is none. */
private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Done<T>(val value: T) : Loaded<T>
    data class Failed(val why: String) : Loaded<Nothing>
}

private fun <T> ApiResult<T>.loaded(): Loaded<T> = when (this) {
    is ApiResult.Ok -> Loaded.Done(value)
    else -> Loaded.Failed(message())
}

/** What the sheet is showing: one of the two lists, or one file opened from them. */
private sealed interface FileView {
    data class Diff(val path: String) : FileView
    data class Scratch(val file: ScratchFile) : FileView
}

/**
 * The desktop's Changes drawer, as a sheet: what the session changed, the tree as it
 * stands, and the scratchpad where the agent keeps its probes and drafts.
 *
 * Every read here is a one-off by a person who wants it as it is now, so nothing is held
 * after the sheet closes — `/changes` shells out to git, and `/diff` is deliberately not
 * cached on the bridge either.
 */
@Composable
fun ChangesSheet(api: BridgeApi, sessionId: String, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<FileView?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    TgxSheet(
        title = when (val v = open) {
            is FileView.Diff -> v.path.substringAfterLast('/')
            is FileView.Scratch -> v.file.path.substringAfterLast('/')
            null -> "Files"
        },
        subtitle = when (val v = open) {
            is FileView.Diff -> v.path
            is FileView.Scratch -> v.file.path
            null -> null
        },
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        val view = open
        if (view != null) {
            TextButton(onClick = { open = null }, modifier = Modifier.padding(start = 4.dp)) { Text("‹ Back") }
            when (view) {
                is FileView.Diff -> DiffView(api, sessionId, view.path)
                is FileView.Scratch -> ScratchView(api, sessionId, view.file)
            }
            return@TgxSheet
        }

        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Changes") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Scratchpad") })
        }
        if (tab == 0) {
            val changes by produceState<Loaded<Changes>>(Loaded.Loading, refreshTick) {
                value = Loaded.Loading
                value = api.changes(sessionId, refresh = refreshTick > 0).loaded()
            }
            ChangesList(
                changes,
                onOpen = { open = FileView.Diff(it) },
                onRefresh = { refreshTick++ },
            )
        } else {
            val pad by produceState<Loaded<Scratchpad>>(Loaded.Loading) {
                value = api.scratchpad(sessionId).loaded()
            }
            ScratchList(pad, onOpen = { open = FileView.Scratch(it) })
        }
    }
}

@Composable
private fun Note(text: String, color: Color = tgx.textMuted) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(Metrics.ScreenPadding),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tgx.textMuted,
        modifier = Modifier.padding(start = Metrics.ScreenPadding, end = Metrics.ScreenPadding, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun FileRow(path: String, detail: String?, added: Int?, deleted: Int?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Metrics.TouchTarget)
            .padding(horizontal = Metrics.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(detail, path.substringBeforeLast('/', "").ifBlank { null }).joinToString(" · "),
                style = TgxType.mono12,
                color = tgx.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (added != null) Text("+$added", style = TgxType.mono12, color = tgx.green, modifier = Modifier.padding(start = 8.dp))
        if (deleted != null) Text("−$deleted", style = TgxType.mono12, color = tgx.red, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ChangesList(changes: Loaded<Changes>, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
    when (changes) {
        Loaded.Loading -> Note("Reading the tree…")
        is Loaded.Failed -> Note("Could not read the changes: ${changes.why}", tgx.red)
        is Loaded.Done -> {
            val c = changes.value
            LazyColumn(Modifier.padding(bottom = 12.dp)) {
                item {
                    val git = c.git
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                git == null -> "No working tree"
                                !git.ok -> when (git.reason) {
                                    "not-a-repo" -> "Not a git repository"
                                    "no-directory" -> "The directory is gone"
                                    "left-behind" -> "A removed worktree — not a checkout of its own"
                                    else -> "git status failed"
                                }
                                else -> listOfNotNull(
                                    git.branch,
                                    "↑${git.ahead}".takeIf { git.ahead > 0 },
                                    "↓${git.behind}".takeIf { git.behind > 0 },
                                    "${git.files} uncommitted",
                                ).joinToString(" · ")
                            },
                            style = TgxType.mono12,
                            color = tgx.textMuted,
                            modifier = Modifier.weight(1f).padding(start = Metrics.ScreenPadding),
                        )
                        TextButton(onClick = onRefresh) { Text("Refresh") }
                    }
                }
                val tree = c.git?.takeIf { it.ok }?.sample.orEmpty()
                if (tree.isNotEmpty()) {
                    item { SectionLabel("Working tree") }
                    items(tree, key = { "g:" + it.path }) { f ->
                        FileRow(
                            f.path,
                            detail = gitStatusWord(f.status) + if (f.binary) " · binary" else "",
                            added = f.added,
                            deleted = f.deleted,
                            onClick = { onOpen(f.path) },
                        )
                    }
                    c.git?.truncated?.takeIf { it > 0 }?.let { item { Note("…and $it more") } }
                }
                if (c.edits.isNotEmpty()) {
                    item { SectionLabel("Edited in this conversation") }
                    items(c.edits, key = { "e:" + it.path }) { e ->
                        FileRow(
                            e.relPath ?: e.path,
                            detail = "${e.edits} edit${if (e.edits == 1) "" else "s"}",
                            added = e.added,
                            deleted = e.deleted,
                            onClick = { onOpen(e.relPath ?: e.path) },
                        )
                    }
                }
                if (tree.isEmpty() && c.edits.isEmpty()) item { Note("Nothing changed.") }
                c.agents?.takeIf { it.total > 0 }?.let { a ->
                    item { Note("${a.total} subagent${if (a.total == 1) "" else "s"}, ${a.edited} of them edited files.") }
                }
            }
        }
    }
}

/** The porcelain-v2 XY code as a word — the two letters are the index and the tree. */
internal fun gitStatusWord(xy: String?): String {
    val code = xy?.trim().orEmpty()
    return when {
        code.startsWith("?") -> "untracked"
        code.contains('U') -> "conflict"
        code.contains('A') -> "added"
        code.contains('D') -> "deleted"
        code.contains('R') -> "renamed"
        code.contains('M') -> "modified"
        else -> code.ifBlank { "changed" }
    }
}

@Composable
private fun DiffView(api: BridgeApi, sessionId: String, path: String) {
    val diff by produceState<Loaded<FileDiff>>(Loaded.Loading, path) {
        value = api.diff(sessionId, path).loaded()
    }
    when (val d = diff) {
        Loaded.Loading -> Note("Reading the diff…")
        is Loaded.Failed -> Note("Could not read it: ${d.why}", tgx.red)
        is Loaded.Done -> {
            val f = d.value
            when {
                !f.ok -> Note(
                    when (f.reason) {
                        "no-such-file" -> "That file is not there any more."
                        "outside-repo" -> "That file is outside the repository."
                        else -> f.error ?: f.reason ?: "No diff."
                    },
                )
                f.binary -> Note("A binary file — there is no text diff to show.")
                f.diff.isBlank() -> Note("No uncommitted change. It may have been committed since.")
                else -> DiffText(f.diff, f.truncated)
            }
        }
    }
}

/** A unified diff, coloured by line, scrolling both ways inside the sheet. */
@Composable
internal fun DiffText(diff: String, truncatedBytes: Long = 0) {
    val lines = remember(diff) { diff.lines() }
    val green = tgx.green
    val red = tgx.red
    val blue = MaterialTheme.colorScheme.primary
    val faint = tgx.textFaint
    val body = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        items(lines.size) { i ->
            val line = lines[i]
            Text(
                line.ifEmpty { " " },
                style = TgxType.mono12,
                softWrap = false,
                color = when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") ||
                        line.startsWith("index ") -> faint
                    line.startsWith("@@") -> blue
                    line.startsWith("+") -> green
                    line.startsWith("-") -> red
                    else -> body
                },
            )
        }
        if (truncatedBytes > 0) {
            item { Text("… $truncatedBytes more bytes not shown", style = TgxType.mono12, color = faint) }
        }
    }
}

@Composable
private fun ScratchList(pad: Loaded<Scratchpad>, onOpen: (ScratchFile) -> Unit) {
    when (pad) {
        Loaded.Loading -> Note("Reading the scratchpad…")
        is Loaded.Failed -> Note("Could not read it: ${pad.why}", tgx.red)
        is Loaded.Done -> {
            val p = pad.value
            if (p.files.isEmpty()) {
                Note(
                    if (p.dirs.isEmpty()) {
                        "No scratchpad. It does not survive a reboot, and a session that never wrote one has none."
                    } else {
                        "The scratchpad is empty."
                    },
                )
                return
            }
            val where = p.dirs.associate { it.key to it.where }
            LazyColumn(Modifier.padding(bottom = 12.dp)) {
                items(p.files, key = { it.dir + "/" + it.path }) { f ->
                    FileRow(
                        f.path,
                        detail = listOfNotNull(
                            where[f.dir]?.let { "in $it" },
                            ago(f.mtimeMs).takeIf { it.isNotBlank() }?.let { "$it ago" },
                            sizeText(f.size),
                        ).joinToString(" · "),
                        added = null,
                        deleted = null,
                        onClick = { onOpen(f) },
                    )
                }
                if (p.truncated) item { Note("Only the newest 400 are listed.") }
            }
        }
    }
}

internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

@Composable
private fun ScratchView(api: BridgeApi, sessionId: String, file: ScratchFile) {
    val content by produceState<Loaded<ScratchFileContent>>(Loaded.Loading, file) {
        value = api.scratchFile(sessionId, file.dir, file.path).loaded()
    }
    when (val c = content) {
        Loaded.Loading -> Note("Reading…")
        is Loaded.Failed -> Note("Could not read it: ${c.why}", tgx.red)
        is Loaded.Done -> {
            val f = c.value
            when {
                !f.ok -> Note(if (f.reason == "no-such-file") "That file is gone." else f.reason ?: "Could not read it.")
                f.binary -> Note("A binary file — nothing to show as text.")
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    SelectionContainer {
                        Text(f.text, style = TgxType.mono12, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (f.truncated) {
                        HorizontalDivider(color = tgx.outlineSoft)
                        Text("Cut at 1 MB of ${sizeText(f.size)}.", style = TgxType.mono12, color = tgx.textFaint)
                    }
                }
            }
        }
    }
}
