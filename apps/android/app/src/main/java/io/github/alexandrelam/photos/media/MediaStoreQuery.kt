package io.github.alexandrelam.photos.media

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore

/** Queries images and videos from MediaStore. Implementation detail of [PhoneMedia]. */
internal fun queryMediaStore(resolver: ContentResolver): List<MediaItem> {
    val hasRelativePath = Build.VERSION.SDK_INT >= 29
    val projection = buildList {
        add(MediaStore.Files.FileColumns._ID)
        add(MediaStore.Files.FileColumns.DISPLAY_NAME)
        add(MediaStore.Files.FileColumns.SIZE)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.Files.FileColumns.DATE_MODIFIED)
        add("datetaken")
        add("duration")
        add("bucket_id")
        add("bucket_display_name")
        add(MediaStore.Files.FileColumns.WIDTH)
        add(MediaStore.Files.FileColumns.HEIGHT)
        if (hasRelativePath) add(MediaStore.Files.FileColumns.RELATIVE_PATH)
    }.toTypedArray()
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.Files.FileColumns.SIZE} > 0"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    val items = ArrayList<MediaItem>()
    resolver.query(MediaStore.Files.getContentUri("external"), projection, selection, args, null)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val takenCol = c.getColumnIndex("datetaken")
        val durationCol = c.getColumnIndex("duration")
        val bucketIdCol = c.getColumnIndex("bucket_id")
        val bucketNameCol = c.getColumnIndex("bucket_display_name")
        val widthCol = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
        val heightCol = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
        val pathCol = if (hasRelativePath) c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH) else -1

        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val name = c.getString(nameCol) ?: continue
            val size = c.getLong(sizeCol)
            val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
            val takenAt = if (taken > 0) taken else c.getLong(modifiedCol) * 1000
            val albumName = (if (bucketNameCol >= 0) c.getString(bucketNameCol) else null) ?: "Other"
            val albumId = (if (bucketIdCol >= 0) c.getString(bucketIdCol) else null) ?: albumName
            val relativePath = (if (pathCol >= 0) c.getString(pathCol) else null) ?: "$albumName/"
            val uri = ContentUris.withAppendedId(
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id,
            )
            items += MediaItem(
                id = "local:$id",
                name = name,
                takenAt = takenAt,
                size = size,
                width = if (widthCol >= 0 && !c.isNull(widthCol)) c.getInt(widthCol).takeIf { it > 0 } else null,
                height = if (heightCol >= 0 && !c.isNull(heightCol)) c.getInt(heightCol).takeIf { it > 0 } else null,
                isVideo = isVideo,
                durationMs = if (isVideo && durationCol >= 0 && !c.isNull(durationCol)) c.getLong(durationCol) else null,
                thumbnail = uri,
                full = uri,
                local = LocalRef(
                    uri = uri,
                    key = "$relativePath$name:$size",
                    albumId = albumId,
                    albumName = albumName,
                    relativePath = relativePath,
                ),
            )
        }
    }
    items.sortByDescending { it.takenAt }
    return items
}
