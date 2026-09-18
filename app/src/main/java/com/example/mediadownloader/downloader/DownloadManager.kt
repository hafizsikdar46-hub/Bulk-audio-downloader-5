package com.example.mediadownloader.downloader

import android.content.Context
import android.util.Log
import com.example.mediadownloader.data.db.DownloadItemEntity
import com.example.mediadownloader.data.db.DownloadStatus
import com.example.mediadownloader.data.repository.DownloadRepository
import com.example.mediadownloader.media.AudioConverter
import com.example.mediadownloader.media.VideoProcessor
import com.example.mediadownloader.resolver.CompositeMediaResolver
import com.example.mediadownloader.storage.MediaStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager private constructor(
    private val context: Context,
    private val repository: DownloadRepository,
    private val resolver: CompositeMediaResolver,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "DownloadManager"
        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(
            context: Context,
            repository: DownloadRepository,
            resolver: CompositeMediaResolver
        ): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(
                    context.applicationContext,
                    repository,
                    resolver
                ).also { INSTANCE = it }
            }
        }
    }

    private val queueMutex = Mutex()
    private val activeCancellations = ConcurrentHashMap<Long, Boolean>()

    private val _activeProgress = MutableStateFlow<ActiveDownloadProgress?>(null)
    val activeProgress: StateFlow<ActiveDownloadProgress?> = _activeProgress.asStateFlow()

    private val _lastCompletedEvent = MutableSharedFlow<DownloadItemEntity>(replay = 0)
    val lastCompletedEvent: SharedFlow<DownloadItemEntity> = _lastCompletedEvent.asSharedFlow()

    private val _lastFailedEvent = MutableSharedFlow<DownloadItemEntity>(replay = 0)
    val lastFailedEvent: SharedFlow<DownloadItemEntity> = _lastFailedEvent.asSharedFlow()

    @Volatile
    private var isProcessing = false

    suspend fun enqueue(item: DownloadItemEntity): Long {
        val id = repository.insert(item)
        DownloadService.startQueue(context)
        return id
    }

    suspend fun enqueueAll(items: List<DownloadItemEntity>): List<Long> {
        val ids = repository.insertAll(items)
        DownloadService.startQueue(context)
        return ids
    }

    fun cancel(id: Long) {
        activeCancellations[id] = true
    }

    fun cancelAll() {
        activeCancellations.clear()
        isProcessing = false
    }

    suspend fun retry(id: Long) {
        repository.retryFailed(id)
        DownloadService.startQueue(context)
    }

    suspend fun retryAllFailed() {
        repository.retryAllFailed()
        DownloadService.startQueue(context)
    }

    suspend fun processQueue(onQueueFinished: ((Boolean) -> Unit)? = null) = withContext(Dispatchers.IO) {
        if (!queueMutex.tryLock()) {
            Log.d(TAG, "Queue processor is already running")
            return@withContext
        }

        try {
            isProcessing = true
            while (isProcessing) {
                val pendingList = repository.getPendingDownloads()
                if (pendingList.isEmpty()) {
                    Log.d(TAG, "Queue is empty, finishing processing")
                    break
                }

                val totalInBatch = pendingList.size
                for ((index, item) in pendingList.withIndex()) {
                    if (!isProcessing) break

                    if (activeCancellations.containsKey(item.id)) {
                        activeCancellations.remove(item.id)
                        repository.updateStatus(item.id, DownloadStatus.CANCELLED)
                        continue
                    }

                    processSingleDownload(item, index + 1, totalInBatch)
                }
            }
        } finally {
            _activeProgress.value = null
            isProcessing = false
            queueMutex.unlock()
            val remaining = repository.getPendingDownloads()
            onQueueFinished?.invoke(remaining.isEmpty())
        }
    }

    private suspend fun processSingleDownload(
        item: DownloadItemEntity,
        currentIndex: Int,
        totalInBatch: Int
    ) {
        val id = item.id
        Log.d(TAG, "Starting download #$id: ${item.title}")

        // 1. Mark as RESOLVING
        repository.updateStatus(id, DownloadStatus.RESOLVING)
        _activeProgress.value = ActiveDownloadProgress(
            id = id,
            title = item.title,
            format = item.format,
            downloadedBytes = 0,
            totalBytes = 0,
            progress = 0,
            status = DownloadStatus.RESOLVING,
            currentItemIndex = currentIndex,
            totalItems = totalInBatch
        )

        var downloadUrl = item.resolvedUrl
        var directStreamFormat = item.format.lowercase()

        // If not already resolved, resolve now
        if (downloadUrl.isNullOrEmpty()) {
            try {
                val resolvedStream = resolver.resolveMediaStream(item.sourceUrl, item.format)
                if (resolvedStream == null || resolvedStream.url.isEmpty()) {
                    val errMsg = "Failed to resolve playable media stream. The source might be protected, private, or invalid."
                    repository.updateStatus(id, DownloadStatus.FAILED, errMsg)
                    val failedItem = item.copy(status = DownloadStatus.FAILED, errorMessage = errMsg)
                    _lastFailedEvent.emit(failedItem)
                    return
                }
                downloadUrl = resolvedStream.url
                directStreamFormat = resolvedStream.format
            } catch (e: Exception) {
                Log.e(TAG, "Resolver error for #${item.id}", e)
                val errMsg = e.localizedMessage ?: "Resolution error"
                repository.updateStatus(id, DownloadStatus.FAILED, errMsg)
                val failedItem = item.copy(status = DownloadStatus.FAILED, errorMessage = errMsg)
                _lastFailedEvent.emit(failedItem)
                return
            }
        }

        if (activeCancellations.containsKey(id)) {
            activeCancellations.remove(id)
            repository.updateStatus(id, DownloadStatus.CANCELLED)
            return
        }

        // 2. Mark as DOWNLOADING
        repository.updateStatus(id, DownloadStatus.DOWNLOADING)
        val tempRawFile = MediaStorageManager.createTempFile(context, item.title, "raw_$directStreamFormat")
        var finalProcessedFile: File? = null

        try {
            // Stream download
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from stream")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            FileOutputStream(tempRawFile).use { fileOut ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastDbUpdate = System.currentTimeMillis()
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (activeCancellations.containsKey(id)) {
                            activeCancellations.remove(id)
                            repository.updateStatus(id, DownloadStatus.CANCELLED)
                            return
                        }

                        fileOut.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        val progressPercent = if (totalBytes > 0) {
                            ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        // Throttle DB updates to once every 400ms to save I/O
                        if (now - lastDbUpdate > 400 || downloadedBytes == totalBytes) {
                            lastDbUpdate = now
                            repository.updateProgress(id, downloadedBytes, totalBytes, progressPercent)
                            _activeProgress.value = ActiveDownloadProgress(
                                id = id,
                                title = item.title,
                                format = item.format,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                progress = progressPercent,
                                status = DownloadStatus.DOWNLOADING,
                                currentItemIndex = currentIndex,
                                totalItems = totalInBatch
                            )
                        }
                    }
                    fileOut.flush()
                }
            }

            if (tempRawFile.length() == 0L) {
                throw IllegalStateException("Downloaded media file is 0 bytes")
            }

            // 3. Media Processing (MP3 conversion or MP4 verification/muxing)
            _activeProgress.value = _activeProgress.value?.copy(
                status = "PROCESSING",
                progress = 98
            )

            finalProcessedFile = if (item.format.equals("MP3", ignoreCase = true)) {
                val mp3File = MediaStorageManager.createTempFile(context, item.title, "mp3")
                AudioConverter.convertToMp3(
                    inputFile = tempRawFile,
                    outputFile = mp3File,
                    title = item.title,
                    artist = item.playlistTitle ?: "Media Downloader"
                )
            } else {
                val mp4File = MediaStorageManager.createTempFile(context, item.title, "mp4")
                VideoProcessor.processMp4(
                    inputFile = tempRawFile,
                    outputFile = mp4File
                )
            }

            // 4. Save to Scoped Storage / MediaStore
            val (contentUri, filePath) = MediaStorageManager.publishToMediaStore(
                context = context,
                tempFile = finalProcessedFile,
                desiredTitle = item.title,
                format = item.format
            )

            // 5. Mark as Completed
            repository.markCompleted(
                id = id,
                fileUri = contentUri,
                filePath = filePath,
                totalBytes = finalProcessedFile.length()
            )

            val completedItem = item.copy(
                status = DownloadStatus.COMPLETED,
                fileUri = contentUri,
                filePath = filePath,
                totalBytes = finalProcessedFile.length(),
                downloadedBytes = finalProcessedFile.length(),
                progress = 100,
                completedAt = System.currentTimeMillis()
            )
            _lastCompletedEvent.emit(completedItem)
            Log.d(TAG, "Download #$id completed successfully: $contentUri")
        } catch (e: Exception) {
            Log.e(TAG, "Download error for #${item.id}", e)
            val errMsg = e.localizedMessage ?: "Download failed"
            repository.updateStatus(id, DownloadStatus.FAILED, errMsg)
            val failedItem = item.copy(status = DownloadStatus.FAILED, errorMessage = errMsg)
            _lastFailedEvent.emit(failedItem)
        } finally {
            if (tempRawFile.exists()) tempRawFile.delete()
            if (finalProcessedFile != null && finalProcessedFile.exists()) finalProcessedFile.delete()
        }
    }
}
