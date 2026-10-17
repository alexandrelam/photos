package io.github.alexandrelam.photos.photos

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexandrelam.photos.AppGraph
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.media.PhotoAccess
import io.github.alexandrelam.photos.server.Connection
import io.github.alexandrelam.photos.server.ServerMonth
import io.github.alexandrelam.photos.shared.format.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class Filter(val label: String) { All("All"), Phone("On this phone"), Server("Server") }

internal sealed interface MonthStatus {
    data class NotSynced(val count: Int) : MonthStatus
    data object Synced : MonthStatus
    /** Only server photos; [label] names where they live, e.g. "Japan 2019". */
    data class ServerOnly(val label: String) : MonthStatus
}

/** One cell or full-width row of the timeline grid. */
internal sealed interface Entry {
    val key: String
    val month: YearMonth

    data class MonthHeader(override val month: YearMonth, val status: MonthStatus, val items: List<MediaItem>) : Entry {
        override val key get() = "m-$month"
    }

    data class DayHeader(override val month: YearMonth, val date: LocalDate) : Entry {
        override val key get() = "d-$date"
    }

    /** [index] is the photo's position in [PhotosUi.flat]. */
    data class Photo(override val month: YearMonth, val item: MediaItem, val index: Int) : Entry {
        override val key get() = item.id
    }
}

internal data class PhotosUi(
    val entries: List<Entry> = emptyList(),
    /** Every item shown, in order; what the viewer pages through. */
    val flat: List<MediaItem> = emptyList(),
    /** Where each month header sits in [entries]. */
    val headerIndex: Map<YearMonth, Int> = emptyMap(),
    /** Photo count per month for the whole timeline, newest first, not only what is loaded. */
    val monthCounts: Map<YearMonth, Int> = emptyMap(),
    val syncedKeys: Set<String> = emptySet(),
    val pending: Int = 0,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

internal data class Selection(val active: Boolean = false, val ids: Set<String> = emptySet())

internal sealed interface PhotosEvent {
    /** A short confirmation; [viewSync] adds a "View" link to the sync progress. */
    data class Message(val text: String, val viewSync: Boolean = false) : PhotosEvent
    data class Launch(val intent: Intent) : PhotosEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class PhotosViewModel(private val graph: AppGraph) : ViewModel() {
    private val permission = MutableStateFlow(graph.phoneMedia.access())
    private val serverItems = MutableStateFlow<List<MediaItem>>(emptyList())
    private val serverMonths = MutableStateFlow<List<ServerMonth>?>(null)
    private val nextCursor = MutableStateFlow<String?>(null)
    private val loadingMore = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val paging = Mutex()
    private val _events = MutableSharedFlow<PhotosEvent>(extraBufferCapacity = 4)
    val filter = MutableStateFlow(Filter.All)
    val refreshing = MutableStateFlow(false)
    val access: StateFlow<PhotoAccess> = permission
    val selection = MutableStateFlow(Selection())
    val events: SharedFlow<PhotosEvent> = _events.asSharedFlow()

    private val local: StateFlow<List<MediaItem>> = permission
        .map { it != PhotoAccess.None }
        .distinctUntilChanged()
        .flatMapLatest { granted -> if (granted) graph.phoneMedia.observe() else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val server = combine(serverItems, nextCursor, loadingMore, error, serverMonths) { items, next, loading, err, months ->
        ServerState(items, next, loading, err, months)
    }

    val ui: StateFlow<PhotosUi> = combine(local, server, filter, graph.sync.syncedKeys, graph.sync.prefs) { local, server, filter, synced, _ ->
        build(local, server, filter, synced)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotosUi())

    init {
        refresh()
        // Re-check sync badges whenever the phone's gallery changes.
        viewModelScope.launch {
            local.collect { items -> if (items.isNotEmpty()) runCatching { graph.sync.refreshStatus(items) } }
        }
    }

    fun permissionChanged() {
        permission.value = graph.phoneMedia.access()
    }

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        graph.server.refreshConnection()
        paging.withLock {
            runCatching { graph.server.timeline() }
                .onSuccess {
                    serverItems.value = it.items
                    nextCursor.value = it.next
                    error.value = null
                }
                .onFailure { error.value = it.message }
        }
        refreshMonths()
        runCatching { graph.sync.refreshStatus(local.value) }
        refreshing.value = false
    }

    private suspend fun refreshMonths() {
        runCatching { graph.server.months(graph.sync.deviceId) }.onSuccess { serverMonths.value = it }
    }

    fun loadMore() {
        if (nextCursor.value == null || loadingMore.value) return
        viewModelScope.launch { loadPage() }
    }

    fun hasMore() = nextCursor.value != null

    /** Loads older server pages until [month] is in the timeline (or the server runs out). */
    suspend fun ensureLoaded(month: YearMonth) {
        val start = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (filter.value == Filter.Phone) return
        var loaded = false
        while (nextCursor.value != null && (serverItems.value.lastOrNull()?.takenAt ?: Long.MAX_VALUE) >= start) {
            if (!loadPage(limit = 500)) break
            loaded = true
        }
        // Let the timeline rebuild with what was just loaded.
        if (loaded) withTimeoutOrNull(2_000) { ui.first { it.headerIndex.keys.any { m -> m <= month } } }
    }

    private suspend fun loadPage(limit: Int = 300): Boolean = paging.withLock {
        val cursor = nextCursor.value ?: return@withLock false
        loadingMore.value = true
        val ok = runCatching { graph.server.timeline(cursor, limit) }
            .onSuccess {
                serverItems.value = serverItems.value + it.items
                nextCursor.value = it.next
            }
            .onFailure { error.value = it.message }
            .isSuccess
        loadingMore.value = false
        ok
    }

    // Selection

    fun startSelection(item: MediaItem) {
        selection.value = Selection(active = true, ids = setOf(item.id))
    }

    fun toggle(item: MediaItem) {
        val ids = selection.value.ids.let { if (item.id in it) it - item.id else it + item.id }
        selection.value = if (ids.isEmpty()) Selection() else Selection(active = true, ids = ids)
    }

    /** Selects every photo of the month, or clears them if all were already selected. */
    fun toggleMonth(header: Entry.MonthHeader) {
        val monthIds = header.items.map { it.id }
        val ids = selection.value.ids
        val next = if (ids.containsAll(monthIds)) ids - monthIds.toSet() else ids + monthIds
        selection.value = if (next.isEmpty()) Selection() else Selection(active = true, ids = next)
    }

    fun clearSelection() {
        selection.value = Selection()
    }

    fun selectedItems(): List<MediaItem> {
        val ids = selection.value.ids
        return ui.value.flat.filter { it.id in ids }
    }

    // Actions on the selection. Each one closes the selection and confirms with a message.

    private val serverName: String
        get() = (graph.server.connection.value as? Connection.Online)?.name ?: graph.server.settings.value?.name ?: "server"

    fun syncSelected() {
        val count = graph.sync.startSome(selectedItems())
        clearSelection()
        _events.tryEmit(
            if (count > 0) PhotosEvent.Message("Syncing ${Format.photos(count)} to $serverName", viewSync = true)
            else PhotosEvent.Message("Already on $serverName"),
        )
    }

    fun shareSelected() {
        val items = selectedItems()
        clearSelection()
        viewModelScope.launch {
            runCatching { graph.actions.shareIntent(items) }
                .onSuccess { _events.emit(PhotosEvent.Launch(it)) }
                .onFailure { _events.emit(PhotosEvent.Message(it.message ?: "Couldn't share")) }
        }
    }

    fun saveSelected() {
        val items = selectedItems()
        clearSelection()
        viewModelScope.launch {
            _events.emit(PhotosEvent.Message("Saving ${Format.photos(items.count { it.local == null })} to this phone…"))
            val message = runCatching { graph.actions.saveToPhone(items) }
                .fold({ "Saved ${Format.photos(it)} to Pictures/Darkroom" }, { it.message ?: "Couldn't save" })
            _events.emit(PhotosEvent.Message(message))
        }
    }

    /** Called once the phone copies are gone; also deletes server copies when [everywhere]. */
    fun deleted(items: List<MediaItem>, removedFromPhone: Int, everywhere: Boolean) {
        clearSelection()
        viewModelScope.launch {
            if (!everywhere) {
                _events.emit(PhotosEvent.Message("Removed ${Format.photos(removedFromPhone)} from this phone"))
                return@launch
            }
            // If the phone copies were kept (the user said no), keep the server copies too.
            val phoneItems = items.filter { it.local != null }
            if (phoneItems.isNotEmpty() && removedFromPhone == 0) return@launch
            val message = runCatching { graph.actions.deleteFromServer(items) }
                .fold(
                    {
                        val ids = items.mapNotNullTo(HashSet()) { it.server?.id }
                        val keys = items.mapNotNullTo(HashSet()) { it.local?.key }
                        serverItems.value = serverItems.value.filter { it.server?.id !in ids && it.server?.sourceKey !in keys }
                        refreshMonths()
                        "Deleted ${Format.photos(items.size)}"
                    },
                    { "Couldn't delete from $serverName: ${it.message}" },
                )
            _events.emit(PhotosEvent.Message(message))
        }
    }

    private data class ServerState(
        val items: List<MediaItem>,
        val next: String?,
        val loading: Boolean,
        val error: String?,
        val months: List<ServerMonth>?,
    )

    private fun build(local: List<MediaItem>, server: ServerState, filter: Filter, synced: Set<String>): PhotosUi {
        val localKeys = local.mapNotNullTo(HashSet()) { it.local?.key }
        var items = when (filter) {
            Filter.Phone -> local
            Filter.Server -> server.items
            Filter.All -> (local + server.items.filter { it.server?.sourceKey !in localKeys }).sortedByDescending { it.takenAt }
        }
        // While the server has older pages, stop at its oldest loaded item so phone photos don't jump around.
        if (filter == Filter.All && server.next != null && server.items.isNotEmpty()) {
            val oldest = server.items.last().takenAt
            items = items.filter { it.takenAt >= oldest }
        }

        val thisMonth = YearMonth.now()
        val entries = ArrayList<Entry>(items.size + 64)
        val headerIndex = HashMap<YearMonth, Int>()
        var index = 0
        items.groupBy { YearMonth.from(Format.localDate(it.takenAt)) }.forEach { (month, list) ->
            headerIndex[month] = entries.size
            entries += Entry.MonthHeader(month, monthStatus(list, synced), list)
            if (month == thisMonth) {
                list.groupBy { Format.localDate(it.takenAt) }.forEach { (date, day) ->
                    entries += Entry.DayHeader(month, date)
                    day.forEach { entries += Entry.Photo(month, it, index++) }
                }
            } else {
                list.forEach { entries += Entry.Photo(month, it, index++) }
            }
        }

        return PhotosUi(
            entries = entries,
            flat = items,
            headerIndex = headerIndex,
            monthCounts = monthCounts(local, server, filter),
            syncedKeys = synced,
            pending = graph.sync.pending(local).size,
            loadingMore = server.loading,
            error = server.error,
        )
    }

    private fun monthStatus(items: List<MediaItem>, synced: Set<String>): MonthStatus {
        val notSynced = items.count { it.local != null && it.local.key !in synced }
        return when {
            notSynced > 0 -> MonthStatus.NotSynced(notSynced)
            items.all { it.local == null } -> MonthStatus.ServerOnly(placeName(items))
            else -> MonthStatus.Synced
        }
    }

    /** "Phone backup" for uploaded photos (`<upload>/<album>/<yyyy>/<mm>`), else the most common folder's name. */
    private fun placeName(items: List<MediaItem>): String {
        val folder = items.mapNotNull { it.server?.folder }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return "Library"
        if (items.any { it.server?.sourceKey != null } || Regex(".+/\\d{4}/\\d{2}$").matches(folder)) return "Phone backup"
        return folder.substringAfterLast('/').ifEmpty { "Library" }
    }

    /** Counts for the whole timeline when the server answered, otherwise for what is loaded. */
    private fun monthCounts(local: List<MediaItem>, server: ServerState, filter: Filter): Map<YearMonth, Int> {
        fun count(items: List<MediaItem>) = items.groupingBy { YearMonth.from(Format.localDate(it.takenAt)) }.eachCount()
        val phone = count(local)
        val remote = server.months?.associate { YearMonth.parse(it.month) to it }
        val counts = when (filter) {
            Filter.Phone -> phone
            Filter.Server -> remote?.mapValues { it.value.count } ?: count(server.items)
            Filter.All -> if (remote == null) {
                count(server.items.filter { it.server?.sourceKey == null }).let { s -> (phone.keys + s.keys).associateWith { (phone[it] ?: 0) + (s[it] ?: 0) } }
            } else {
                (phone.keys + remote.keys).associateWith { m ->
                    (phone[m] ?: 0) + ((remote[m]?.count ?: 0) - (remote[m]?.mine ?: 0)).coerceAtLeast(0)
                }
            }
        }
        return counts.filterValues { it > 0 }.toSortedMap(compareByDescending { it })
    }
}
