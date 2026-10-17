package io.github.alexandrelam.photos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.media.PhoneMedia
import io.github.alexandrelam.photos.server.Connection
import io.github.alexandrelam.photos.server.Route
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.DarkroomCard
import io.github.alexandrelam.photos.shared.ui.DarkroomSwitch
import io.github.alexandrelam.photos.shared.ui.OutlineButton
import io.github.alexandrelam.photos.shared.ui.SectionLabel
import io.github.alexandrelam.photos.shared.ui.SettingRow
import io.github.alexandrelam.photos.shared.ui.StatusDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onEditServer: () -> Unit, onChooseFolders: () -> Unit) {
    val graph = LocalAppGraph.current
    val settings by graph.server.settings.collectAsState()
    val connection by graph.server.connection.collectAsState()
    val prefs by graph.sync.prefs.collectAsState()
    val colors = Darkroom.colors
    val scope = rememberCoroutineScope()
    val diskCache = graph.imageLoader.diskCache
    var cacheSize by remember { mutableLongStateOf(diskCache?.size ?: 0L) }
    var rescanStatus by remember { mutableStateOf<String?>(null) }
    val backedUpNames by produceState("…", prefs) {
        value = PhoneMedia.albums(graph.phoneMedia.all())
            .filter { graph.sync.isBackedUp(it) }
            .joinToString(", ") { it.name }
            .ifEmpty { "None" }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Settings", style = Darkroom.type.largeTitle, color = colors.text)
        Spacer(Modifier.height(12.dp))

        SectionLabel("Server")
        DarkroomCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Dns, null, tint = colors.text)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(settings?.name?.ifEmpty { null } ?: "Not connected", style = Darkroom.type.section, color = colors.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (text, color) = when (val c = connection) {
                            is Connection.Online -> (if (c.route == Route.Home) "Connected via home Wi‑Fi" else "Connected via away address") to colors.success
                            is Connection.Offline -> c.reason to colors.accentText
                            Connection.Checking -> "Checking…" to colors.textSecondary
                            Connection.NotConfigured -> "Not set up" to colors.textSecondary
                        }
                        StatusDot(color)
                        Spacer(Modifier.width(6.dp))
                        Text(text, style = Darkroom.type.caption, color = color)
                    }
                }
                OutlineButton("Edit", onClick = onEditServer, compact = true)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.surfaceRaised)
            KeyValue("Home", settings?.homeUrl?.removePrefix("http://")?.ifEmpty { null } ?: "—")
            KeyValue("Away (Tailscale)", settings?.awayUrl?.removePrefix("http://")?.ifEmpty { null } ?: "—")
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Sync")
        DarkroomCard {
            SettingRow("Sync on Wi‑Fi only", "Never use mobile data, even through Tailscale.") {
                DarkroomSwitch(prefs.wifiOnly, graph.sync::setWifiOnly)
            }
            SettingRow("Only while charging", "Good for big first backups.") {
                DarkroomSwitch(prefs.whileCharging, graph.sync::setWhileCharging)
            }
            SettingRow("Manual sync", "Nothing uploads until you tap Sync.")
            SettingRow("Folders to back up", backedUpNames, onClick = onChooseFolders) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Storage")
        DarkroomCard {
            SettingRow("Thumbnail cache", "${Format.bytes(cacheSize)} on this phone") {
                OutlineButton("Clear", compact = true, onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { diskCache?.clear() }
                        graph.imageLoader.memoryCache?.clear()
                        cacheSize = diskCache?.size ?: 0L
                    }
                })
            }
            SettingRow("Rescan server folders", rescanStatus ?: "Pick up files added on the server.") {
                OutlineButton("Rescan", compact = true, onClick = {
                    rescanStatus = "Scanning…"
                    scope.launch {
                        rescanStatus = runCatching { graph.server.rescan() }.fold({ "Done" }, { it.message ?: "Failed" })
                    }
                })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    val colors = Darkroom.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = Darkroom.type.caption.copy(fontSize = Darkroom.type.bodyRegular.fontSize), color = colors.textSecondary)
        Text(value, style = Darkroom.type.body, color = colors.text)
    }
}
