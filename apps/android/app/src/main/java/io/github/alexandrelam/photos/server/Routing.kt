package io.github.alexandrelam.photos.server

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Picks the address that answers (home first, then away), caches it for a
 * minute, and rewrites [ServerLinks] requests to it. Implementation detail of [Server].
 */
internal class Router(
    private val settings: () -> ServerSettings?,
    private val connection: MutableStateFlow<Connection>,
) {
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: HttpUrl? = null
    @Volatile private var cachedAt = 0L

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            var request = chain.request()
            if (request.url.host == ServerLinks.HOST) {
                request = request.newBuilder().url(rewrite(request.url)).build()
            }
            settings()?.let { request = request.newBuilder().header("Authorization", "Bearer ${it.accessKey}").build() }
            chain.proceed(request)
        }
        .build()

    fun forget() {
        cached = null
    }

    fun rewrite(link: HttpUrl): HttpUrl {
        val base = base()
        return base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + link.encodedPath)
            .encodedQuery(link.encodedQuery)
            .build()
    }

    @Synchronized
    fun base(): HttpUrl {
        cached?.takeIf { System.currentTimeMillis() - cachedAt < 60_000 }?.let { return it }
        val current = settings() ?: throw ServerException("No server set up yet")
        return try {
            val (name, route) = probe(current)
            val url = (if (route == Route.Home) current.homeUrl else current.awayUrl).toHttpUrlOrNull()!!
            cached = url
            cachedAt = System.currentTimeMillis()
            connection.value = Connection.Online(name, route)
            url
        } catch (e: ServerException) {
            connection.value = Connection.Offline(e.message ?: "Offline")
            throw e
        }
    }

    /** Tries each address in turn. Returns the server name and the address that worked. */
    fun probe(s: ServerSettings): Pair<String, Route> {
        if (s.homeUrl.isEmpty() && s.awayUrl.isEmpty()) throw ServerException("Enter your server's address")
        var lastError = "Can't reach your server"
        for ((route, address) in listOf(Route.Home to s.homeUrl, Route.Away to s.awayUrl)) {
            if (address.isEmpty()) continue
            val url = "$address/api/server".toHttpUrlOrNull() ?: throw ServerException("\"$address\" is not a valid address")
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${s.accessKey}").build()
            try {
                probeClient.newCall(request).execute().use { res ->
                    when {
                        res.code == 401 -> throw ServerException("Your server refused the access key")
                        res.isSuccessful -> return JSONObject(res.body.string()).getString("name") to route
                        else -> lastError = "Server answered ${res.code}"
                    }
                }
            } catch (e: ServerException) {
                throw e
            } catch (e: IOException) {
                lastError = "Can't reach your server"
            } catch (e: org.json.JSONException) {
                lastError = "That address is not a Darkroom server"
            }
        }
        throw ServerException(lastError)
    }
}

/** Persists [ServerSettings] in shared preferences. */
internal class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun load(): ServerSettings? {
        val key = prefs.getString("accessKey", null) ?: return null
        return ServerSettings(
            homeUrl = prefs.getString("homeUrl", "")!!,
            awayUrl = prefs.getString("awayUrl", "")!!,
            accessKey = key,
            name = prefs.getString("name", "")!!,
        )
    }

    fun save(settings: ServerSettings) {
        prefs.edit()
            .putString("homeUrl", settings.homeUrl)
            .putString("awayUrl", settings.awayUrl)
            .putString("accessKey", settings.accessKey)
            .putString("name", settings.name)
            .apply()
    }
}
