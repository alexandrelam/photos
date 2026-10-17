package io.github.alexandrelam.photos.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.media.MediaThumbnail
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom

/** One server folder: its subfolders, then its pictures. */
@Composable
fun FolderScreen(
    path: String,
    onBack: () -> Unit,
    onOpenFolder: (path: String) -> Unit,
    onOpen: (items: List<MediaItem>, index: Int) -> Unit,
) {
    val graph = LocalAppGraph.current
    val vm = viewModel(key = "folder:$path") { FolderViewModel(graph, path) }
    val page by vm.page.collectAsState()
    val error by vm.error.collectAsState()
    val loading by vm.loading.collectAsState()
    val colors = Darkroom.colors

    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 20 }
    }
    LaunchedEffect(nearEnd, page?.items?.size) { if (nearEnd) vm.loadMore() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text) }
            Column(Modifier.weight(1f)) {
                Text(page?.name ?: path.substringAfterLast('/'), style = Darkroom.type.title, color = colors.text, maxLines = 1)
                if (path.isNotEmpty()) Text("/$path", style = Darkroom.type.caption, color = colors.textSecondary, maxLines = 1)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(error!!, style = Darkroom.type.caption, color = colors.accentText, modifier = Modifier.padding(20.dp))
                }
            }
            val current = page
            if (current != null) {
                current.folders.forEach { folder ->
                    item(key = "f:${folder.path}", span = { GridItemSpan(2) }) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            FolderCard(folder, onClick = { onOpenFolder(folder.path) })
                        }
                    }
                }
                if (current.total > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            Format.photos(current.total),
                            style = Darkroom.type.section,
                            color = colors.text,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 10.dp),
                        )
                    }
                }
                items(current.items.size, key = { current.items[it].id }) { i ->
                    MediaThumbnail(current.items[i], onClick = { onOpen(current.items, i) })
                }
                if (current.folders.isEmpty() && current.total == 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("This folder is empty.", style = Darkroom.type.bodyRegular, color = colors.textSecondary, modifier = Modifier.padding(20.dp))
                    }
                }
            }
            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}
