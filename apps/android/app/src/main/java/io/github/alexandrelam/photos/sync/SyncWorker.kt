package io.github.alexandrelam.photos.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.alexandrelam.photos.PhotosApp
import org.json.JSONArray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/** Uploads pending files one by one. Started only by [Sync.start]. */
internal class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph = (context.applicationContext as PhotosApp).graph

    override suspend fun doWork(): Result {
        val sync = graph.sync
        val server = graph.server
        runCatching { setForeground(foregroundInfo("Preparing…", 0, 0)) }

        var uploaded = 0
        return try {
            val candidates = graph.phoneMedia.all().filter { it.local != null && sync.isBackedUp(it) }
            val missing = HashSet<String>()
            candidates.map { it.local!!.key }.chunked(2000).forEach { missing += server.missing(sync.deviceId, it) }
            sync.markSynced(candidates.map { it.local!!.key }.filter { it !in missing })

            val queue = candidates.filter { it.local!!.key in missing }
            val bytesTotal = queue.sumOf { it.size }
            // Replaced, never mutated, because the ticker reads it from another thread.
            var recent = emptyList<SyncFile>()
            var bytesBefore = 0L
            val inFlight = AtomicLong(0)
            val started = System.currentTimeMillis()

            suspend fun report() {
                val sent = bytesBefore + inFlight.get()
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                setProgress(writeProgress(SyncProgress(uploaded, queue.size, sent, bytesTotal, recent, sent * 1000 / elapsed)))
                runCatching { setForeground(foregroundInfo("Uploading ${minOf(uploaded + 1, queue.size)} of ${queue.size}", sent, bytesTotal)) }
            }

            coroutineScope {
                // Byte-level progress arrives on OkHttp's thread; publish it twice a second.
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        report()
                    }
                }
                for (item in queue) {
                    val local = item.local!!
                    recent = (recent + SyncFile(item.name, item.size, local.uri.toString(), done = false)).takeLast(4)
                    inFlight.set(0)
                    report()

                    server.upload(
                        deviceId = sync.deviceId,
                        key = local.key,
                        name = item.name,
                        album = local.albumName,
                        takenAt = item.takenAt,
                        size = item.size,
                        open = { graph.phoneMedia.open(item) ?: error("${item.name} can no longer be read") },
                        onBytes = inFlight::set,
                    )
                    sync.markSynced(local.key)
                    uploaded++
                    bytesBefore += item.size
                    inFlight.set(0)
                    recent = recent.dropLast(1) + recent.last().copy(done = true)
                }
                ticker.cancel()
                report()
            }
            sync.recordFinished()
            Result.success(workDataOf(OUT_UPLOADED to uploaded))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(OUT_ERROR to (e.message ?: "Sync failed"), OUT_UPLOADED to uploaded))
        }
    }

    private fun foregroundInfo(text: String, done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW))
        }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Syncing photos")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total == 0L)
            .addAction(0, "Pause", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val OUT_UPLOADED = "uploaded"
        const val OUT_ERROR = "error"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 42

        fun writeProgress(p: SyncProgress): Data = workDataOf(
            "done" to p.done,
            "total" to p.total,
            "bytesDone" to p.bytesDone,
            "bytesTotal" to p.bytesTotal,
            "speed" to p.bytesPerSecond,
            "recent" to JSONArray(
                p.recent.map { JSONObject().put("name", it.name).put("size", it.size).put("thumb", it.thumbnail).put("done", it.done) },
            ).toString(),
        )

        fun readProgress(data: Data): SyncProgress? {
            if (!data.keyValueMap.containsKey("total")) return null
            val recent = JSONArray(data.getString("recent") ?: "[]")
            return SyncProgress(
                done = data.getInt("done", 0),
                total = data.getInt("total", 0),
                bytesDone = data.getLong("bytesDone", 0),
                bytesTotal = data.getLong("bytesTotal", 0),
                recent = (0 until recent.length()).map {
                    val o = recent.getJSONObject(it)
                    SyncFile(o.getString("name"), o.getLong("size"), o.getString("thumb"), o.getBoolean("done"))
                },
                bytesPerSecond = data.getLong("speed", 0),
            )
        }
    }
}
