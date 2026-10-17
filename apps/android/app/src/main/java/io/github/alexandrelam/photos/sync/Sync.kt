package io.github.alexandrelam.photos.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.media.PhoneAlbum
import io.github.alexandrelam.photos.server.Server
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class SyncPrefs(
    /** Only upload on unmetered networks. */
    val wifiOnly: Boolean,
    /** Only upload while the phone is charging. */
    val whileCharging: Boolean,
    /** Album ids to back up; null until the user changes it (then Camera is the default). */
    val backupAlbums: Set<String>?,
    val lastSyncAt: Long?,
)

data class SyncFile(val name: String, val size: Long, val thumbnail: String, val done: Boolean)

data class SyncProgress(
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** The last few files, newest last; the last one may still be uploading. */
    val recent: List<SyncFile>,
    val bytesPerSecond: Long,
)

sealed interface SyncState {
    data object Idle : SyncState
    /** Queued, waiting for Wi‑Fi or a charger. */
    data class Waiting(val reason: String) : SyncState
    data class Running(val progress: SyncProgress?) : SyncState
    data object Paused : SyncState
    data class Done(val uploaded: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Manual backup of phone albums to the server. Nothing uploads until [start] is
 * called (the Sync button); the upload then runs in a foreground worker so it
 * keeps going if the app is closed.
 */
class Sync(private val context: Context, private val server: Server) {
    private val prefsStore = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(readPrefs())
    private val keys = SyncedKeys(context)
    private val work = WorkManager.getInstance(context)

    val deviceId: String = prefsStore.getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { prefsStore.edit().putString("deviceId", it).apply() }

    val prefs: StateFlow<SyncPrefs> = _prefs.asStateFlow()

    /** Keys of phone files the server already has. */
    val syncedKeys: StateFlow<Set<String>> = keys.keys

    val state: Flow<SyncState> = work.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val info = infos.firstOrNull() ?: return@map SyncState.Idle
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SyncState.Waiting(
                when {
                    _prefs.value.whileCharging && _prefs.value.wifiOnly -> "Waiting for Wi‑Fi and a charger"
                    _prefs.value.whileCharging -> "Waiting for a charger"
                    _prefs.value.wifiOnly -> "Waiting for Wi‑Fi"
                    else -> "Waiting for a connection"
                },
            )
            WorkInfo.State.RUNNING -> SyncState.Running(SyncWorker.readProgress(info.progress))
            WorkInfo.State.SUCCEEDED -> SyncState.Done(info.outputData.getInt(SyncWorker.OUT_UPLOADED, 0))
            WorkInfo.State.FAILED -> SyncState.Failed(info.outputData.getString(SyncWorker.OUT_ERROR) ?: "Sync failed")
            WorkInfo.State.CANCELLED -> SyncState.Paused
        }
    }

    /** Starts uploading every not-yet-synced file of the backed-up albums. */
    fun start() {
        val p = _prefs.value
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (p.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(p.whileCharging)
                    .build(),
            )
            .apply { if (!p.wifiOnly && !p.whileCharging) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Stops the current sync. Already uploaded files stay on the server; the next sync skips them. */
    fun pause() {
        work.cancelUniqueWork(WORK_NAME)
    }

    fun isBackedUp(album: PhoneAlbum): Boolean = isBackedUp(album.id, album.name)

    fun isBackedUp(item: MediaItem): Boolean = item.local?.let { isBackedUp(it.albumId, it.albumName) } ?: false

    private fun isBackedUp(albumId: String, albumName: String): Boolean =
        _prefs.value.backupAlbums?.contains(albumId) ?: albumName.equals("Camera", ignoreCase = true)

    fun setBackedUp(album: PhoneAlbum, on: Boolean, allAlbums: List<PhoneAlbum>) {
        val current = _prefs.value.backupAlbums ?: allAlbums.filter { isBackedUp(it) }.map { it.id }.toSet()
        update { it.copy(backupAlbums = if (on) current + album.id else current - album.id) }
    }

    fun setWifiOnly(on: Boolean) = update { it.copy(wifiOnly = on) }

    fun setWhileCharging(on: Boolean) = update { it.copy(whileCharging = on) }

    /** Phone files in backed-up albums that the server does not have yet. */
    fun pending(local: List<MediaItem>): List<MediaItem> {
        val synced = syncedKeys.value
        return local.filter { item -> item.local != null && isBackedUp(item) && item.local.key !in synced }
    }

    /** Asks the server which phone files it has, so "not synced" badges are accurate. */
    suspend fun refreshStatus(local: List<MediaItem>) {
        val all = local.mapNotNull { it.local?.key }
        if (all.isEmpty()) return
        val missing = HashSet<String>()
        all.chunked(2000).forEach { missing += server.missing(deviceId, it) }
        keys.replace(all.filterTo(HashSet()) { it !in missing })
    }

    internal fun markSynced(key: String) = keys.add(key)

    internal fun markSynced(synced: Collection<String>) = keys.addAll(synced)

    internal fun recordFinished() = update { it.copy(lastSyncAt = System.currentTimeMillis()) }

    private fun update(change: (SyncPrefs) -> SyncPrefs) {
        val next = change(_prefs.value)
        _prefs.value = next
        prefsStore.edit()
            .putBoolean("wifiOnly", next.wifiOnly)
            .putBoolean("whileCharging", next.whileCharging)
            .apply {
                if (next.backupAlbums == null) remove("backupAlbums") else putStringSet("backupAlbums", next.backupAlbums)
                if (next.lastSyncAt != null) putLong("lastSyncAt", next.lastSyncAt)
            }
            .apply()
    }

    private fun readPrefs() = SyncPrefs(
        wifiOnly = prefsStore.getBoolean("wifiOnly", true),
        whileCharging = prefsStore.getBoolean("whileCharging", false),
        backupAlbums = prefsStore.getStringSet("backupAlbums", null)?.toSet(),
        lastSyncAt = prefsStore.getLong("lastSyncAt", 0L).takeIf { it > 0 },
    )

    companion object {
        internal const val WORK_NAME = "sync"
    }
}
