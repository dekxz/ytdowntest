package com.youtubedownloader.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies finished files into the public Downloads/YouTubeDownloader folder.
 * Uses MediaStore, so no storage permission is needed on Android 14+.
 */
object MediaExporter {

    const val FOLDER = "YouTubeDownloader"

    /** RELATIVE_PATH as MediaStore stores it (with trailing slash). */
    val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

    /** Finds a file in Downloads/YouTubeDownloader by exact display name (only files this app created are visible). */
    private fun exists(context: Context, displayName: String): Boolean {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(displayName, RELATIVE_PATH),
            null
        )?.use { c -> if (c.moveToFirst()) return true }
        return false
    }

    /** Returns "Name.ext" if free, otherwise "Name_1.ext", "Name_2.ext", ... */
    fun uniqueName(context: Context, baseName: String, ext: String): String {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var candidate = "$baseName$suffix"
        var counter = 1
        while (exists(context, candidate)) {
            candidate = "${baseName}_$counter$suffix"
            counter++
        }
        return candidate
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    fun export(context: Context, file: File, displayName: String = file.name): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(file.extension))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: run { resolver.delete(uri, null, null); return false }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
