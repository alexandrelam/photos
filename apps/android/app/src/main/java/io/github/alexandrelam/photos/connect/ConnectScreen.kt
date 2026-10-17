package io.github.alexandrelam.photos.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.alexandrelam.photos.LocalAppGraph
import io.github.alexandrelam.photos.server.Route
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.LabeledField
import io.github.alexandrelam.photos.shared.ui.OutlineButton
import io.github.alexandrelam.photos.shared.ui.PrimaryButton
import kotlinx.coroutines.launch

/** First-run (and "Edit server") screen: addresses and access key, test, connect. */
@Composable
fun ConnectScreen(onConnected: () -> Unit, onBack: (() -> Unit)?) {
    val server = LocalAppGraph.current.server
    val colors = Darkroom.colors
    val saved = remember { server.settings.value }
    var home by rememberSaveable { mutableStateOf(saved?.homeUrl.orEmpty()) }
    var away by rememberSaveable { mutableStateOf(saved?.awayUrl.orEmpty()) }
    var key by rememberSaveable { mutableStateOf(saved?.accessKey.orEmpty()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PhotoCamera, null, tint = colors.onAccent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Connect your server", style = Darkroom.type.largeTitle, color = colors.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your photos stay at home. Point Darkroom at the machine that stores them.",
                style = Darkroom.type.bodyRegular,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(28.dp))
            LabeledField(
                label = "Home address",
                value = home,
                onValueChange = { home = it; result = null },
                placeholder = "http://192.168.1.20:8080",
                help = "Used when you are on your home Wi‑Fi. Fastest for syncing.",
            )
            Spacer(Modifier.height(18.dp))
            LabeledField(
                label = "Away address",
                labelSuffix = "optional",
                value = away,
                onValueChange = { away = it; result = null },
                placeholder = "http://nas.tail1234.ts.net:8080",
                help = "Your server's Tailscale name. Darkroom tries home first, then this one.",
            )
            Spacer(Modifier.height(18.dp))
            LabeledField(
                label = "Access key",
                value = key,
                onValueChange = { key = it; result = null },
                placeholder = "Paste the key from your server",
                secret = true,
                keyboardType = KeyboardType.Password,
                help = "The server prints it when it starts.",
            )
            result?.let { (ok, message) ->
                Spacer(Modifier.height(18.dp))
                Text(message, style = Darkroom.type.body, color = if (ok) colors.success else colors.accentText)
            }
            Spacer(Modifier.height(24.dp))
        }

        val ready = (home.isNotBlank() || away.isNotBlank()) && key.isNotBlank()
        OutlineButton(
            text = "Test connection",
            enabled = ready && busy == null,
            loading = busy == "test",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = "test"
                scope.launch {
                    result = runCatching { server.test(home, away, key) }.fold(
                        { (name, route) -> true to "Connected to $name via ${if (route == Route.Home) "home address" else "away address"}" },
                        { false to (it.message ?: "Couldn't connect") },
                    )
                    busy = null
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = "Connect",
            enabled = ready && busy == null,
            loading = busy == "connect",
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            onClick = {
                busy = "connect"
                scope.launch {
                    runCatching { server.connect(home, away, key) }
                        .onSuccess { onConnected() }
                        .onFailure { result = false to (it.message ?: "Couldn't connect") }
                    busy = null
                }
            },
        )
    }
}
