package io.github.alexandrelam.photos

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.alexandrelam.photos.connect.ConnectScreen
import io.github.alexandrelam.photos.library.FolderScreen
import io.github.alexandrelam.photos.library.LibraryScreen
import io.github.alexandrelam.photos.photos.PhotosScreen
import io.github.alexandrelam.photos.settings.SettingsScreen
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.viewer.ViewerScreen
import io.github.alexandrelam.photos.viewer.ViewerSession

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Photos("photos", "Photos", Icons.Outlined.GridView),
    Library("library", "Library", Icons.Outlined.Folder),
    Settings("settings", "Settings", Icons.Outlined.Tune),
}

@Composable
fun AppNavigation() {
    val graph = LocalAppGraph.current
    val nav = rememberNavController()
    val start = remember { if (graph.server.settings.value == null) "connect" else Tab.Photos.route }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // The Photos tab swaps the tabs for its own actions while photos are selected.
    var selecting by remember { mutableStateOf(false) }
    val showTabs = Tab.entries.any { it.route == route } && !(route == Tab.Photos.route && selecting)

    fun openViewer() = nav.navigate("viewer")
    fun openFolder(path: String) = nav.navigate("folder?path=${Uri.encode(path)}")

    Column(Modifier.fillMaxSize().background(Darkroom.colors.background)) {
        NavHost(nav, startDestination = start, modifier = Modifier.weight(1f)) {
            composable("connect") {
                // Opened from Settings to edit, or as the first screen on a fresh install.
                val editing = nav.previousBackStackEntry != null
                val back: (() -> Unit)? = if (editing) { { nav.popBackStack() } } else null
                ConnectScreen(
                    onConnected = {
                        if (editing) nav.popBackStack()
                        else nav.navigate(Tab.Photos.route) { popUpTo("connect") { inclusive = true } }
                    },
                    onBack = back,
                )
            }
            composable(Tab.Photos.route) {
                PhotosScreen(
                    onOpen = { items, index -> ViewerSession.open(items, index); openViewer() },
                    onSelectingChange = { selecting = it },
                )
            }
            composable(Tab.Library.route) {
                LibraryScreen(onOpenFolder = ::openFolder)
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    onEditServer = { nav.navigate("connect") },
                    onChooseFolders = { nav.switchTab(Tab.Library) },
                )
            }
            composable(
                "folder?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                FolderScreen(
                    path = backStack.arguments?.getString("path").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenFolder = ::openFolder,
                    onOpen = { items, index -> ViewerSession.open(items, index); openViewer() },
                )
            }
            composable("viewer") {
                ViewerScreen(onBack = { nav.popBackStack() })
            }
        }
        if (showTabs) BottomBar(current = route, onSelect = { nav.switchTab(it) })
    }
}

private fun NavHostController.switchTab(tab: Tab) = navigate(tab.route) {
    popUpTo(Tab.Photos.route) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(current: String?, onSelect: (Tab) -> Unit) {
    val colors = Darkroom.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab.route == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.accentSoft else colors.background)
                        .padding(horizontal = 18.dp, vertical = 5.dp),
                ) {
                    Icon(tab.icon, contentDescription = null, tint = if (selected) colors.accentText else colors.textSecondary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    tab.label,
                    style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight),
                    color = if (selected) colors.text else colors.textSecondary,
                )
            }
        }
    }
}
