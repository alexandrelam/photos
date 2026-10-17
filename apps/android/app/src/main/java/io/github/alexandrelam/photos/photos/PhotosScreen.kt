package io.github.alexandrelam.photos.photos

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.media.MediaThumbnail
import io.github.alexandrelam.photos.media.PhotoAccess
import io.github.alexandrelam.photos.media.PhotoAccessPrompt
import io.github.alexandrelam.photos.server.Connection
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.DarkroomCard
import io.github.alexandrelam.photos.shared.ui.FilterChip
import io.github.alexandrelam.photos.shared.ui.StatusDot
import io.github.alexandrelam.photos.sync.SyncSheet
import io.github.alexandrelam.photos.sync.SyncState

/** The timeline: phone and server photos by day, with the Sync button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(onOpen: (items: List<MediaItem>, index: Int) -> Unit) {
    val graph = LocalAppGraph.current
    val vm = viewModel { PhotosViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val filter by vm.filter.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val access by vm.access.collectAsState()
    val connection by graph.server.connection.collectAsState()
    val prefs by graph.sync.prefs.collectAsState()
    val syncState by graph.sync.state.collectAsState(initial = SyncState.Idle)
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val colors = Darkroom.colors

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.sync.start()
        showSheet = true
    }
    LifecycleResumeEffect(Unit) {
        vm.permissionChanged()
        onPauseOrDispose { }
    }

    fun startSync() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            graph.sync.start()
            showSheet = true
        }
    }

    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 30
        }
    }
    LaunchedEffect(nearEnd, ui.flat.size) { if (nearEnd && vm.hasMore()) vm.loadMore() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Photos", style = Darkroom.type.largeTitle, color = colors.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val online = connection is Connection.Online
                    StatusDot(if (online) colors.success else colors.textMuted)
                    Spacer(Modifier.width(6.dp))
                    val name = (connection as? Connection.Online)?.name ?: graph.server.settings.value?.name ?: "server"
                    val status = when {
                        connection is Connection.Offline -> "offline"
                        prefs.lastSyncAt != null -> "last sync ${Format.ago(prefs.lastSyncAt!!)}"
                        else -> "never synced"
                    }
                    Text("$name · $status", style = Darkroom.type.caption, color = colors.textSecondary)
                }
            }
            SyncButton(
                pending = ui.pending,
                running = syncState is SyncState.Running || syncState is SyncState.Waiting,
                onClick = {
                    if (syncState is SyncState.Running || syncState is SyncState.Waiting) showSheet = true else startSync()
                },
            )
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f -> FilterChip(f.label, selected = f == filter, onClick = { vm.filter.value = f }) }
        }

        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (access != PhotoAccess.Full && filter != Filter.Server) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DarkroomCard(Modifier.padding(16.dp)) {
                            PhotoAccessPrompt(access, graph.phoneMedia.permissions, onChanged = vm::permissionChanged, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                if (ui.error != null && filter != Filter.Phone) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Server: ${ui.error}",
                            style = Darkroom.type.caption,
                            color = colors.accentText,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
                var offset = 0
                ui.sections.forEach { section ->
                    val start = offset
                    offset += section.items.size
                    item(key = "h-${section.date}", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(section) }
                    items(section.items.size, key = { section.items[it].id }) { i ->
                        val item = section.items[i]
                        MediaThumbnail(
                            item,
                            notSynced = item.local != null && item.local.key !in ui.syncedKeys,
                            onClick = { onOpen(ui.flat, start + i) },
                        )
                    }
                }
                if (ui.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                        }
                    }
                }
                if (ui.sections.isEmpty() && !refreshing && (access != PhotoAccess.None || filter == Filter.Server)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "No photos here yet.",
                            style = Darkroom.type.bodyRegular,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        SyncSheet(
            state = syncState,
            connection = connection,
            onPause = { graph.sync.pause() },
            onResume = { startSync() },
            onHide = { showSheet = false },
        )
    }
}

@Composable
private fun SectionHeader(section: Section) {
    val colors = Darkroom.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Format.day(section.date), style = Darkroom.type.section, color = colors.text, modifier = Modifier.weight(1f))
        Text(
            when (section.status) {
                SectionStatus.NotSynced -> "${section.notSynced} not synced"
                SectionStatus.Synced -> "Synced"
                SectionStatus.ServerOnly -> "Server only"
            },
            style = Darkroom.type.caption,
            color = if (section.status == SectionStatus.NotSynced) colors.accentText else colors.textSecondary,
        )
    }
}

@Composable
private fun SyncButton(pending: Int, running: Boolean, onClick: () -> Unit) {
    val colors = Darkroom.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (pending > 0 && !running) 8.dp else 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(Modifier.size(18.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.CloudUpload, null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(if (running) "Syncing" else "Sync", style = Darkroom.type.body, color = colors.onAccent)
        if (pending > 0 && !running) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(colors.onAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (pending > 99) "99+" else "$pending", style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight), color = colors.accent)
            }
        }
    }
}
