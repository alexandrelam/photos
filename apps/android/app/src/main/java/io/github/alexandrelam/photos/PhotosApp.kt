package io.github.alexandrelam.photos

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import io.github.alexandrelam.photos.media.PhoneMedia
import io.github.alexandrelam.photos.server.Server
import io.github.alexandrelam.photos.sync.Sync

/** The app-wide singletons. Screens reach them through `LocalAppGraph` or `PhotosApp.graph`. */
class AppGraph(app: Application) {
    val server = Server(app)
    val phoneMedia = PhoneMedia(app)
    val sync = Sync(app, server)
    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { server.http }))
            add(VideoFrameDecoder.Factory())
        }
        .diskCache {
            DiskCache.Builder()
                .directory(app.cacheDir.resolve("thumbnails"))
                .maxSizeBytes(1024L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()
}

class PhotosApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}
