package com.twentygx.tgxcode.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.twentygx.tgxcode.core.ApiResult
import com.twentygx.tgxcode.core.message
import com.twentygx.tgxcode.core.toolDisplayText
import com.twentygx.tgxcode.data.attach.ChatImageCache
import com.twentygx.tgxcode.data.model.ChatImage
import com.twentygx.tgxcode.data.model.ImageAnchor
import com.twentygx.tgxcode.data.model.describe
import com.twentygx.tgxcode.ui.common.EmptyState
import com.twentygx.tgxcode.ui.common.TgxSheet
import com.twentygx.tgxcode.ui.theme.Metrics
import com.twentygx.tgxcode.ui.theme.Radius
import com.twentygx.tgxcode.ui.theme.TgxType
import com.twentygx.tgxcode.ui.theme.tgx
import kotlinx.coroutines.delay

/**
 * How long a burst of transcript growth is gathered before the list is asked for again.
 * The desktop's figure: an unchanged transcript costs the bridge one `stat`, but a
 * streaming turn appends several times a second.
 */
private const val REFRESH_DEBOUNCE_MS = 1_500L

/** The list as last read, or why there is none. */
private sealed interface ImagesState {
    data object Loading : ImagesState
    data class Done(val images: List<ChatImage>) : ImagesState
    data class Failed(val why: String) : ImagesState
}

/**
 * Every image in the conversation, once each — the desktop's Images pane
 * (`web/transcript/images.js`), as a sheet beside Changes and Turns.
 *
 * Read only while it is open, as the desktop does, and read again whenever
 * [refreshKey] moves: the transcript growing, or a turn ending — which is when a
 * subagent's screenshots are finished, though the session's own file may not grow.
 *
 * @param onJump close everything and scroll the conversation to that occurrence.
 */
@Composable
fun ImagesSheet(
    images: ChatImageCache,
    sessionId: String,
    refreshKey: Any?,
    onJump: (ImageAnchor) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember(sessionId) { mutableStateOf<ImagesState>(ImagesState.Loading) }
    var viewing by remember(sessionId) { mutableStateOf<ChatImage?>(null) }

    // A new key cancels the wait in flight, which is what makes the delay a debounce.
    LaunchedEffect(sessionId, refreshKey) {
        if (state is ImagesState.Done) delay(REFRESH_DEBOUNCE_MS)
        state = when (val result = images.list(sessionId)) {
            is ApiResult.Ok -> ImagesState.Done(result.value.images)
            // A refresh that fails keeps what is on screen: it was right a moment ago.
            else -> (state as? ImagesState.Done) ?: ImagesState.Failed(result.message())
        }
    }

    val list = (state as? ImagesState.Done)?.images
    TgxSheet(
        title = "Images",
        subtitle = list?.takeIf { it.isNotEmpty() }?.let {
            "${it.size} image${if (it.size == 1) "" else "s"} · newest first"
        },
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
    ) {
        when (val s = state) {
            ImagesState.Loading -> EmptyState("Reading the transcript…", modifier = Modifier.heightIn(max = 240.dp))
            is ImagesState.Failed -> EmptyState(
                "Could not read the images",
                s.why,
                modifier = Modifier.heightIn(max = 240.dp),
            )
            is ImagesState.Done -> if (s.images.isEmpty()) {
                EmptyState(
                    "No images in this chat yet",
                    "Pictures you attach, and screenshots or images a tool hands back, collect here.",
                    modifier = Modifier.heightIn(max = 280.dp),
                )
            } else {
                ImageGrid(images, sessionId, s.images, onOpen = { viewing = it })
            }
        }
    }

    viewing?.let { image ->
        ImageViewer(
            images = images,
            sessionId = sessionId,
            image = image,
            onJump = { anchor ->
                viewing = null
                onJump(anchor)
            },
            onDismiss = { viewing = null },
        )
    }
}

/** Newest first, as the bridge sends them: the one that just arrived is the one you came for. */
@Composable
private fun ImageGrid(
    cache: ChatImageCache,
    sessionId: String,
    images: List<ChatImage>,
    onOpen: (ChatImage) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(images, key = { it.key }) { image ->
            ImageTile(cache, sessionId, image, onOpen = { onOpen(image) })
        }
        // Room under the last row, clear of the gesture bar.
        item { Box(Modifier.heightIn(min = 8.dp)) }
    }
}

/** One square: the thumbnail, its ×N, and only fetched once the grid lays it out. */
@Composable
private fun ImageTile(cache: ChatImageCache, sessionId: String, image: ChatImage, onOpen: () -> Unit) {
    // Twice the tile, so a two-column phone still gets a sharp square.
    val px = with(LocalDensity.current) { 220.dp.roundToPx() }
    val thumb by produceState<Thumb>(
        cache.peek(image.key, px)?.let { Thumb.Ready(it.asImageBitmap()) } ?: Thumb.Loading,
        image.key,
    ) {
        if (value is Thumb.Ready) return@produceState
        value = cache.bitmap(sessionId, image.key, px)?.let { Thumb.Ready(it.asImageBitmap()) } ?: Thumb.Missing
    }
    val text = image.describe()
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.Sm))
            .background(tgx.s2)
            .clickable(onClickLabel = "Open $text", onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        when (val t = thumb) {
            is Thumb.Ready -> Image(
                t.bitmap,
                contentDescription = text,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Thumb.Loading -> Text(image.mediaType.substringAfter('/'), style = TgxType.mono12, color = tgx.textFaint)
            Thumb.Missing -> Text("not available", style = TgxType.mono12, color = tgx.textFaint)
        }
        if (image.count > 1) {
            Text(
                "×${image.count}",
                style = TgxType.mono12,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(Radius.Sm))
                    .padding(horizontal = 5.dp),
            )
        }
    }
}

private sealed interface Thumb {
    data object Loading : Thumb
    data object Missing : Thumb
    data class Ready(val bitmap: ImageBitmap) : Thumb
}

/**
 * The picture large, on black, pinch to zoom and double-tap to toggle. With the bar
 * the desktop's preview has when opened from the pane: ‹ 1 of N › through each place
 * the picture appeared, and Jump to message for the one showing.
 */
@Composable
private fun ImageViewer(
    images: ChatImageCache,
    sessionId: String,
    image: ChatImage,
    onJump: (ImageAnchor) -> Unit,
    onDismiss: () -> Unit,
) {
    val occurrences = image.occurrences
    var at by remember(image.key) { mutableIntStateOf(0) }
    val occurrence = occurrences.getOrNull(at)
    val px = with(LocalDensity.current) { 1600.dp.roundToPx() }.coerceAtMost(2048)
    val full by produceState<Thumb>(Thumb.Loading, image.key) {
        value = images.bitmap(sessionId, image.key, px)?.let { Thumb.Ready(it.asImageBitmap()) } ?: Thumb.Missing
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        occurrence?.title ?: image.label?.let(::toolDisplayText) ?: "Image",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        image.describe(withLabel = false),
                        style = TgxType.mono12,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val f = full) {
                    is Thumb.Ready -> ZoomableImage(f.bitmap, image.label?.let(::toolDisplayText) ?: "Image", Modifier.fillMaxSize())
                    Thumb.Loading -> Text("Loading…", color = Color.White.copy(alpha = 0.6f))
                    Thumb.Missing -> Text(
                        "This image is not available any more.",
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            if (occurrences.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (occurrences.size > 1) {
                        TextButton(onClick = { at-- }, enabled = at > 0) { Text("‹", style = MaterialTheme.typography.titleMedium) }
                        Text(
                            "${at + 1} of ${occurrences.size}",
                            style = TgxType.mono12,
                            color = Color.White,
                        )
                        TextButton(onClick = { at++ }, enabled = at < occurrences.lastIndex) {
                            Text("›", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Box(Modifier.weight(1f))
                    val anchor = occurrence?.anchor
                    TextButton(onClick = { anchor?.let(onJump) }, enabled = anchor != null) {
                        Text(if (anchor != null) "Jump to message" else "Not in the conversation")
                    }
                }
            }
        }
    }
}

/** Pinch to zoom, drag to pan while zoomed, double-tap to go in or back out. */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String, modifier: Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Panning stops where the picture's edge meets the frame's.
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Image(
        bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        // Zoom toward the tap rather than the middle.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - tap) * (scale - 1f), scale)
                    }
                })
            }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else clamp(offset + pan, scale)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
