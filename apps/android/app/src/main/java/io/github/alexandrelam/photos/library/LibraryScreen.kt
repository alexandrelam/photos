package io.github.alexandrelam.photos.library

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.server.ServerFolder
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.DarkroomCard
import io.github.alexandrelam.photos.shared.ui.DarkroomSwitch
import io.github.alexandrelam.photos.shared.ui.PrimaryButton
import io.github.alexandrelam.photos.shared.ui.SectionLabel
import io.github.alexandrelam.photos.shared.ui.SettingRow

/** Phone albums (choose which to back up) and the server's folders. */
@Composable
fun LibraryScreen(onOpenFolder: (path: String) -> Unit) {
    val graph = LocalAppGraph.current
    val vm = viewModel { LibraryViewModel(graph) }
    val albums by vm.albums.collectAsState()
    val root by vm.root.collectAsState()
    val error by vm.error.collectAsState()
    val hasPermission by vm.hasPermission.collectAsState()
    val colors = Darkroom.colors
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.permissionChanged() }
    LifecycleResumeEffect(Unit) {
        vm.permissionChanged()
        onPauseOrDispose { }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Library", style = Darkroom.type.largeTitle, color = colors.text)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionLabel("On this phone", icon = Icons.Outlined.PhoneAndroid)
                DarkroomCard {
                    if (!hasPermission) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Allow photo access to choose albums to back up.", style = Darkroom.type.caption, color = colors.textSecondary)
                            Spacer(Modifier.height(10.dp))
                            PrimaryButton("Allow", onClick = { permission.launch(graph.phoneMedia.permissions) })
                        }
                    }
                    albums.forEach { row ->
                        val count = Format.photos(row.album.count)
                        SettingRow(
                            title = row.album.name,
                            subtitle = when {
                                !row.backedUp -> "$count · not backed up"
                                row.waiting > 0 -> "$count · ${Format.count(row.waiting)} waiting"
                                else -> "$count · backed up"
                            },
                            subtitleColor = if (!row.backedUp) colors.accentText else null,
                            leading = {
                                AsyncImage(
                                    model = row.album.cover.thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceRaised),
                                )
                            },
                        ) {
                            DarkroomSwitch(row.backedUp, onCheckedChange = { vm.setBackedUp(row.album, it) })
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val folders = root?.folders
            SectionLabel(
                "On your server",
                icon = Icons.Outlined.Dns,
                trailing = folders?.let { "${it.size} ${if (it.size == 1) "folder" else "folders"}" },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (error != null && root == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(error!!, style = Darkroom.type.caption, color = colors.accentText)
            }
        }
        root?.let { page ->
            page.folders.forEach { folder ->
                item(key = folder.path) { FolderCard(folder, onClick = { onOpenFolder(folder.path) }) }
            }
            if (page.total > 0) {
                item(key = "__root") {
                    FolderCard(
                        ServerFolder("", "Top level", page.total, null, null, page.items.map { it.thumbnail }),
                        onClick = { onOpenFolder("") },
                    )
                }
            }
        }
    }
}

/** A server folder as a 2×2-ish mosaic of recent pictures with name and count. */
@Composable
internal fun FolderCard(folder: ServerFolder, onClick: () -> Unit) {
    val colors = Darkroom.colors
    Column(Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(colors.surfaceRaised),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val covers = folder.covers
            Cover(covers.getOrNull(0), Modifier.weight(1f).fillMaxHeight())
            if (covers.size > 1) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Cover(covers.getOrNull(1), Modifier.weight(1f).fillMaxWidth())
                    Cover(covers.getOrNull(2) ?: covers.getOrNull(3), Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(folder.name, style = Darkroom.type.body, color = colors.text, maxLines = 1)
        val years = Format.yearRange(folder.oldest, folder.newest)
        Text(
            Format.photos(folder.count) + (years?.takeIf { it.contains('–') }?.let { " · $it" } ?: ""),
            style = Darkroom.type.caption,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun Cover(model: Any?, modifier: Modifier) {
    Box(modifier.background(Darkroom.colors.surfaceRaised)) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
