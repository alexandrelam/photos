package io.github.alexandrelam.photos.viewer

import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.server.ServerLinks
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import kotlinx.coroutines.launch

/** Hands the list to show from a grid to the viewer screen. */
object ViewerSession {
    internal var items: List<MediaItem> = emptyList()
    internal var start: Int = 0

    fun open(items: List<MediaItem>, index: Int) {
        this.items = items
        this.start = index
    }
}

/** Full-screen pager over the list given to [ViewerSession.open]. */
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val items = remember { ViewerSession.items }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pager = rememberPagerState(initialPage = ViewerSession.start.coerceIn(0, items.lastIndex)) { items.size }
    val synced by graph.sync.syncedKeys.collectAsState()
    var showInfo by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    val current = items[pager.currentPage]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strip = rememberLazyListState()
    val white = Color(0xFFF2F1EE)
    val muted = Color(0xFFA4A7AE)

    LaunchedEffect(pager.currentPage) {
        playing = null
        strip.animateScrollToItem((pager.currentPage - 3).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = white) }
            Column(Modifier.weight(1f)) {
                Text(Format.dayAndTime(current.takenAt), style = Darkroom.type.body, color = white)
                Text(current.name, style = Darkroom.type.caption, color = muted, maxLines = 1)
            }
            SyncBadge(current, synced)
            Spacer(Modifier.width(12.dp))
        }

        HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { items[it].id }) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (item.isVideo && playing == item.id) {
                    VideoPlayer(item)
                } else {
                    AsyncImage(
                        model = item.full,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable { showInfo = !showInfo },
                    )
                    if (item.isVideo) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(Color(0x99000000)).clickable { playing = item.id },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.PlayArrow, "Play", tint = white, modifier = Modifier.size(36.dp)) }
                    }
                }
            }
        }

        if (showInfo) InfoPanel(current)

        LazyRow(
            state = strip,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                val selected = index == pager.currentPage
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 40.dp, height = 52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (selected) Modifier.border(BorderStroke(2.dp, Darkroom.colors.accent), RoundedCornerShape(8.dp)) else Modifier)
                        .background(Color(0xFF22252B))
                        .clickable { scope.launch { pager.animateScrollToPage(index) } },
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Action(Icons.Outlined.Share, "Share") {
                scope.launch {
                    runCatching { graph.actions.shareIntent(listOf(current)) }
                        .onSuccess { context.startActivity(it) }
                        .onFailure { Toast.makeText(context, it.message ?: "Couldn't share", Toast.LENGTH_SHORT).show() }
                }
            }
            Action(Icons.Outlined.Info, "Info", highlighted = showInfo) { showInfo = !showInfo }
            val notSynced = current.local != null && current.local.key !in synced
            Action(Icons.Outlined.CloudUpload, if (notSynced) "Sync" else "Synced", enabled = notSynced) {
                graph.sync.startOne(current)
                Toast.makeText(context, "Uploading ${current.name}", Toast.LENGTH_SHORT).show()
            }
            Action(Icons.Outlined.Download, "Save", enabled = current.server != null && current.local == null && Build.VERSION.SDK_INT >= 29) {
                scope.launch {
                    val message = runCatching { graph.actions.saveToPhone(listOf(current)) }
                        .fold({ "Saved to ${if (current.isVideo) "Movies" else "Pictures"}/Darkroom" }, { it.message ?: "Couldn't save" })
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun SyncBadge(item: MediaItem, synced: Set<String>) {
    val colors = Darkroom.colors
    val (label, icon, color) = when {
        item.local == null -> Triple("On server", Icons.Outlined.Check, colors.success)
        item.local.key in synced -> Triple("Synced", Icons.Outlined.Check, colors.success)
        else -> Triple("Not synced", Icons.Outlined.CloudOff, colors.accent)
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight), color = color)
    }
}

@Composable
private fun InfoPanel(item: MediaItem) {
    val label = Color(0xFF7D818A)
    val value = Color(0xFFF2F1EE)
    @Composable
    fun Cell(title: String, text: String, modifier: Modifier) = Column(modifier) {
        Text(title.uppercase(), style = Darkroom.type.label.copy(fontSize = Darkroom.type.caption.fontSize), color = label)
        Spacer(Modifier.height(2.dp))
        Text(text, style = Darkroom.type.body, color = value, maxLines = 2)
    }
    val dims = if (item.width != null && item.height != null) "${item.width} × ${item.height} · " else ""
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF17181B))
            .padding(16.dp),
    ) {
        Row {
            Cell("File", item.name, Modifier.weight(1f))
            Cell("Size", dims + Format.bytes(item.size), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Cell("On phone", item.local?.relativePath?.trimEnd('/') ?: "—", Modifier.weight(1f))
            Cell("On server", item.server?.folder?.let { "/$it" } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, enabled: Boolean = true, highlighted: Boolean = false, onClick: () -> Unit) {
    val colors = Darkroom.colors
    val tint = when {
        !enabled -> Color(0xFF4A4D55)
        highlighted -> colors.accent
        else -> Color(0xFFF2F1EE)
    }
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = Darkroom.type.caption, color = tint)
    }
}

@Composable
private fun VideoPlayer(item: MediaItem) {
    val graph = LocalAppGraph.current
    // Local videos play from their content URI; server videos stream with the access key.
    val source by produceState<Pair<Uri, Map<String, String>>?>(null, item.id) {
        value = when {
            item.local != null -> item.local.uri to emptyMap()
            item.server != null -> runCatching {
                Uri.parse(graph.server.realUrl(ServerLinks.file(item.server.id))) to graph.server.authHeaders()
            }.getOrNull()
            else -> null
        }
    }
    val ready = source ?: return
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).apply {
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setVideoURI(ready.first, ready.second)
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}
