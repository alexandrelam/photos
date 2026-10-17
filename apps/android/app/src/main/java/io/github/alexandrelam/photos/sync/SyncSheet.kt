package io.github.alexandrelam.photos.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.alexandrelam.photos.server.Connection
import io.github.alexandrelam.photos.server.Route
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.OutlineButton
import io.github.alexandrelam.photos.shared.ui.PrimaryButton

/** The bottom sheet that shows a running sync, with Pause/Resume and Hide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSheet(state: SyncState, connection: Connection, onPause: () -> Unit, onResume: () -> Unit, onHide: () -> Unit) {
    val colors = Darkroom.colors
    val serverName = (connection as? Connection.Online)?.name ?: "server"
    ModalBottomSheet(
        onDismissRequest = onHide,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                when (state) {
                    is SyncState.Running -> "Syncing to $serverName"
                    is SyncState.Waiting -> "Sync queued"
                    SyncState.Paused -> "Sync paused"
                    is SyncState.Done -> "Sync complete"
                    is SyncState.Failed -> "Sync stopped"
                    SyncState.Idle -> "Sync"
                },
                style = Darkroom.type.title,
                color = colors.text,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                val route = when ((connection as? Connection.Online)?.route) {
                    Route.Home -> "Home Wi‑Fi"
                    Route.Away -> "Away"
                    null -> "Offline"
                }
                Text("$route → $serverName", style = Darkroom.type.caption, color = colors.textSecondary)
            }
            Spacer(Modifier.height(20.dp))

            val progress = (state as? SyncState.Running)?.progress
            when {
                progress != null -> ProgressBlock(progress)
                state is SyncState.Waiting -> Message(state.reason)
                state is SyncState.Done -> Message(if (state.uploaded == 0) "Everything was already on your server." else "Uploaded ${Format.photos(state.uploaded)}.")
                state is SyncState.Failed -> Message(state.message)
                state == SyncState.Paused -> Message("Already uploaded files stay on your server. Resume to send the rest.")
                else -> Message("Checking what your server already has…")
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val running = state is SyncState.Running || state is SyncState.Waiting
                OutlineButton(
                    text = if (running) "Pause" else "Resume",
                    onClick = if (running) onPause else onResume,
                    enabled = running || state == SyncState.Paused || state is SyncState.Failed,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton("Hide", onClick = onHide, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Syncing keeps going if you close the app.",
                style = Darkroom.type.caption,
                color = colors.textMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun ProgressBlock(p: SyncProgress) {
    val colors = Darkroom.colors
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            buildAnnotatedString {
                append("${p.done}")
                withStyle(SpanStyle(fontSize = 18.sp, color = colors.textSecondary)) { append(" / ${p.total}") }
            },
            style = Darkroom.type.largeTitle.copy(fontSize = 38.sp),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        val left = if (p.bytesPerSecond > 0) (p.bytesTotal - p.bytesDone) / p.bytesPerSecond else null
        Text(
            "${Format.bytes(p.bytesDone)} of ${Format.bytes(p.bytesTotal)}" + (left?.let { " · ~${seconds(it)} left" } ?: ""),
            style = Darkroom.type.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(
        progress = { if (p.bytesTotal > 0) (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = colors.accent,
        trackColor = colors.surfaceRaised,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
    Spacer(Modifier.height(16.dp))
    p.recent.forEach { file ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = file.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceRaised),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = Darkroom.type.body, color = colors.text, maxLines = 1)
                Text(Format.bytes(file.size), style = Darkroom.type.caption, color = colors.textSecondary)
            }
            Text(
                if (file.done) "Done" else "Uploading",
                style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight),
                color = if (file.done) colors.success else colors.accentText,
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = Darkroom.type.bodyRegular, color = Darkroom.colors.textSecondary)
}

private fun seconds(s: Long) = when {
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}
