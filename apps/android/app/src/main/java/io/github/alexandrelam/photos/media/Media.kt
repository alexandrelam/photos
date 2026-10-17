package io.github.alexandrelam.photos.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import io.github.alexandrelam.photos.shared.format.Format
import io.github.alexandrelam.photos.shared.ui.Darkroom
import io.github.alexandrelam.photos.shared.ui.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class PhotoAccess { Full, Partial, None }

/** A picture or video, on this phone, on the server, or both. */
data class MediaItem(
    /** Unique across sources: `local:<id>` or `server:<id>`. */
    val id: String,
    val name: String,
    val takenAt: Long,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val isVideo: Boolean,
    val durationMs: Long?,
    /** Coil model for the grid thumbnail. */
    val thumbnail: Any,
    /** Coil model for the full-size picture. */
    val full: Any,
    val local: LocalRef? = null,
    val server: ServerRef? = null,
)

data class LocalRef(
    val uri: Uri,
    /** Stable identity used by sync: relative path, name and size. */
    val key: String,
    val albumId: String,
    val albumName: String,
    /** e.g. `DCIM/Camera/`. */
    val relativePath: String,
)

data class ServerRef(
    val id: Long,
    val path: String,
    val folder: String,
    /** The phone key this file was uploaded from, if any. */
    val sourceKey: String?,
)

/** A MediaStore bucket such as Camera or Screenshots. */
data class PhoneAlbum(
    val id: String,
    val name: String,
    val count: Int,
    val cover: MediaItem,
)

/** Reads pictures and videos stored on this phone. */
class PhoneMedia(private val context: Context) {

    /** Runtime permissions needed to read the gallery on this Android version. */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * How much of the gallery the app may read. On Android 14+ the user can pick
     * "Select photos", which only shares the pictures they tick: that is [PhotoAccess.Partial].
     */
    fun access(): PhotoAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Full
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Full
            else -> PhotoAccess.None
        }
    }

    fun hasPermission(): Boolean = access() != PhotoAccess.None

    /** Every picture and video on the phone, newest first. Empty without permission. */
    suspend fun all(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (hasPermission()) queryMediaStore(context.contentResolver) else emptyList()
    }

    /** [all], re-emitted whenever the gallery changes. */
    fun observe(): Flow<List<MediaItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().map { all() }.flowOn(Dispatchers.IO)

    fun open(item: MediaItem) = item.local?.let { context.contentResolver.openInputStream(it.uri) }

    companion object {
        fun albums(items: List<MediaItem>): List<PhoneAlbum> =
            items.filter { it.local != null }
                .groupBy { it.local!!.albumId }
                .map { (id, list) -> PhoneAlbum(id, list.first().local!!.albumName, list.size, list.first()) }
                .sortedByDescending { it.count }
    }
}

/**
 * Square grid cell: the thumbnail, a duration badge for videos and a
 * "not synced" badge when [notSynced] is true.
 */
@Composable
fun MediaThumbnail(item: MediaItem, modifier: Modifier = Modifier, notSynced: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = Darkroom.colors
    Box(
        modifier
            .aspectRatio(1f)
            .background(colors.surfaceRaised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Text(
                text = item.durationMs?.let(Format::duration) ?: "Video",
                style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xC70E0F11))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (notSynced) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xB80E0F11)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CloudOff, contentDescription = "Not synced", tint = colors.accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Asks for access to the whole gallery. Shows nothing once access is full.
 * With partial access ("Select photos") it sends the user to the app's permission
 * screen, where "Allow all" lives, instead of the per-photo picker.
 * Callers should re-read [PhoneMedia.access] when the screen resumes.
 */
@Composable
fun PhotoAccessPrompt(access: PhotoAccess, permissions: Array<String>, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    if (access == PhotoAccess.Full) return
    val context = LocalContext.current
    val colors = Darkroom.colors
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    Column(modifier) {
        Text(
            if (access == PhotoAccess.Partial) "Darkroom only sees the photos you picked" else "Allow access to your photos",
            style = Darkroom.type.section,
            color = colors.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (access == PhotoAccess.Partial) {
                "To back up whole albums, open Photos and videos and choose \"Always allow all\"."
            } else {
                "Darkroom needs it to show and back up the pictures on this phone. Choose \"Allow all\"."
            },
            style = Darkroom.type.caption,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = if (access == PhotoAccess.Partial) "Allow all photos" else "Allow",
            onClick = {
                if (access == PhotoAccess.Partial) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    request.launch(permissions)
                }
            },
        )
    }
}
