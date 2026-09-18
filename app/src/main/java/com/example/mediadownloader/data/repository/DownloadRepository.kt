package com.example.mediadownloader.data.repository

import com.example.mediadownloader.data.db.DownloadDao
import com.example.mediadownloader.data.db.DownloadItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DownloadRepository(private val downloadDao: DownloadDao) {

    val allDownloads: Flow<List<DownloadItemEntity>> = downloadDao.getAllDownloads()
    val activeDownloads: Flow<List<DownloadItemEntity>> = downloadDao.getActiveDownloads()
    val completedDownloads: Flow<List<DownloadItemEntity>> = downloadDao.getCompletedDownloads()
    val failedDownloads: Flow<List<DownloadItemEntity>> = downloadDao.getFailedDownloads()

    suspend fun insert(item: DownloadItemEntity): Long = withContext(Dispatchers.IO) {
        downloadDao.insert(item)
    }

    suspend fun insertAll(items: List<DownloadItemEntity>): List<Long> = withContext(Dispatchers.IO) {
        downloadDao.insertAll(items)
    }

    suspend fun update(item: DownloadItemEntity) = withContext(Dispatchers.IO) {
        downloadDao.update(item)
    }

    suspend fun getById(id: Long): DownloadItemEntity? = withContext(Dispatchers.IO) {
        downloadDao.getById(id)
    }

    suspend fun getByUrl(sourceUrl: String): List<DownloadItemEntity> = withContext(Dispatchers.IO) {
        downloadDao.getByUrl(sourceUrl)
    }

    suspend fun getPendingDownloads(): List<DownloadItemEntity> = withContext(Dispatchers.IO) {
        downloadDao.getPendingDownloads()
    }

    suspend fun delete(item: DownloadItemEntity) = withContext(Dispatchers.IO) {
        downloadDao.delete(item)
    }

    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) {
        downloadDao.deleteById(id)
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        downloadDao.clearCompleted()
    }

    suspend fun cancelAllActive() = withContext(Dispatchers.IO) {
        downloadDao.cancelAllActive()
    }

    suspend fun retryFailed(id: Long) = withContext(Dispatchers.IO) {
        downloadDao.retryFailed(id)
    }

    suspend fun retryAllFailed() = withContext(Dispatchers.IO) {
        downloadDao.retryAllFailed()
    }

    suspend fun updateProgress(id: Long, downloadedBytes: Long, totalBytes: Long, progress: Int) = withContext(Dispatchers.IO) {
        downloadDao.updateProgress(id, downloadedBytes, totalBytes, progress)
    }

    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null) = withContext(Dispatchers.IO) {
        downloadDao.updateStatus(id, status, errorMessage)
    }

    suspend fun markCompleted(id: Long, fileUri: String, filePath: String, totalBytes: Long) = withContext(Dispatchers.IO) {
        downloadDao.markCompleted(id, fileUri, filePath, totalBytes, System.currentTimeMillis())
    }
}
