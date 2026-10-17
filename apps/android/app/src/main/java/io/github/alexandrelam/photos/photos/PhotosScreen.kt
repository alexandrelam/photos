package io.github.alexandrelam.photos.photos

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.actions.rememberPhoneDeleter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val monthShort = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

/**
 * The timeline: phone and server photos by month (the current month by day), with the
 * Sync button, a fast scroller, "Jump to" and multi-select. [onSelectingChange] tells the
 * caller to hide its tabs while photos are selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(onOpen: (items: List<MediaItem>, index: Int) -> Unit, onSelectingChange: (Boolean) -> Unit = {}) {
    val graph = LocalAppGraph.current
    val vm = viewModel { PhotosViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val filter by vm.filter.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val access by vm.access.collectAsState()
    val selection by vm.selection.collectAsState()
    val connection by graph.server.connection.collectAsState()
    val prefs by graph.sync.prefs.collectAsState()
    val syncState by graph.sync.state.collectAsState(initial = SyncState.Idle)
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showJump by rememberSaveable { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var snackbar by remember { mutableStateOf<PhotosEvent.Message?>(null) }
    val colors = Darkroom.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleter = rememberPhoneDeleter()
    val serverName = (connection as? Connection.Online)?.name ?: graph.server.settings.value?.name ?: "server"

    LaunchedEffect(selection.active) { onSelectingChange(selection.active) }
    BackHandler(enabled = selection.active) { vm.clearSelection() }

    // Uploads need the notification permission first so progress shows outside the app.
    var afterNotificationPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotificationPermission?.invoke()
        afterNotificationPermission = null
    }
    fun withNotifications(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            afterNotificationPermission = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
    fun startSync() = withNotifications {
        graph.sync.start()
        showSheet = true
    }

    LifecycleResumeEffect(Unit) {
        vm.permissionChanged()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is PhotosEvent.Message -> snackbar = event
                is PhotosEvent.Launch -> context.startActivity(event.intent)
            }
        }
    }
    LaunchedEffect(snackbar) {
        if (snackbar != null) {
            delay(4_000)
            snackbar = null
        }
    }

    val grid = rememberLazyGridState()
    val showPrompt = access != PhotoAccess.Full && filter != Filter.Server
    val showError = ui.error != null && filter != Filter.Phone
    // Grid rows before the first timeline entry.
    val lead = (if (showPrompt) 1 else 0) + (if (showError) 1 else 0)
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 30
        }
    }
    LaunchedEffect(nearEnd, ui.flat.size) { if (nearEnd && vm.hasMore()) vm.loadMore() }
    val monthInView by remember(lead) {
        derivedStateOf { ui.entries.getOrNull((grid.firstVisibleItemIndex - lead).coerceAtLeast(0))?.month }
    }

    fun jumpTo(month: YearMonth) = scope.launch {
        vm.ensureLoaded(month)
        val headers = vm.ui.value.headerIndex
        // The month itself, or the closest older one when this filter has nothing that month.
        val target = headers[month] ?: headers.filterKeys { it < month }.maxByOrNull { it.key }?.value ?: headers.values.maxOrNull()
        if (target != null) grid.scrollToItem(lead + target)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selection.active) {
                SelectionHeader(count = selection.ids.size, onCancel = vm::clearSelection)
            } else {
                Header(
                    serverName = serverName,
                    online = connection is Connection.Online,
                    status = when {
                        connection is Connection.Offline -> "offline"
                        prefs.lastSyncAt != null -> "last sync ${Format.ago(prefs.lastSyncAt!!)}"
                        else -> "never synced"
                    },
                    pending = ui.pending,
                    running = syncState is SyncState.Running || syncState is SyncState.Waiting,
                    onSync = { if (syncState is SyncState.Running || syncState is SyncState.Waiting) showSheet = true else startSync() },
                    onCalendar = { showJump = true },
                )
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { f -> FilterChip(f.label, selected = f == filter, onClick = { vm.filter.value = f }) }
                }
            }

            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                LongPressAfter(500) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = grid,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = 80.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (showPrompt) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                DarkroomCard(Modifier.padding(16.dp)) {
                                    PhotoAccessPrompt(access, graph.phoneMedia.permissions, onChanged = vm::permissionChanged, modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                        if (showError) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "Server: ${ui.error}",
                                    style = Darkroom.type.caption,
                                    color = colors.accentText,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }
                        ui.entries.forEach { entry ->
                            when (entry) {
                                is Entry.MonthHeader -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }) {
                                    val ids = selection.ids
                                    MonthHeader(
                                        entry,
                                        selecting = selection.active,
                                        allSelected = entry.items.all { it.id in ids },
                                        onToggleAll = { vm.toggleMonth(entry) },
                                    )
                                }
                                is Entry.DayHeader -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        Format.day(entry.date),
                                        style = Darkroom.type.body,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
                                    )
                                }
                                is Entry.Photo -> item(key = entry.key) {
                                    val item = entry.item
                                    PhotoCell(
                                        item,
                                        notSynced = item.local != null && item.local.key !in ui.syncedKeys,
                                        selecting = selection.active,
                                        selected = item.id in selection.ids,
                                        onClick = { if (selection.active) vm.toggle(item) else onOpen(ui.flat, entry.index) },
                                        onLongClick = { if (selection.active) vm.toggle(item) else vm.startSelection(item) },
                                    )
                                }
                            }
                        }
                        if (ui.loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                                }
                            }
                        }
                        if (ui.entries.isEmpty() && !refreshing && (access != PhotoAccess.None || filter == Filter.Server)) {
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
                val years = remember(ui.entries, lead) {
                    ui.headerIndex.entries.groupBy { it.key.year }.map { (year, months) -> year to lead + months.minOf { it.value } }.sortedBy { it.second }
                }
                FastScroller(
                    grid = grid,
                    labelAt = { index -> ui.entries.getOrNull(index - lead)?.month?.let(monthShort::format) },
                    years = years,
                    onYear = { year -> ui.monthCounts.keys.filter { it.year == year }.maxOrNull()?.let(::jumpTo) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }

            if (selection.active) {
                val selected = vm.selectedItems()
                SelectionActionBar(
                    toSync = selected.count { it.local != null && it.local.key !in ui.syncedKeys },
                    canShare = selected.isNotEmpty(),
                    toSave = if (Build.VERSION.SDK_INT >= 29) selected.count { it.local == null && it.server != null } else 0,
                    onSync = { withNotifications { vm.syncSelected() } },
                    onShare = vm::shareSelected,
                    onSave = vm::saveSelected,
                    onDelete = { showDelete = true },
                )
            }
        }

        snackbar?.let { message ->
            PhotosSnackbar(
                text = message.text,
                onView = if (message.viewSync) ({ snackbar = null; showSheet = true }) else null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showDelete) {
        val items = remember { vm.selectedItems() }
        val onPhone = items.filter { it.local != null }
        DeleteSheet(
            count = items.size,
            onPhone = onPhone.size,
            notOnServer = onPhone.count { it.local!!.key !in ui.syncedKeys },
            serverName = serverName,
            onRemoveFromPhone = {
                showDelete = false
                deleter.delete(onPhone) { removed -> if (removed > 0) vm.deleted(items, removed, everywhere = false) }
            },
            onDeleteEverywhere = {
                showDelete = false
                deleter.delete(onPhone) { removed -> vm.deleted(items, removed, everywhere = true) }
            },
            onDismiss = { showDelete = false },
        )
    }

    if (showJump) {
        JumpToSheet(
            counts = ui.monthCounts,
            current = monthInView,
            onMonth = { showJump = false; jumpTo(it) },
            onToday = { showJump = false; scope.launch { grid.scrollToItem(0) } },
            onDismiss = { showJump = false },
        )
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

/** Makes long presses inside [content] take [millis] instead of the system default. */
@Composable
private fun LongPressAfter(millis: Long, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val config = remember(base) { object : ViewConfiguration by base { override val longPressTimeoutMillis = millis } }
    CompositionLocalProvider(LocalViewConfiguration provides config, content = content)
}

@Composable
private fun Header(
    serverName: String,
    online: Boolean,
    status: String,
    pending: Int,
    running: Boolean,
    onSync: () -> Unit,
    onCalendar: () -> Unit,
) {
    val colors = Darkroom.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Photos", style = Darkroom.type.largeTitle, color = colors.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (online) colors.success else colors.textMuted)
                Spacer(Modifier.width(6.dp))
                Text("$serverName · $status", style = Darkroom.type.caption, color = colors.textSecondary)
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).border(1.dp, colors.border, CircleShape).clickable(onClick = onCalendar),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Jump to date", tint = colors.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        SyncButton(pending = pending, running = running, onClick = onSync)
    }
}

@Composable
private fun SelectionHeader(count: Int, onCancel: () -> Unit) {
    val colors = Darkroom.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = "Cancel selection", tint = colors.text)
        }
        Spacer(Modifier.width(4.dp))
        Text("${Format.count(count)} selected", style = Darkroom.type.title, color = colors.text)
    }
}

@Composable
private fun MonthHeader(header: Entry.MonthHeader, selecting: Boolean, allSelected: Boolean, onToggleAll: () -> Unit) {
    val colors = Darkroom.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monthTitle.format(header.month), style = Darkroom.type.section, color = colors.text, modifier = Modifier.weight(1f))
        if (selecting) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, if (allSelected) colors.accent else colors.border, RoundedCornerShape(50))
                    .clickable(onClick = onToggleAll)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(if (allSelected) "Clear" else "Select all", style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight), color = colors.text)
            }
        } else {
            val status = header.status
            Text(
                when (status) {
                    is MonthStatus.NotSynced -> "${Format.count(status.count)} not synced"
                    MonthStatus.Synced -> "Synced"
                    is MonthStatus.ServerOnly -> "Server · ${status.label}"
                },
                style = Darkroom.type.caption,
                color = if (status is MonthStatus.NotSynced) colors.accentText else colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    item: MediaItem,
    notSynced: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Darkroom.colors
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "selectInset")
    val corner by animateDpAsState(if (selected) 12.dp else 0.dp, label = "selectCorner")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        MediaThumbnail(item, Modifier.padding(inset).clip(RoundedCornerShape(corner)), notSynced = notSynced && !selecting)
        if (selecting) {
            Box(
                Modifier
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(colors.accent)
                        else Modifier.background(Color(0x330E0F11)).border(1.5.dp, Color.White, CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = colors.onAccent, modifier = Modifier.size(15.dp))
            }
        }
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
