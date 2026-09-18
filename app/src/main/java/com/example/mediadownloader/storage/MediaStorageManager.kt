package com.example.mediadownloader.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStorageManager {

    private const val TAG = "MediaStorageManager"
    const val AUDIO_DIRECTORY = "Music/MediaDownloader"
    const val VIDEO_DIRECTORY = "Movies/MediaDownloader"

    /**
     * Returns the app-private temporary directory for incomplete downloads.
     */
    fun getTempDirectory(context: Context): File {
        val dir = File(context.cacheDir, "temp_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Creates a temporary file in the app-private cache directory.
     */
    fun createTempFile(context: Context, prefix: String, extension: String): File {
        val safePrefix = sanitizeFilename(prefix).take(30).ifEmpty { "download" }
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        return File.createTempFile("tmp_${safePrefix}_", ext, getTempDirectory(context))
    }

    /**
     * Sanitizes a filename to remove illegal filesystem characters.
     */
    fun sanitizeFilename(filename: String): String {
        // Illegal characters: / \ : * ? " < > |
        var sanitized = filename
            .replace(Regex("[/\\\\:*?\"<>|]"), "-")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (sanitized.isEmpty()) {
            sanitized = "media_${System.currentTimeMillis()}"
        }
        return sanitized
    }

    /**
     * Publishes a temporary file to MediaStore (Android 10+) or public storage (Android 9-).
     * Returns Pair(ContentUriString, FilePath).
     */
    suspend fun publishToMediaStore(
        context: Context,
        tempFile: File,
        desiredTitle: String,
        format: String // "MP3" or "MP4"
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val isAudio = format.equals("MP3", ignoreCase = true)
        val extension = if (isAudio) ".mp3" else ".mp4"
        val mimeType = if (isAudio) "audio/mpeg" else "video/mp4"
        val baseName = sanitizeFilename(desiredTitle)
        val subDir = if (isAudio) AUDIO_DIRECTORY else VIDEO_DIRECTORY

        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collectionUri = if (isAudio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val finalFileName = getUniqueFileNameMediaStore(resolver, collectionUri, baseName, extension)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.TITLE, desiredTitle)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, subDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collectionUri, values)
                ?: throw IllegalStateException("Failed to insert media item into MediaStore")

            try {
                resolver.openOutputStream(itemUri).use { out ->
                    if (out == null) throw IllegalStateException("Could not open output stream for $itemUri")
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(out)
                    }
                    out.flush()
                }

                // Unmark pending
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)

                val displayPath = "$subDir/$finalFileName"
                Log.d(TAG, "Successfully published to MediaStore: $itemUri, path: $displayPath")
                Pair(itemUri.toString(), displayPath)
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }
        } else {
            // Android 9 and lower legacy public storage
            val baseDir = if (isAudio) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            }
            val targetFolder = File(baseDir, "MediaDownloader")
            if (!targetFolder.exists()) targetFolder.mkdirs()

            val finalFile = getUniqueLocalFile(targetFolder, baseName, extension)
            tempFile.copyTo(finalFile, overwrite = true)

            // Trigger Media Scanner
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(finalFile)
            }
            context.sendBroadcast(mediaScanIntent)

            Pair(Uri.fromFile(finalFile).toString(), finalFile.absolutePath)
        }
    }

    private fun getUniqueFileNameMediaStore(
        resolver: ContentResolver,
        collection: Uri,
        baseName: String,
        extension: String
    ): String {
        var counter = 0
        while (true) {
            val candidate = if (counter == 0) "$baseName$extension" else "$baseName ($counter)$extension"
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(candidate)

            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.count == 0) {
                    return candidate
                }
            } ?: return candidate

            counter++
        }
    }

    private fun getUniqueLocalFile(folder: File, baseName: String, extension: String): File {
        var counter = 0
        while (true) {
            val candidateName = if (counter == 0) "$baseName$extension" else "$baseName ($counter)$extension"
            val file = File(folder, candidateName)
            if (!file.exists()) {
                return file
            }
            counter++
        }
    }

    /**
     * Opens the media file using standard Android ACTION_VIEW intent.
     */
    fun openMedia(context: Context, fileUriString: String?, filePath: String?, mimeType: String): Boolean {
        try {
            val uri = resolvePlayableUri(context, fileUriString, filePath) ?: return false
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open media with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening media", e)
            return false
        }
    }

    /**
     * Shares the media file using standard Android ACTION_SEND intent.
     */
    fun shareMedia(context: Context, fileUriString: String?, filePath: String?, title: String, mimeType: String): Boolean {
        try {
            val uri = resolvePlayableUri(context, fileUriString, filePath) ?: return false
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing media", e)
            return false
        }
    }

    /**
     * Deletes the media file from MediaStore and local file system.
     */
    suspend fun deleteMedia(context: Context, fileUriString: String?, filePath: String?): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        if (!fileUriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(fileUriString)
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) deleted = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete via MediaStore URI: $fileUriString", e)
            }
        }
        if (!filePath.isNullOrEmpty()) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    deleted = file.delete() || deleted
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete local file: $filePath", e)
            }
        }
        deleted
    }

    private fun resolvePlayableUri(context: Context, fileUriString: String?, filePath: String?): Uri? {
        if (!fileUriString.isNullOrEmpty()) {
            val uri = Uri.parse(fileUriString)
            if (uri.scheme == "content") return uri
        }

        if (!filePath.isNullOrEmpty()) {
            val file = File(filePath)
            if (file.exists()) {
                return try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    Uri.fromFile(file)
                }
            }
        }

        return if (!fileUriString.isNullOrEmpty()) Uri.parse(fileUriString) else null
    }
}
