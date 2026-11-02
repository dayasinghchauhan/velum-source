package com.daya.velum.ui.gallery

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem as M3Item
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.daya.velum.media.MediaItem
import com.daya.velum.media.MediaKind
import com.daya.velum.media.MediaRepository
import com.daya.velum.ui.camera.IconBtn
import com.daya.velum.ui.components.G
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryScreen(media: MediaRepository, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var open by remember { mutableStateOf<MediaItem?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { items = media.list() }
    BackHandler { if (open != null) open = null else onBack() }

    Box(Modifier.fillMaxSize().background(V.Black).windowInsetsPadding(WindowInsets.systemBars)) {
        val o = open
        if (o != null) {
            Viewer(media, o, onClose = { open = null }, onDeleted = {
                open = null; scope.launch { items = media.list() }
            })
            return@Box
        }
        Column {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(G.BACK, "Back to camera", 0f, onBack)
                Text("Velum", style = VType.title)
                Spacer(Modifier.weight(1f))
                Text(items?.let { "${it.size} items" } ?: "", style = VType.small.copy(color = V.Ash), modifier = Modifier.padding(end = 14.dp))
            }
            val list = items
            when {
                list == null -> {}
                list.isEmpty() -> Text("Nothing here yet. Photos and videos you take with Velum appear in DCIM/Velum.",
                    style = VType.body.copy(color = V.Ash), modifier = Modifier.padding(24.dp))
                else -> LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(2.dp)) {
                    items(list, key = { it.uri.toString() }) { item -> Cell(media, item) { open = item } }
                }
            }
        }
    }
}

@Composable
private fun Cell(media: MediaRepository, item: MediaItem, onClick: () -> Unit) {
    var bmp by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { bmp = media.thumbnail(item.uri, 320) }
    Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(V.Graphite).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        val tag = when (item.kind) { MediaKind.VIDEO -> fmtDur(item.durationMs); MediaKind.RAW -> "DNG"; else -> null }
        tag?.let {
            Text(it, style = VType.micro, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(V.Scrim, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
    }
}

private fun fmtDur(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

@Composable
private fun Viewer(media: MediaRepository, item: MediaItem, onClose: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var details by remember(item.uri) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(item.uri) {
        details = withContext(Dispatchers.IO) {
            buildList {
                add("File" to item.name)
                add("Date" to DateFormat.getDateTimeInstance().format(Date(item.dateMs)))
                add("Size" to "%.1f MB".format(item.sizeBytes / 1e6))
                if (item.kind == MediaKind.VIDEO) {
                    val p = media.probeVideo(item.uri)
                    add("Resolution" to "${p.width} × ${p.height}")
                    add("Duration" to fmtDur(p.durationMs))
                    p.fps?.let { add("Frame rate" to "%.2f fps".format(it)) }
                    p.bitrate?.let { add("Bitrate" to "%.1f Mbit/s".format(it / 1e6)) }
                    add("Audio" to if (p.hasAudio) "Yes" else "No")
                } else runCatching {
                    ctx.contentResolver.openInputStream(item.uri)?.use { s ->
                        val e = ExifInterface(s)
                        add("Resolution" to "${e.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: item.width} × ${e.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: item.height}")
                        e.getAttribute(ExifInterface.TAG_MODEL)?.let { add("Camera" to it) }
                        e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { add("Shutter" to if (it < 1) "1/${Math.round(1 / it)}" else "${it}s") }
                        e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO" to it) }
                        e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { add("Aperture" to "f/$it") }
                        e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { add("Focal length" to it) }
                        e.getAttribute(ExifInterface.TAG_SOFTWARE)?.let { add("Software" to it) }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(V.Black)) {
        when (item.kind) {
            MediaKind.VIDEO -> VideoPlayer(item.uri)
            MediaKind.RAW -> Text("DNG raw file. Open it in a raw editor (Lightroom, Snapseed, darktable) to develop it; the matching JPEG is saved next to it.",
                style = VType.body.copy(color = V.Ash), modifier = Modifier.align(Alignment.Center).padding(32.dp))
            MediaKind.PHOTO -> ZoomImage(item.uri)
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(G.BACK, "Back", 0f, onClose)
            Spacer(Modifier.weight(1f))
            IconBtn(G.INFO, "Details", 0f, { info = !info }, tint = if (info) V.Amber else V.Bone)
            IconBtn(G.TRASH, "Delete", 0f, { confirm = true })
        }
        if (info) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(V.Scrim).padding(20.dp)) {
            for ((k, v) in details) Row { Text(k, style = VType.small.copy(color = V.Ash), modifier = Modifier.width(110.dp)); Text(v, style = VType.small) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = V.Graphite,
        title = { Text("Delete ${if (item.kind == MediaKind.VIDEO) "video" else "photo"}?", style = VType.title) },
        text = { Text("${item.name} will be removed from the device. This can't be undone.", style = VType.body) },
        confirmButton = { TextButton({ confirm = false; scope.launch { if (media.delete(item.uri)) onDeleted() } }) { Text("Delete", color = V.Tally) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep", color = V.Bone) } },
    )
}

@Composable
private fun ZoomImage(uri: Uri) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val maxSide = 2400
                    val sz = info.size
                    val k = maxOf(sz.width, sz.height).toFloat() / maxSide
                    if (k > 1f) d.setTargetSize((sz.width / k).toInt(), (sz.height / k).toInt())
                }
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(it.asImageBitmap(), "Photo", Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> scale = (scale * z).coerceIn(1f, 6f); off = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan } }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(M3Item.fromUri(uri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } }, Modifier.fillMaxSize())
}
