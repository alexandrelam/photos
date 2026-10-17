package io.github.alexandrelam.photos.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexandrelam.photos.AppGraph
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.media.PhoneAlbum
import io.github.alexandrelam.photos.media.PhoneMedia
import io.github.alexandrelam.photos.server.FolderPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class AlbumRow(val album: PhoneAlbum, val backedUp: Boolean, val waiting: Int)

@OptIn(ExperimentalCoroutinesApi::class)
internal class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    private val permission = MutableStateFlow(graph.phoneMedia.hasPermission())
    private val local = permission
        .flatMapLatest { if (it) graph.phoneMedia.observe() else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<MediaItem>())

    val albums: StateFlow<List<AlbumRow>> = combine(local, graph.sync.prefs, graph.sync.syncedKeys) { items, _, synced ->
        PhoneMedia.albums(items).map { album ->
            val backedUp = graph.sync.isBackedUp(album)
            val waiting = if (backedUp) items.count { it.local != null && it.local.albumId == album.id && it.local.key !in synced } else 0
            AlbumRow(album, backedUp, waiting)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val root = MutableStateFlow<FolderPage?>(null)
    val error = MutableStateFlow<String?>(null)
    val hasPermission: StateFlow<Boolean> = permission

    init {
        refresh()
    }

    fun permissionChanged() {
        permission.value = graph.phoneMedia.hasPermission()
    }

    fun refresh() = viewModelScope.launch {
        runCatching { graph.server.folder("", limit = 1) }
            .onSuccess { root.value = it; error.value = null }
            .onFailure { error.value = it.message }
    }

    fun setBackedUp(album: PhoneAlbum, on: Boolean) =
        graph.sync.setBackedUp(album, on, albums.value.map { it.album })
}

internal class FolderViewModel(private val graph: AppGraph, val path: String) : ViewModel() {
    val page = MutableStateFlow<FolderPage?>(null)
    val error = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        loading.value = true
        runCatching { graph.server.folder(path) }
            .onSuccess { page.value = it; error.value = null }
            .onFailure { error.value = it.message }
        loading.value = false
    }

    fun loadMore() {
        val current = page.value ?: return
        if (loading.value || current.items.size >= current.total) return
        loading.value = true
        viewModelScope.launch {
            runCatching { graph.server.folder(path, offset = current.items.size) }
                .onSuccess { next -> page.value = current.copy(items = current.items + next.items) }
                .onFailure { error.value = it.message }
            loading.value = false
        }
    }
}
