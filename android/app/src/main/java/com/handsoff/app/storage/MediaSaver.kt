package com.handsoff.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream

/**
 * Saves received files where people expect them: photos in Pictures/Handsoff and
 * videos in Movies/Handsoff (so they show up in the Gallery), everything else in
 * Download/Handsoff. No storage permission is needed for files the app creates.
 */
object MediaSaver {
    fun save(context: Context, input: InputStream, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val isImage = mime.startsWith("image/") && mime != "image/svg+xml"
        val isVideo = mime.startsWith("video/")
        val targets = buildList {
            if (isImage) add(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/Handsoff")
            if (isVideo) add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/Handsoff")
            add(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download/Handsoff")
        }

        var lastError: Exception? = null
        for ((collection, dir) in targets) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, values)
            } catch (e: Exception) {
                lastError = e
                null
            } ?: continue // e.g. a MIME type this collection refuses: fall back to Downloads
            try {
                resolver.openOutputStream(uri)!!.use { out -> input.copyTo(out) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        throw lastError ?: IllegalStateException("Could not save $name")
    }
}
