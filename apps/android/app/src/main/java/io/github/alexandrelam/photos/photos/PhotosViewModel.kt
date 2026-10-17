package io.github.alexandrelam.photos.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexandrelam.photos.AppGraph
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.shared.format.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

internal enum class Filter(val label: String) { All("All"), Phone("On this phone"), Server("Server") }

internal enum class SectionStatus { NotSynced, Synced, ServerOnly }

internal data class Section(val date: LocalDate, val status: SectionStatus, val notSynced: Int, val items: List<MediaItem>)

internal data class PhotosUi(
    val sections: List<Section> = emptyList(),
    /** Every item shown, in order; what the viewer pages through. */
    val flat: List<MediaItem> = emptyList(),
    val syncedKeys: Set<String> = emptySet(),
    val pending: Int = 0,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class PhotosViewModel(private val graph: AppGraph) : ViewModel() {
    private val permission = MutableStateFlow(graph.phoneMedia.hasPermission())
    private val serverItems = MutableStateFlow<List<MediaItem>>(emptyList())
    private val nextCursor = MutableStateFlow<String?>(null)
    private val loadingMore = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val filter = MutableStateFlow(Filter.All)
    val refreshing = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = permission

    private val local: StateFlow<List<MediaItem>> = permission
        .flatMapLatest { granted -> if (granted) graph.phoneMedia.observe() else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val server = combine(serverItems, nextCursor, loadingMore, error) { items, next, loading, err ->
        ServerState(items, next, loading, err)
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
        permission.value = graph.phoneMedia.hasPermission()
    }

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        graph.server.refreshConnection()
        runCatching { graph.server.timeline() }
            .onSuccess {
                serverItems.value = it.items
                nextCursor.value = it.next
                error.value = null
            }
            .onFailure { error.value = it.message }
        runCatching { graph.sync.refreshStatus(local.value) }
        refreshing.value = false
    }

    fun loadMore() {
        val cursor = nextCursor.value ?: return
        if (loadingMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            runCatching { graph.server.timeline(cursor) }
                .onSuccess {
                    serverItems.value = serverItems.value + it.items
                    nextCursor.value = it.next
                }
                .onFailure { error.value = it.message }
            loadingMore.value = false
        }
    }

    private data class ServerState(val items: List<MediaItem>, val next: String?, val loading: Boolean, val error: String?)

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
        val sections = items.groupBy { Format.localDate(it.takenAt) }.map { (date, list) ->
            val notSynced = list.count { it.local != null && it.local.key !in synced }
            val status = when {
                notSynced > 0 -> SectionStatus.NotSynced
                list.all { it.local == null } -> SectionStatus.ServerOnly
                else -> SectionStatus.Synced
            }
            Section(date, status, notSynced, list)
        }
        return PhotosUi(
            sections = sections,
            flat = sections.flatMap { it.items },
            syncedKeys = synced,
            pending = graph.sync.pending(local).size,
            loadingMore = server.loading,
            error = server.error,
        )
    }

    fun hasMore() = nextCursor.value != null
}
