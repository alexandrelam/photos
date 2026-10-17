package io.github.alexandrelam.photos.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.OutlineButton

/** Replaces the bottom tabs while photos are selected. */
@Composable
internal fun SelectionActionBar(
    toSync: Int,
    canShare: Boolean,
    toSave: Int,
    onSync: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Darkroom.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = toSync > 0, onClick = onSync).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (toSync > 0) colors.accent else colors.surfaceRaised)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (toSync > 0) colors.onAccent else colors.textMuted
                Icon(Icons.Outlined.CloudUpload, null, tint = tint, modifier = Modifier.size(18.dp))
                if (toSync > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(Format.count(toSync), style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight), color = tint)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(if (toSync > 0) "Sync ${Format.count(toSync)}" else "Synced", style = Darkroom.type.caption, color = if (toSync > 0) colors.text else colors.textMuted)
        }
        BarAction(Icons.Outlined.Share, "Share", enabled = canShare, onClick = onShare)
        BarAction(Icons.Outlined.Download, "Save to phone", enabled = toSave > 0, onClick = onSave)
        BarAction(Icons.Outlined.DeleteOutline, "Delete", tint = colors.danger, onClick = onDelete)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Darkroom.colors.text, onClick: () -> Unit) {
    val color = if (enabled) tint else Darkroom.colors.textMuted.copy(alpha = 0.6f)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = Darkroom.type.caption, color = color)
    }
}

/**
 * "Delete N photos?": remove only the phone copies, or delete everywhere.
 * [notOnServer] is how many phone photos would be lost for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteSheet(
    count: Int,
    onPhone: Int,
    notOnServer: Int,
    serverName: String,
    onRemoveFromPhone: () -> Unit,
    onDeleteEverywhere: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Darkroom.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Delete ${Format.photos(count)}?", style = Darkroom.type.title, color = colors.text)
            Spacer(Modifier.height(16.dp))
            if (onPhone > 0) {
                DeleteOption(
                    title = "Remove from this phone",
                    titleColor = colors.text,
                    subtitle = if (notOnServer > 0) {
                        "${Format.count(notOnServer)} of these ${if (notOnServer == 1) "is" else "are"} not on your server yet. " +
                            "${if (notOnServer == 1) "It" else "They"} will be gone for good."
                    } else {
                        "${if (onPhone == 1) "It stays" else "They stay"} on $serverName."
                    },
                    subtitleColor = if (notOnServer > 0) colors.accentText else colors.textSecondary,
                    onClick = onRemoveFromPhone,
                )
                Spacer(Modifier.height(10.dp))
            }
            DeleteOption(
                title = "Delete everywhere",
                titleColor = colors.danger,
                subtitle = if (onPhone > 0) "From this phone and from $serverName. This can’t be undone." else "From $serverName. This can’t be undone.",
                subtitleColor = colors.textSecondary,
                onClick = onDeleteEverywhere,
            )
            Spacer(Modifier.height(16.dp))
            OutlineButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DeleteOption(title: String, titleColor: Color, subtitle: String, subtitleColor: Color, onClick: () -> Unit) {
    val colors = Darkroom.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = Darkroom.type.body, color = titleColor)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = Darkroom.type.caption, color = subtitleColor)
    }
}

/** The light pill that confirms an action, with an optional "View" link. */
@Composable
internal fun PhotosSnackbar(text: String, onView: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Darkroom.colors
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(colors.text)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Darkroom.type.body, color = colors.background, modifier = Modifier.weight(1f).padding(vertical = 8.dp), maxLines = 2)
        if (onView != null) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onView).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("View", style = Darkroom.type.body, color = if (colors.isDark) Color(0xFFA15C00) else colors.accent)
            }
        }
    }
}
