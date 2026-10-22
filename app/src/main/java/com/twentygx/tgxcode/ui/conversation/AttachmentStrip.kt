package com.twentygx.tgxcode.ui.conversation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twentygx.tgxcode.data.attach.AttachmentSource
import com.twentygx.tgxcode.data.attach.MAX_ATTACHMENTS
import com.twentygx.tgxcode.data.attach.StageState
import com.twentygx.tgxcode.data.attach.StagedFile
import com.twentygx.tgxcode.data.attach.formatBytes
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * The + beside a message box: Photo or File.
 *
 * The system photo picker and the document picker, so there is no storage permission
 * anywhere in the app — the pick is the grant. [remaining] caps a multi-pick at what the
 * message still has room for; at zero the button stays but does nothing, and the strip
 * already shows why.
 */
@Composable
fun AttachButton(
    remaining: Int,
    onPicked: (List<Uri>) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    // The multi-pick contract refuses a limit under two, so one slot left is a single pick.
    val photos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    val photo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPicked(listOf(uri)) }
    val files = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onPicked(uris) }

    Box {
        IconButton(
            onClick = { open = true },
            enabled = enabled && remaining > 0,
            modifier = Modifier.size(Metrics.TouchTarget),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Attach", tint = tgx.textMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Photo") },
                onClick = {
                    open = false
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (remaining >= 2) photos.launch(request) else photo.launch(request)
                },
            )
            DropdownMenuItem(
                text = { Text("File") },
                onClick = {
                    open = false
                    files.launch(arrayOf("*/*"))
                },
            )
        }
    }
}

/**
 * The files on a message, one chip each: a thumbnail or the extension, the name, and
 * what is happening to it. Scrolls sideways rather than wrapping — five chips on a phone
 * would otherwise push the box off the screen.
 */
@Composable
fun AttachmentStrip(
    files: List<StagedFile>,
    onRemove: (Long) -> Unit,
    onRetry: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.forEach { file ->
            AttachmentChip(
                file = file,
                onRemove = { onRemove(file.id) },
                onRetry = onRetry?.let { retry -> { retry(file.id) } },
            )
        }
    }
}

@Composable
private fun AttachmentChip(file: StagedFile, onRemove: () -> Unit, onRetry: (() -> Unit)?) {
    val failed = file.state as? StageState.Failed
    Row(
        Modifier
            .clip(RoundedCornerShape(Radius.Sm))
            .background(tgx.s2)
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(file)
        Column(Modifier.padding(start = 8.dp).widthIn(max = 150.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                when (file.state) {
                    StageState.Uploading -> "uploading…"
                    is StageState.Failed -> failed?.reason.orEmpty()
                    else -> file.size?.let(::formatBytes).orEmpty()
                },
                style = TgxType.mono12,
                color = if (failed != null) tgx.red else tgx.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (failed != null && onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry", style = MaterialTheme.typography.labelMedium) }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove ${file.name}",
                tint = tgx.textFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 36dp: a photo's own pixels, or its extension in mono where it has none to show. */
@Composable
private fun Thumb(file: StagedFile) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 36.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, file.uri) {
        value = if (file.isImage) {
            AttachmentSource(context).thumbnail(Uri.parse(file.uri), px)?.asImageBitmap()
        } else {
            null
        }
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radius.Xs))
            .background(tgx.s4),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp))
        } else {
            Text(
                file.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" },
                style = TgxType.mono12,
                color = tgx.textMuted,
                maxLines = 1,
            )
        }
    }
}
