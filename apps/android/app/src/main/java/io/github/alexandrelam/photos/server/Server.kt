package io.github.alexandrelam.photos.server

import android.content.Context
import io.github.alexandrelam.photos.media.MediaItem
import io.github.alexandrelam.photos.media.ServerRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Where the app finds the home server. */
data class ServerSettings(
    val homeUrl: String,
    val awayUrl: String,
    val accessKey: String,
    /** Name the server reported when we connected. */
    val name: String,
)

enum class Route { Home, Away }

sealed interface Connection {
    data object NotConfigured : Connection
    data object Checking : Connection
    data class Online(val name: String, val route: Route) : Connection
    data class Offline(val reason: String) : Connection
}

data class ServerFolder(
    val path: String,
    val name: String,
    val count: Int,
    val oldest: Long?,
    val newest: Long?,
    /** Thumbnail models of up to four recent pictures, for the folder mosaic. */
    val covers: List<Any>,
)

data class FolderPage(val path: String, val name: String, val folders: List<ServerFolder>, val items: List<MediaItem>, val total: Int)

/** One month of the server timeline. [month] is "yyyy-MM". */
data class ServerMonth(val month: String, val count: Int, val mine: Int)

data class TimelinePage(val items: List<MediaItem>, val next: String?)

/** Thrown with a message that can be shown to the user as is. */
class ServerException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The home server: its settings, which address currently works (home Wi‑Fi or
 * the away/Tailscale address), and every API call the app makes.
 */
class Server(context: Context) {
    private val store = SettingsStore(context)
    private val _settings = MutableStateFlow(store.load())
    private val _connection = MutableStateFlow<Connection>(if (_settings.value == null) Connection.NotConfigured else Connection.Checking)
    private val router = Router(::currentSettings, _connection)

    val settings: StateFlow<ServerSettings?> = _settings.asStateFlow()
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /**
     * Authenticated client for the server. Requests to [ServerLinks] URLs are
     * sent to whichever address currently works. Share it with the image loader.
     */
    val http: OkHttpClient = router.client

    private fun currentSettings() = _settings.value

    /**
     * Checks the addresses and key without saving them. Returns the server name
     * and which address answered, or throws [ServerException].
     */
    suspend fun test(homeUrl: String, awayUrl: String, accessKey: String): Pair<String, Route> = withContext(Dispatchers.IO) {
        router.probe(ServerSettings(normalizeUrl(homeUrl), normalizeUrl(awayUrl), accessKey.trim(), ""))
    }

    /** Tests and then saves the settings. */
    suspend fun connect(homeUrl: String, awayUrl: String, accessKey: String): String {
        val (name, route) = test(homeUrl, awayUrl, accessKey)
        val saved = ServerSettings(normalizeUrl(homeUrl), normalizeUrl(awayUrl), accessKey.trim(), name)
        store.save(saved)
        _settings.value = saved
        router.forget()
        _connection.value = Connection.Online(name, route)
        return name
    }

    /** Re-checks which address works now. */
    suspend fun refreshConnection(): Connection = withContext(Dispatchers.IO) {
        router.forget()
        runCatching { router.base() }
        _connection.value
    }

    suspend fun timeline(cursor: String? = null, limit: Int = 300): TimelinePage = get(
        ServerLinks.api("timeline").newBuilder().apply {
            if (cursor != null) addQueryParameter("cursor", cursor)
            addQueryParameter("limit", limit.toString())
        }.build().toString(),
    ) { json -> TimelinePage(json.getJSONArray("items").mediaItems(), json.optStringOrNull("next")) }

    suspend fun folder(path: String, offset: Int = 0, limit: Int = 300): FolderPage = get(
        ServerLinks.api("folders").newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build().toString(),
    ) { json ->
        val folders = json.getJSONArray("folders")
        FolderPage(
            path = json.getString("path"),
            name = json.getString("name"),
            folders = (0 until folders.length()).map { i ->
                val f = folders.getJSONObject(i)
                val covers = f.getJSONArray("coverIds")
                ServerFolder(
                    path = f.getString("path"),
                    name = f.getString("name"),
                    count = f.getInt("count"),
                    oldest = f.optLongOrNull("oldest"),
                    newest = f.optLongOrNull("newest"),
                    covers = (0 until covers.length()).map { ServerLinks.thumb(covers.getLong(it)) },
                )
            },
            items = json.getJSONArray("items").mediaItems(),
            total = json.getInt("total"),
        )
    }

    /**
     * Photo counts per month ("2026-10"), newest first. `mine` is how many of them
     * this device uploaded, so callers can avoid counting a photo twice.
     */
    suspend fun months(deviceId: String): List<ServerMonth> {
        val offsetMinutes = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        return get(
            ServerLinks.api("timeline/months").newBuilder()
                .addQueryParameter("deviceId", deviceId)
                .addQueryParameter("offsetMinutes", offsetMinutes.toString())
                .build().toString(),
        ) { json ->
            val months = json.getJSONArray("months")
            (0 until months.length()).map {
                val m = months.getJSONObject(it)
                ServerMonth(m.getString("month"), m.getInt("count"), m.getInt("mine"))
            }
        }
    }

    /** Deletes files from the server, by server id and/or by the keys this device uploaded them under. */
    suspend fun delete(ids: List<Long>, deviceId: String, keys: List<String>): Int = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("ids", JSONArray(ids))
            .put("deviceId", deviceId)
            .put("keys", JSONArray(keys))
            .toString()
            .toRequestBody("application/json".toMediaType())
        call(Request.Builder().url(ServerLinks.api("media/delete").toString()).post(body).build()) { it.getInt("deleted") }
    }

    /** Which of [keys] the server has not received from this device yet. */
    suspend fun missing(deviceId: String, keys: List<String>): Set<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("deviceId", deviceId).put("keys", JSONArray(keys)).toString()
            .toRequestBody("application/json".toMediaType())
        call(Request.Builder().url(ServerLinks.api("sync/check").toString()).post(body).build()) { json ->
            val missing = json.getJSONArray("missing")
            (0 until missing.length()).mapTo(HashSet()) { missing.getString(it) }
        }
    }

    /** Streams one file to the server. [onBytes] reports progress as bytes are sent. */
    suspend fun upload(
        deviceId: String,
        key: String,
        name: String,
        album: String,
        takenAt: Long,
        size: Long,
        open: () -> InputStream,
        onBytes: (Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val url = ServerLinks.api("sync/upload").newBuilder()
            .addQueryParameter("deviceId", deviceId)
            .addQueryParameter("key", key)
            .addQueryParameter("name", name)
            .addQueryParameter("album", album)
            .addQueryParameter("takenAt", takenAt.toString())
            .addQueryParameter("size", size.toString())
            .build()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().source().use { source ->
                    var sent = 0L
                    while (true) {
                        val read = source.read(sink.buffer, 256 * 1024)
                        if (read == -1L) break
                        sink.emit()
                        sent += read
                        onBytes(sent)
                    }
                }
            }
        }
        call(Request.Builder().url(url).put(body).build()) { it.getString("path") }
    }

    /** Copies the original file of a server item into [out]. */
    suspend fun download(serverId: Long, out: OutputStream) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(ServerLinks.file(serverId)).build()).execute().use { res ->
            if (!res.isSuccessful) throw ServerException("Download failed (${res.code})")
            res.body.byteStream().copyTo(out)
        }
    }

    /** Asks the server to re-index its photos folder. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        call(Request.Builder().url(ServerLinks.api("library/rescan").toString()).post(ByteArray(0).toRequestBody()).build()) { }
    }

    /** Turns a [ServerLinks] URL into the real address, for players that cannot use [http]. */
    suspend fun realUrl(link: String): String = withContext(Dispatchers.IO) { router.rewrite(link.toHttpUrl()).toString() }

    fun authHeaders(): Map<String, String> =
        _settings.value?.let { mapOf("Authorization" to "Bearer ${it.accessKey}") } ?: emptyMap()

    private suspend fun <T> get(url: String, parse: (JSONObject) -> T): T = withContext(Dispatchers.IO) {
        call(Request.Builder().url(url).build(), parse)
    }

    private fun <T> call(request: Request, parse: (JSONObject) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: ServerException) {
            throw e
        } catch (e: IOException) {
            router.forget()
            throw ServerException("Can't reach your server", e)
        }
        response.use { res ->
            val text = res.body.string()
            if (res.code == 401) throw ServerException("Your server refused the access key")
            if (!res.isSuccessful) {
                val message = runCatching { JSONObject(text).getString("error") }.getOrNull()
                throw ServerException(message ?: "Server error (${res.code})")
            }
            return parse(JSONObject(text))
        }
    }
}

/**
 * Stable URLs for server resources. They point at a placeholder host that
 * [Server.http] rewrites to the address that currently works, so image caches
 * keep working when you move between home and away.
 */
object ServerLinks {
    internal const val HOST = "darkroom.server"

    internal fun api(path: String) = "http://$HOST/api/$path".toHttpUrl()

    fun thumb(id: Long) = "http://$HOST/api/media/$id/thumb"

    fun file(id: Long) = "http://$HOST/api/media/$id/file"
}

/** Adds `http://` when missing and drops trailing slashes. */
fun normalizeUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return if (trimmed.contains("://")) trimmed else "http://$trimmed"
}

private fun JSONArray.mediaItems(): List<MediaItem> = (0 until length()).map { i ->
    val o = getJSONObject(i)
    val id = o.getLong("id")
    MediaItem(
        id = "server:$id",
        name = o.getString("name"),
        takenAt = o.getLong("takenAt"),
        size = o.getLong("size"),
        width = o.optIntOrNull("width"),
        height = o.optIntOrNull("height"),
        isVideo = o.getString("kind") == "video",
        durationMs = null,
        thumbnail = ServerLinks.thumb(id),
        full = if (o.getString("kind") == "video") ServerLinks.thumb(id) else ServerLinks.file(id),
        server = ServerRef(
            id = id,
            path = o.getString("path"),
            folder = o.getString("folder"),
            sourceKey = o.optStringOrNull("sourceKey"),
        ),
    )
}

private fun JSONObject.optStringOrNull(name: String) = if (isNull(name)) null else optString(name)
private fun JSONObject.optLongOrNull(name: String) = if (isNull(name)) null else optLong(name)
private fun JSONObject.optIntOrNull(name: String) = if (isNull(name)) null else optInt(name)
