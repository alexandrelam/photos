package io.github.alexandrelam.photos.actions

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.server.Server
import io.github.alexandrelam.photos.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What can be done to a batch of photos: share, save to the phone, delete from the server. */
class PhotoActions(private val context: Context, private val server: Server, private val sync: Sync) {

    /**
     * A chooser to share these photos with another app. Server-only photos are
     * downloaded to the cache first, so this can take a while.
     */
    suspend fun shareIntent(items: List<MediaItem>): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val uris = ArrayList<Uri>()
        for (item in items) {
            val local = item.local
            val serverId = item.server?.id
            when {
                local != null -> uris += local.uri
                serverId != null -> {
                    val file = File(dir, "$serverId-${item.name}")
                    file.outputStream().use { server.download(serverId, it) }
                    uris += FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }
            }
        }
        val type = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null)
    }

    /**
     * Downloads the server-only photos among [items] into Pictures/Darkroom (videos into
     * Movies/Darkroom) and returns how many were saved. Needs Android 10 or later.
     */
    suspend fun saveToPhone(items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Saving needs Android 10 or later")
        var saved = 0
        for (item in items) {
            val serverId = item.server?.id ?: continue
            if (item.local != null) continue
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (item.isVideo) "Movies/Darkroom" else "Pictures/Darkroom")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values) ?: throw IllegalStateException("Couldn't create ${item.name}")
            try {
                context.contentResolver.openOutputStream(uri)!!.use { server.download(serverId, it) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                saved++
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        saved
    }

    /**
     * Deletes the server copies of [items]: server photos by id, and phone photos
     * through the key they were uploaded under. Returns how many files the server removed.
     */
    suspend fun deleteFromServer(items: List<MediaItem>): Int {
        val ids = items.mapNotNull { it.server?.id }
        val keys = items.mapNotNull { it.local?.key }
        if (ids.isEmpty() && keys.isEmpty()) return 0
        val deleted = server.delete(ids, sync.deviceId, keys)
        sync.forget(keys)
        return deleted
    }
}

/** Deletes photos from this phone. On Android 11+ the system asks the user to confirm. */
fun interface PhoneDeleter {
    /** [onResult] gets how many were deleted, or 0 if the user said no. */
    fun delete(items: List<MediaItem>, onResult: (Int) -> Unit)
}

@Composable
fun rememberPhoneDeleter(): PhoneDeleter {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<Pair<Int, (Int) -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val (count, onResult) = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        onResult(if (result.resultCode == Activity.RESULT_OK) count else 0)
    }
    val currentLauncher = rememberUpdatedState(launcher)
    return remember {
        PhoneDeleter { items, onResult ->
            val uris = items.mapNotNull { it.local?.uri }
            when {
                uris.isEmpty() -> onResult(0)
                Build.VERSION.SDK_INT >= 30 -> {
                    pending[0] = uris.size to onResult
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    currentLauncher.value.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                else -> onResult(uris.count { runCatching { context.contentResolver.delete(it, null, null) > 0 }.getOrDefault(false) })
            }
        }
    }
}
