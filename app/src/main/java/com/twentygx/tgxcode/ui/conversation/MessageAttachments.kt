package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.twentygx.tgxcode.data.attach.decodeDataUri
import com.twentygx.tgxcode.data.attach.filesBesideImages
import com.twentygx.tgxcode.data.model.AttachmentRef
import com.twentygx.tgxcode.data.model.FileRef
import com.twentygx.tgxcode.data.model.ImageRef
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx

/**
 * What a user turn carried besides its words: the images the model was sent, drawn,
 * and the files beside them as 📎 cards. The cards do not open anything — opening an
 * attachment launches a program on the desktop, which the bridge keeps local-only.
 */
@Composable
fun MessageAttachments(images: List<ImageRef>, files: List<FileRef>, modifier: Modifier = Modifier) {
    val cards = remember(files, images) { filesBesideImages(files, images) }
    if (images.isEmpty() && cards.isEmpty()) return
    var viewing by remember { mutableStateOf<String?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.forEach { image ->
                    val uri = image.dataUri
                    if (uri == null) {
                        // The bridge could not read it back; say so rather than drop it.
                        Text("image", style = TgxType.mono12, color = tgx.textFaint)
                    } else {
                        InlineImage(uri, onOpen = { viewing = uri })
                    }
                }
            }
        }
        cards.forEach { file ->
            Text(
                "📎 " + listOfNotNull(file.name ?: file.relPath, file.size).joinToString(" · "),
                style = TgxType.mono12,
                color = tgx.textMuted,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }

    viewing?.let { uri -> FullImage(uri, onDismiss = { viewing = null }) }
}

/** A queued or scheduled message's files, by name — the rows hold paths, not pictures. */
@Composable
fun AttachmentNames(refs: List<AttachmentRef>) {
    if (refs.isEmpty()) return
    Text(
        "📎 " + refs.joinToString(", ") { it.name },
        style = TgxType.mono12,
        color = tgx.textMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun InlineImage(dataUri: String, onOpen: () -> Unit) {
    val px = with(LocalDensity.current) { 220.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, dataUri) {
        value = decodeDataUri(dataUri, px)?.asImageBitmap()
    }
    val image = bitmap
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.Sm))
            .background(tgx.s2)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Box(Modifier.size(96.dp))
        } else {
            Image(
                image,
                contentDescription = "Attached image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = 160.dp).widthIn(max = 220.dp),
            )
        }
    }
}

/** The whole picture, on black, until a tap anywhere. */
@Composable
private fun FullImage(dataUri: String, onDismiss: () -> Unit) {
    val px = with(LocalDensity.current) { 1600.dp.roundToPx() }.coerceAtMost(2048)
    val bitmap by produceState<ImageBitmap?>(null, dataUri) {
        value = decodeDataUri(dataUri, px)?.asImageBitmap()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it, contentDescription = "Attached image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
